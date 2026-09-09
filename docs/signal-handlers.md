# Signal handlers

## The short version

chDB installs process-wide crash handlers so it can print ClickHouse-style stack traces. That
is wrong for a JVM: HotSpot's own SIGSEGV, SIGBUS, SIGILL and SIGFPE handlers are load-bearing.
So the driver opts out of chDB's handlers — and repairs the damage that opting out does.

You do not have to configure anything. This page is here because the behaviour is surprising,
because you lose one diagnostic, and because the repair is not quite complete: a residual
few-microsecond window per connect is not closable from outside the engine, and matters only if
you open connections while other threads are running. See
[What the guard cannot do](#what-the-guard-cannot-do).

## Why the JVM needs its own handlers

HotSpot does not treat SIGSEGV as a crash. It uses it:

- **implicit null checks** — a field access on `null` takes a SIGSEGV, and HotSpot's handler
  turns it into a `NullPointerException`;
- **stack banging** — a guard page touch becomes `StackOverflowError`;
- **safepoint polling** on some platforms.

Every Java program depends on this. A JVM whose SIGSEGV disposition is `SIG_DFL` dies on the
first ordinary null dereference, with a core dump that names neither chDB nor the driver.

## What chDB does, exactly

`chdb_set_signal_handlers_enabled(0)` sets a flag suppressing future installs, and then calls
`chdb_reset_signal_handlers()`, which unconditionally sets eight signals to `SIG_DFL`:

```
SIGABRT  SIGSEGV  SIGILL  SIGBUS  SIGSYS  SIGFPE  SIGTSTP  SIGTRAP
```

It does not check whether chDB installed the incumbent handler. So the call that opts out of
chDB's handlers also destroys the JVM's, and `chdb_connect()` re-runs the reset on every
connect while the flag is set.

## What the driver does

The JNI shim brackets every call that can reach that code:

1. snapshot the `sigaction` of every guarded signal;
2. make the chDB call;
3. restore whatever changed, and report which signals those were.

Under a process-wide lock, so two threads cannot interleave a reset with a restore. The guard
is applied on the opt-out call and on every connect, because both re-run the reset. The opt-out
is made once per process, at load; the reset inside `chdb_connect()` is not something the driver
can decline, so that one is guarded per connect.

You can see it happen:

```java
String[] restored = org.chdb.internal.ChdbNative.protectHostSignalHandlers();
System.out.println(Arrays.toString(restored));
// [SIGSEGV, SIGILL, SIGBUS, SIGFPE, SIGTSTP]   on macOS arm64, HotSpot 21
```

Those five are the ones HotSpot had installed. The other three were already `SIG_DFL`, so
resetting them changed nothing.

To check that nothing leaked, before and after anything:

```java
String before = ChdbNative.signalDispositions();
// ... connect, query, close ...
assert before.equals(ChdbNative.signalDispositions());
```

`SignalHandlerIT` asserts exactly this across load, connect, query and close, and is a release
gate for V1 — the driver is not publishable if it fails.

## What the guard cannot do

That check is taken from the thread that made the call, after it returned, so it only ever sees
what the guard restored. It is blind to the thing that actually bites.

Signal dispositions belong to the process, not the thread, so "snapshot, call, restore" cannot
be atomic against the *rest* of the JVM. Between chDB's reset — which happens inside
`chdb_connect()`, before the shim gets control back — and the guard's restore, the whole
process has `SIGSEGV` at `SIG_DFL` for a few microseconds. A thread that takes one of HotSpot's
recoverable SIGSEGVs in that window is killed by the kernel, and no `hs_err_pid*.log` is
written, because HotSpot's crash reporter is the handler that was just removed. The symptom is
a bare exit 139 with nothing to read.

This needs concurrency to matter: something has to be running Java code while another thread
opens a connection. A single-threaded application never sees it. A connection pool that opens
connections while other threads work is exactly the shape that does — see
[issue #14](https://github.com/chdb-io/chdb-java/issues/14) and
[upstream findings §1a](upstream-findings.md).

Measured over 200 connects with an observer thread, the exposure is about 0.2% of wall clock.
If you want to see it, or to check whether a newer engine has fixed it:

```
scripts/run-signal-window-test.sh measure     # non-destructive; counts the window
scripts/run-signal-window-test.sh stress      # demonstrates it is lethal; kills the JVM
```

There is no setting that avoids this, and the driver has already taken the only reduction
available to it — making the opt-out once per process rather than once per connection. Closing
the window needs an engine that does not reset host handlers on the connect path. Until then,
the practical mitigation is to open connections early and keep them, rather than opening one
per request: the exposure is per `chdb_connect()`, so a pool with a stable set of connections
pays it a handful of times at startup instead of continuously.

## What you lose

**ClickHouse-format native crash traces.** If the engine segfaults, you get the JVM's
`hs_err_pid*.log` and a core dump, not ClickHouse's own symbolized stack trace with query
context. The JVM's report still names the failing native frames, so it is not a dead end, but
it is less informative about what the engine was doing.

That is the trade V1 makes: a JVM that stays alive is worth more than a better crash report
from a JVM that dies on every null pointer.

**A native crash still ends the process.** V1 is an in-process binding with no crash isolation
(work plan §2.3). If the engine crashes, your JVM goes with it. Do not run chDB in-process in a
service where that is unacceptable; a subprocess isolation mode is on the post-V1 list.

## Signals the driver does not touch

`SIGTERM`, `SIGINT`, `SIGQUIT` and `SIGHUP` are yours. chDB's reset does not include them, so
your shutdown hooks and `kill` handling work normally. The driver guards them anyway — snapshot
and restore, so an unchanged disposition is a no-op — so that an upstream change to the reset
list cannot break them silently.

## If you want chDB's handlers instead

You cannot have them and a working JVM. The driver does not offer a switch, because the only
thing a switch could do is break the JVM in a way that would be reported as a driver bug.

If you are diagnosing an engine crash and want ClickHouse's trace, reproduce it outside the
JVM — `clickhouse-local`, or the Python or CLI binding, which are not subject to this
constraint.

## Upstream

The compound behaviour of `chdb_set_signal_handlers_enabled(0)` is the problem; an entry point
that suppresses future installs without touching incumbent handlers would remove the need for
the guard entirely. Detail and reproduction in
[upstream findings §1](upstream-findings.md).
