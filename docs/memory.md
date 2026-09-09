# Memory

## `-Xmx` does not bound chDB

The engine allocates outside the Java heap. `-Xmx512m` limits the heap; it does nothing to the
several hundred megabytes the engine may use for a query, and nothing to the ~350 MB of shared
library mapped at startup.

This surprises people in containers, where the usual failure is the kernel OOM-killing a JVM
that is well inside its `-Xmx`.

## What is actually using memory

| | Roughly | Bounded by |
|---|---|---|
| The engine shared library | 350 MB resident, mostly shared file-backed pages | nothing; it is the code |
| Query execution | depends on the query | `max_memory_usage` |
| One Arrow batch | engine block size, typically single-digit MB | the engine's block size |
| Java-side per row | transient objects only | the heap |
| The Java heap | | `-Xmx` |

## What to set

**In a container**, budget for the engine and cap the engine's queries:

```
docker run --memory=4g \
  -e JAVA_TOOL_OPTIONS="-Xmx1g" \
  myapp
```

```
jdbc:chdb:/data?max_memory_usage=2000000000
```

The arithmetic: 4 GB limit − 1 GB heap − 0.4 GB library − JVM overhead leaves the engine about
2 GB, which is what `max_memory_usage` should say.

A query that exceeds it raises a `SQLTransientException` with ClickHouse error code 241 and
SQLSTATE `53200`, and the connection stays usable. A recoverable error beats an OOM kill.

**The engine reads the cgroup limit itself**, so this holds even if you set nothing. Measured in
a 2 GB container: a query needing far more came back as the same code 241 exception either way,
and the JVM exited 0. The engine logs what it found — `Low memory system detected (2.00 GiB)` —
and sizes its own tracker from it.

Setting `max_memory_usage` is still worth doing, for two reasons the automatic limit does not
cover: it leaves headroom for the heap and everything else in the container rather than letting
one query claim the lot, and it applies outside containers, where there is no cgroup to read.

`scripts/run-memory-limit-test.sh` runs both cases and is part of CI.

## Streaming is what keeps result size out of the equation

A result set holds one Arrow batch at a time. Peak memory tracks the batch, not the result:

```java
// 20 million rows in a JVM with -Xmx512m. Measured RSS growth: 17 MB.
try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(
                "SELECT number, toString(number) FROM numbers(20000000)")) {
    while (rs.next()) {
        process(rs.getLong(1), rs.getString(2));
    }
}
```

Two things you have to do for that to hold:

- **Close the `ResultSet`.** Until you do, its batch is live and the engine's query is still
  running. Use try-with-resources; do not rely on the garbage collector, which does not know
  about native memory and has no reason to hurry.
- **Do not accumulate rows yourself.** Streaming the result into an `ArrayList` puts it all in
  the heap, which is the thing streaming avoids.

Reading one row of a huge result and closing is cheap and supported: the driver cancels the
query rather than draining it.

## Measuring it

**`jcmd <pid> VM.native_memory` does not cover chDB.** Native Memory Tracking instruments the
JVM's own allocators. The engine allocates through its own, so NMT reports a JVM that looks
fine next to a process that does not.

Use RSS from outside:

```bash
ps -o rss= -p <pid>          # KB
cat /proc/<pid>/status | grep VmRSS
```

Judge it by shape, not by an absolute number. An allocator legitimately keeps arenas after
freeing, so RSS does not return to where it started; what matters is that it reaches a plateau
rather than climbing linearly with the number of queries. `StreamingLifecycleIT` uses exactly
that criterion over a thousand queries.

## Checking for leaks in your own code

The driver counts its open native handles:

```java
import static org.chdb.internal.ChdbNative.*;

assertEquals(0, openHandleCount(KIND_STREAM));
assertEquals(0, openHandleCount(KIND_RESULT));
assertEquals(0, openHandleCount(KIND_CONNECTION));
```

Assert this at the end of a test suite. Every non-zero count is a `ResultSet`, `Statement` or
`Connection` that was not closed, and it tells you which kind.

## Shutting the engine down

`chdb_shutdown()` joins every engine thread, for a host that runs its own teardown — global
destructors, a finalizing runtime, a sanitizer exit handler — that would otherwise race them.

```java
// Every Connection closed and every ResultSet closed first.
int result = org.chdb.internal.ChdbNative.shutdown();
// 0 = stopped, 1 = something is still open, 2 = this engine has no chdb_shutdown
```

Not needed for a process that simply exits: the threads are reaped by exit, as they always
were. The pinned 26.7.0 engine does not export it and returns 2; see
[upstream findings §6](upstream-findings.md).
