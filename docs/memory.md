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

That URL form works on the pinned v26.7.3 baseline. First measured on v26.7.2-rc.2 with
`?max_threads=7&max_result_rows=13&max_block_size=4096`: `system.settings` reports 7, 13 and
4096 with `changed = 1`, and the cap is enforced rather than merely reported — a 100-row
`SELECT` under `max_result_rows=13` fails with ClickHouse error 396.

**On v26.7.0 and v26.7.1-rc.1 it was silently inert**, if you are pointing the loader at one of
those: settings in the connect argument vector never reached a session, so the same URL left
`max_threads` at `auto(18)`, `max_result_rows` at 0 and `max_block_size` at 65409, and no cap
was applied. `SET max_memory_usage = 2000000000` on the connection was the workaround, and still
works. Fixed upstream in v26.7.2-rc.2 (chdb-core #191, commit `3231c03afca`); both measurements
are in [findings §10](upstream-findings.md). The engine's automatic cgroup limit below applies
regardless of either, which is what keeps this from being an OOM kill.

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

## One route is not bounded: `SHOW`, `DESCRIBE`, `EXPLAIN`, `EXISTS`, `CHECK`

These cannot be streamed — the engine's streaming entry point accepts only a SELECT pipeline —
so they run through `chdb_query_arrow_n`, which materializes the whole result.

**The peak is inside the engine, before the driver has a handle.** Measured on v26.7.0, arm64,
comparing the two routes on the same query and sampling RSS after the open with not one row
read:

| result payload | materialized, RSS at open | while reading | streamed, RSS at open |
|---|---|---|---|
| 108 MB | +234 MB | +0 | +48 KB |
| 432 MB | +669 MB | +0 | +6.4 MB |
| 864 MB | +896 MB | +0 | +6.5 MB |

`RSS at open` is already the peak: it does not move while the caller iterates. `chdb-arrow-output.cpp`
shows why — the engine collects every chunk, converts all of them into one `arrow::Table`, and
only then exports a `TableBatchReader` over it, so both the ClickHouse chunks and the Arrow copy
are live before the call returns.

**So the JDBC knobs cannot help on this route, and the driver does not pretend otherwise.**
`setMaxRows`, closing the `ResultSet` after one row, and any row cap the driver could impose all
run after the memory has been spent. There is nothing left to save.

**What does bound it is the engine's own per-query accounting**, which covers this path. Set it
the same way as anywhere else — in the URL, or with `SET` on a connection you already have:

```
jdbc:chdb:/data?max_memory_usage=2000000000
```

```java
statement.execute("SET max_memory_usage = 2000000000");
```

An oversized materialization then fails in milliseconds with ClickHouse error 241, which the
driver maps to `SQLTransientException` (SQLSTATE `53200`) — not an OOM, and not a dead process.
Measured: 15 out of 15 clean refusals under a 100 MB cap. Both forms take effect on the pinned
baseline; only on engines before v26.7.2-rc.2 was the URL form inert, as described
[above](#what-to-set) and in [findings §10](upstream-findings.md).

**In practice the exposure is small, because the row count of each of these statements is a
catalog or schema quantity rather than a data quantity.** `SHOW TABLES` over a 20,001-table
catalog: 3 MB and 10 ms. `CHECK TABLE`: one row of a part path per active part, and ClickHouse's
own `parts_to_throw_insert` keeps that in the thousands. `DESCRIBE`: one row per column.
`EXPLAIN`: one row per plan line. None of them scales with the size of a table.

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
were. The pinned 26.7.3 engine exports it, so 2 is what an older engine returns; see
[upstream findings §6](upstream-findings.md). The driver's shutdown hook already calls it
after closing the connections it knows about, so a host that leaves the hook installed does
not need to.
