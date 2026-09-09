# Engine findings from building the V1 binding

Facts about `chdb-core` that this binding had to work around, each one first observed on macOS
arm64 while implementing work-plan phases 0–8 against what was then the pinned baseline,
**v26.7.0**. They are recorded here because every one of them is a decision the driver's code
depends on, and because several belong upstream rather than in a Java driver.

The baseline has since moved to **v26.7.2-rc.2** (issue #8, for `chdb_shutdown()`), and four
of these entries changed with it rather than being carried over:

- **§2 was wrong about who was at fault** and is rewritten. The released header was always
  correct; the stale version constant came from this repository vendoring the source tree's
  copy instead of the tarball's.
- **§5 is now half fixed upstream.** An invalid *value* for a known setting fails the connect,
  as documented. An unrecognized setting *name* is still accepted and ignored.
- **§6 no longer applies to the baseline**: both optional symbols are exported now.
- **§9 is new**, from measuring what `chdb_shutdown()` does and does not do.
- **§10 is new**, from measuring which statements the streaming Arrow entry point accepts.

The rest still hold on the new engine, and the tests named against each are what demonstrates
that — they run in `mvn verify` against whichever engine is pinned.

Reproduce any of them with `mvn verify` — each has a test named below.

---

## 1. Opting out of chDB's signal handlers resets the JVM's

**Severity: would crash any embedding JVM. Worked around; needs an upstream API.**

`chdb_set_signal_handlers_enabled(0)` does two things, and the second is not implied by its
name:

```cpp
// programs/local/chdb.cpp
void chdb_set_signal_handlers_enabled(int enabled)
{
    HandledSignals::disable_signal_handlers.store(!enabled, std::memory_order_relaxed);
    if (!enabled)
        chdb_reset_signal_handlers();          // <-- resets to SIG_DFL
}

void chdb_reset_signal_handlers(void)
{
    static constexpr int deadly_signals[] = {SIGABRT, SIGSEGV, SIGILL, SIGBUS,
                                             SIGSYS, SIGFPE, SIGTSTP, SIGTRAP};
    ...
    for (int sig : deadly_signals)
        sigaction(sig, &sa, nullptr);          // sa.sa_handler = SIG_DFL
}
```

The reset is unconditional: it does not ask whether chDB installed the incumbent handler.
HotSpot's SIGSEGV, SIGBUS, SIGILL and SIGFPE handlers are load-bearing — implicit null checks,
stack banging and safepoint polling all run through them — so a JVM that opts out of chDB's
handlers loses the ability to service its own null dereference. The crash that follows names
neither chDB nor the opt-out.

Measured on this baseline, `protectHostSignalHandlers()` reports the shim restoring:

```
SIGSEGV, SIGILL, SIGBUS, SIGFPE, SIGTSTP
```

SIGABRT, SIGSYS and SIGTRAP are absent only because HotSpot leaves them at `SIG_DFL`, so the
reset does not change them. Running the same JVM under a sanitizer confirms it: the sanitizer
runtime installs handlers for those three, and the guard then reports all seven.

Two further details that shape the workaround:

- `chdb_connect()` re-runs the reset whenever the opt-out flag is set — twice, on the inner and
  outer connect paths (`chdb.cpp` lines 237 and 550). So the connect path needs the same
  protection as the opt-out call.
- The reset runs on *every* `chdb_set_signal_handlers_enabled(0)` call. The already-set flag
  does not short-circuit it, so the guard is load-bearing on every call rather than only the
  first.

**Workaround:** `chdb-jni/src/chdb_jni_signals.cpp` brackets both call sites with a
`SignalGuard` that snapshots every guarded signal's `sigaction`, makes the call, and restores
whatever changed, under a process-wide lock.

### 1a. The workaround cannot be made correct under concurrency

**Severity: kills the JVM, intermittently, with no crash report. Not worked around — it cannot
be from outside the engine.** This is issue #14.

Signal dispositions are process-wide, so "snapshot, call, restore" is not atomic with respect
to the *other* threads in the JVM. The lock `SignalGuard` holds serialises guard against guard;
it does nothing about the eight application threads a connection pool has running Java code.
Between `chdb_reset_signal_handlers()` inside `chdb_connect()` and the shim's restore, the
whole process has `SIGSEGV` at `SIG_DFL` — and any thread that takes one of HotSpot's
recoverable SIGSEGVs in that window is killed by the kernel.

Worse, it is killed **silently**: HotSpot writes `hs_err_pid*.log` from its own SIGSEGV
handler, which is the handler that was just removed. So the failure mode is a bare exit 139
with no crash report, no matter what `-XX:ErrorFile` is set to. That is precisely what #14
observed, and why waiting for a stack there was never going to work.

Measured with an observer thread sampling `signalDispositions()` while another thread opens and
closes 200 connections (`scripts/run-signal-window-test.sh measure`):

| platform | JVM | observations of a host handler at `SIG_DFL` | samples |
|---|---|---|---|
| macOS arm64 | HotSpot 11.0.25 | 648–803 | ~365,000 |
| linux-aarch64 | Red Hat 11.0.25 | 1,116–1,161 | ~490,000 |

About 0.2% of wall clock, a few microseconds per connect. Small, but it is hit: eight threads
opening connections alongside eight threads generating stack-guard SIGSEGVs
(`scripts/run-signal-window-test.sh stress`) killed the JVM in **8 of 8 runs on macOS arm64
(exit 138, SIGBUS) and 4 of 4 on linux-aarch64 (exit 139, SIGSEGV)**, with no `hs_err` file in
any of them.

Leaving the opt-out unset is not an escape. With the flag clear, the engine installs its own
deadly-signal handlers during connect instead, and the same observer sees them in place for
331,296 of 557,194 samples — roughly 60% of every connect, three orders of magnitude more
exposure — where they turn a SIGSEGV HotSpot would have recovered from into a fatal engine
crash. The opt-out is the lesser hazard, which is why the driver keeps it.

All the driver can do is reduce the number of windows, and it has: the opt-out is now made once
per process rather than once per `Connection.open()`, which took the macOS figure from 233–260
down to 179–184 over the same 200 connects. The window inside `chdb_connect()` is upstream's.

**What would remove it:** the same API as §1 — a way to set the opt-out flag without resetting
the incumbent handlers. With that, `chdb_connect()`'s two reset branches never fire and the
window disappears. Alternatively, `chdb_connect()` could stop resetting handlers even when the
flag is set: the flag's documented purpose is to suppress *future installs*, and re-resetting on
every connect is not needed for that.

**Tests:** `SignalHandlerIT` — including `jvmStillOwnsSegv`, which triggers a real
NullPointerException after connecting, and would terminate the JVM if the guard regressed, and
`concurrentConnectsNeverExposeAChdbHandler`, which is the only case that watches the
dispositions from a *different* thread than the one making the call. `scripts/run-signal-window-test.sh`
is the standalone reproducer; its `measure` count reaching zero is how an upstream fix gets
verified.

---

## 2. `CHDB_VERSION` is stale in the source tree, correct in the release — the bug was ours

**Severity: none upstream, for anyone using a release. Was a wrong vendoring source here; now
fixed.**

This entry used to say the v26.7.0 header shipped `#define CHDB_VERSION "26.5.1-rc.3"` while
`chdb_version()` returned `26.7.0`, and blamed upstream for not bumping the constant. Moving
the baseline turned up the real cause, which is worth recording because the conclusion was
inverted.

`chdb.h` exists in two places and they do not agree:

| | v26.7.0 | v26.7.2-rc.2 |
|---|---|---|
| `programs/local/chdb.h` at the git tag | `26.5.1-rc.3` | `26.7.2` |
| `chdb.h` inside the release tarball | `26.7.0` | `26.7.2-rc.2` |
| `chdb_version()` from that tarball's library | `26.7.0` | `26.7.2-rc.2` |

The release build stamps the constant, and the released artifact has always been right. What
was wrong was this repository's vendored copy: it was byte-identical to the *source tree* at
tag v26.7.0, stale constant included, rather than to the header inside the release tarball.

**Consequence for this binding:** `chdb-jni/include/chdb.h` is now taken from the release
tarball, which is the artifact this binding is built beside and ships against, and
`shim.built.against.engine.header` reads `26.7.2-rc.2` accordingly. The rule is recorded in
`chdb-jni/CMakeLists.txt` where the vendored header is included: copy it from the tarball, not
from the source tree, and do not edit it — its value is being byte-identical to what upstream
shipped, which is what makes the next comparison able to detect drift. This finding was made
exactly that way.

**What is left for upstream, and it is minor:** the in-tree constant is stale between releases,
so anyone building chdb-core from a checkout gets a header that misreports its own version.
Deriving it at configure time as well as at release time would close that.

**Unchanged decision:** the loader still compares `chdb_version()` against
`CHDB_JNI_EXPECTED_ENGINE_VERSION` from `scripts/engine.properties`, and still treats the
header's constant as build provenance only. A constant compiled into the shim cannot describe
the library that was actually loaded, however correct it is.

---

## 3. `--path=:memory:` creates a directory called `:memory:`

**Severity: silently turns an in-memory database into an on-disk one. Worked around.**

`chdb.h` documents `:memory:` as the default when no path is given. It is not, however,
recognized in the `--path` argument: the engine takes the string as a directory name and
creates `./:memory:/{data,metadata,store}` in the process's working directory. The "in-memory"
database is then on disk and outlives the JVM — two consecutive JVMs see each other's tables.

chdb-core's own ADBC driver has the same note:

```cpp
// programs/local/chdb-adbc.cpp:1009
/// ":memory:" is the engine default and must NOT be passed as --path:
if (db->path != ":memory:")
```

**Workaround:** `ChdbUrl.toConnectArguments()` omits `--path` entirely for `:memory:`.

**Suggested upstream fix:** recognize `:memory:` in `--path` and treat it as the default, so
that passing the documented value of a documented default is not a trap.

**Tests:** `StoragePathIT.memoryDoesNotPersist` asserts no `:memory:` directory appears;
`ChdbUrlTest.memoryOmitsPathArgument` pins the argument vector.

---

## 4. Bound parameter values are escaped text, not opaque bytes

**Severity: silent data corruption and spurious query failures. Worked around; the contract is
undocumented.**

`chdb_query_with_params_n` reads each value with `deserializeTextEscaped` and requires the
whole value to be consumed:

```cpp
// src/Interpreters/ReplaceQueryParameterVisitor.cpp
ReadBufferFromString read_buffer{value};
serialization->deserializeTextEscaped(temp_column, read_buffer, format_settings);
if (!read_buffer.eof())
    throw Exception(BAD_QUERY_PARAMETER, "Value {} cannot be parsed as {} ...");
```

That is TSV field syntax, which is not what "the engine resolves the type from the
`{name:Type}` placeholder" in the header's documentation leads a caller to expect. Three
observable consequences:

| Value bound | Arrives as | Why |
|---|---|---|
| `\'; DROP TABLE t; --` | `'; DROP TABLE t; --` | `\'` is an escape sequence; the backslash is consumed |
| `line1\nline2` (real newline) | error 457, `BAD_QUERY_PARAMETER` | a raw newline ends the TSV field, so the value "isn't parsed completely" |
| `NULL` intended as SQL NULL | the four characters `NULL` | `\N` is the NULL marker, and only under a nullable placeholder type |

None of these is an injection: the value never enters the statement text, so the worst case is
a wrong *value*, never a changed statement. But a driver that passes values through verbatim
corrupts every value containing a backslash and fails every value containing a newline or tab.

**Workaround:** `TextEscape` encodes values into escaped-text form, and `SqlParameterLexer`
renders the placeholder as `Nullable(String)` for a parameter bound to NULL so `\N` is read as
NULL rather than as the letter `N`. Because that depends on which parameters are null, the
statement text is assembled per execution rather than once at prepare time.

**Suggested upstream documentation:** state the escaping contract on
`chdb_query_with_params{,_n}`, and say that NULL requires both `\N` and a nullable placeholder
type. A length-carrying binary-safe binding API would remove the need for escaping altogether,
and would let a `String` parameter hold an arbitrary byte sequence — which the `_n` suffix
currently implies but does not deliver.

**Tests:** `PreparedStatementIT.injectionAttemptsAreData`, `.awkwardBytesSurvive`,
`.setNull`, `.setObjectNull`.

---

## 5. Invalid setting *values* now fail the connection; unknown *names* still do not

**Severity: half fixed upstream between v26.7.0 and v26.7.2-rc.2. The remaining half is a
silent typo, documented rather than worked around.**

`chdb.h` says of `chdb_connect`:

> An invalid value for a known setting fails the connection.

On v26.7.0 it did not. On v26.7.2-rc.2 it does. Measured through the driver, one connection
per URL:

| connection | v26.7.0 | v26.7.2-rc.2 |
|---|---|---|
| `?max_threads=4` | connects, `max_threads=4` | connects, `max_threads=4` |
| `?max_threads=not-a-number` | connects, setting ignored | **connect fails** |
| `?max_threads=-5` | connects, setting ignored | **connect fails** |
| `?max_memory_usage=abc` | connects, setting ignored | **connect fails** |
| `?no_such_setting_at_all=1` | connects, ignored | connects, ignored |

So an invalid value is now reported, as documented. An unrecognized setting *name* is still
accepted and silently dropped, which is the more common typo of the two: `max_thread=4` or
`max_memmory_usage=...` gives a working connection with the setting absent.

**Consequence for this binding:** the driver forwards unrecognized URL properties to the engine
as `--key=value` and cannot tell a setting name from a misspelling, so it still cannot catch
that case. Two things follow from the half that changed. The connect-failure diagnostic in
`chdb_jni.cpp` now leads with "a setting below has a value the engine rejects", because
`chdb_connect()` returns NULL with no message and the previous list named only storage-path
causes — a user passing `--max_threads=not-a-number` was told to check disk space.
`docs/unsupported.md` and `docs/native-loading.md` say which half is caught and which is not,
and recommend `SELECT value FROM system.settings WHERE name = '...'` for the half that is not.

**Suggested upstream fix:** fail, or at least warn, on a setting name the engine does not
recognize. Validating values without validating names catches the rarer mistake.

---

## 6. `chdb_classify_query_n` and `chdb_shutdown` arrived in v26.7.2-rc.2

**Severity: expected; still handled as optional symbols.**

Neither is in v26.7.0 or v26.7.1-rc.1. Both are in v26.7.2-rc.2, which is now the baseline.
Checked in the release library rather than in the source's export list, because the two are not
the same claim:

```
$ nm -gU libchdb.so | grep -E ' _chdb_(classify_query_n|shutdown)$'
0000000005a94ec0 T _chdb_classify_query_n
0000000005a94fc0 T _chdb_shutdown
```

On v26.7.0 and v26.7.1-rc.1 the same command prints nothing, and neither name appears in
`chdb/libchdb_export_macos.txt`, `chdb/libchdb_export.map` or `programs/local/chdb.h` at either
tag — which is worth stating because the driver's own comments claimed v26.7.1-rc.1 for a
while.

This is what work plan §5.1's split between required and optional symbols is for. The required
set — the 18 entry points `chdb_jni.cpp` calls directly — is checked by the linker at build time
and re-checked by the loader at startup. These two are resolved with `dlsym(RTLD_DEFAULT)` in
`chdb_optional_api.cpp`, and they stay there now that the baseline has them, because the
loader can be pointed at another `libchdb` of the same version and an absent symbol has to
degrade rather than fail the load:

- no classifier: `StatementShape` falls back to a leading-keyword scan to decide whether a
  statement has a result set to stream;
- no `chdb_shutdown`: `ChdbNative.shutdown()` returns 2, and the engine threads are reaped by
  process exit as they always were.

`chdb_shutdown()` turned out to be narrower than issue #8 assumed — see §9.

---

## 7. `UUID` and `FixedString(16)` are indistinguishable in Arrow output

**Severity: cosmetic, lossless. Documented rather than worked around.**

The engine's Arrow converter exports both as `w:16` (fixed-size binary of 16 bytes). The public
`chdb_arrow_options` struct has no knob to change that — the internal
`chdb_query_arrow_with_settings_n` used by the ADBC driver takes an
`outputUuidAsFixedByteArray` flag, but it is not on the public API.

So the driver has to pick one meaning for `w:16`. It reports UUID, because a UUID column is far
more common than a 16-byte `FixedString` and the work plan's type matrix asks for a UUID
mapping. Nothing is lost either way: `getBytes()` returns the raw 16 bytes for both.

**Suggested upstream fix:** expose `output_uuid_as_fixed_byte_array` on `chdb_arrow_options`,
so a caller can ask for UUIDs as text and remove the ambiguity.

**Documented in:** `docs/type-mapping.md`.

---

## 8. The released libchdb cannot run under AddressSanitizer

**Severity: blocks a release gate as written. Worked around by splitting the coverage; the
remaining half needs upstream.**

Work plan section 5.11 asks for ASan, LSan and UBSan runs. ASan replaces `malloc` for the whole
process, and a preloaded ASan in a JVM that loads the released `libchdb` crashes before the
suite finishes:

| JIT | Crash site |
|---|---|
| default | `libjvm.dylib` — `IndexSet::initialize`, inside HotSpot's C2 register allocator |
| `-Xint` | `libchdb.so+0x5801074` |

Neither the release engine nor HotSpot is ASan-clean, and `-XX:TieredStopAtLevel=1` does not
help — it only moves the crash from the JIT into the engine. There is no ASan option that
disables allocator interception, so this is not a configuration problem.

The plan already anticipates the answer: it asks for a run against "a full chDB sanitizer
build", and chdb-core does not publish one.

**What is covered instead:**

- **UBSan over the whole JDBC suite**, in a real JVM against the real engine. UBSan instruments
  arithmetic and casts rather than intercepting allocation, so it coexists with both. 90 tests,
  zero reports.
- **ASan + UBSan over `chdb_jni_test`**, a JVM-free and engine-free harness for the shim's
  standalone logic: Arrow format parsing, the handle registry, the signal guard. 197 checks,
  zero reports — and it found five real parser defects on its first run, listed below.

**What would close the gap:** a sanitizer build of chdb-core published as a release asset, or
buildable by a documented target. Then `scripts/run-sanitizer-tests.sh <platform>
address,undefined` would run the JDBC suite too rather than skipping it with an explanation.

### A related trap, for anyone running a JVM under any sanitizer

A sanitizer installs handlers for SIGSEGV, SIGBUS, SIGILL and SIGFPE, and HotSpot needs all
four. Without `handle_segv=0:handle_sigbus=0:handle_sigfpe=0:handle_sigill=0`, the JVM's
ordinary recovery from an implicit null check is reported as a fatal `DEADLYSIGNAL` — the same
conflict as finding §1, arriving from the other direction. Observed on the first UBSan run,
against `SignalHandlerIT.jvmStillOwnsSegv`.

And on macOS the preload has to be exported by the shell that execs `java`: dyld drops `DYLD_*`
from an environment handed to a hardened binary, so a surefire fork receives `LD_PRELOAD` and
`ASAN_OPTIONS` but not `DYLD_INSERT_LIBRARIES`, and ASan then loads by `dlopen` and aborts.

### What CI found in the guard itself

Worth recording because it is the shape of bug local testing structurally cannot reach.

On Linux the signal guard reported four signals -- SIGABRT, SIGSYS, SIGTSTP, SIGTRAP -- as
clobbered-and-restored on every call, and `SignalHandlerIT.optOutRestoresHostHandlers` failed
because the before/after dispositions never matched. The handler was `SIG_DFL` in both
snapshots. Only the flags differed:

```
before:  SIGABRT=SIG_DFL flags=0x0
after:   SIGABRT=SIG_DFL flags=0x4000000
```

`0x04000000` is `SA_RESTORER`, which glibc sets on every `sigaction()` it performs to name the
trampoline a handler returns through. chDB's reset sets `SIG_DFL` over `SIG_DFL` and the flag
appears; the guard's restore goes through glibc too and sets it again, so the difference could
never be removed. The disposition was behaviourally identical throughout -- with `SIG_DFL`
there is no handler and so no trampoline.

The comparison and the report now mask flags a caller cannot control. macOS has no such flag,
so the whole local suite passed; and it surfaced under the sanitizer job rather than the plain
Linux job only because the JUnit console launcher orders test methods differently from
surefire, which let this test run before anything else had already set the flag.

### A packaging defect the runner change exposed

Unrelated to the engine, but found while moving CI off the scarce `macos-13` runners and worth
recording in the same place.

The shim had no `CMAKE_OSX_DEPLOYMENT_TARGET`, so its minimum macOS followed the build
machine's SDK. Built locally it wanted **macOS 26**, sitting next to an engine that runs on
**macOS 11**:

```
shim:   minos 26.0
engine: minos 11.0
```

A package built that way loads for nobody below the builder's OS, and moving CI to a newer
runner image would have raised the bar further without anything failing. The target is now
pinned to what chdb-core's own wheel tags declare -- 11.0 for arm64, 10.15 for x86_64 -- and
`build-native.sh` fails the build if the shim ever ends up demanding a newer macOS than the
engine beside it.

### What the harness found

Five formats the shim's parser accepted and the Java parser refused: `d:9`, `d:`, `d:a,b`,
`ts`, `tsX:UTC`. The shim's parser decides how many bytes of each Arrow buffer Java may see and
the Java parser decides what the bytes mean, so the two disagreeing is a latent inconsistency
even where it happens to be harmless. The cause was `std::atoi`, which returns 0 for
non-numeric input, and an unconditional 8-byte width for anything starting `ts`. Both parsers
are now pinned to the same table, in `ArrowFieldTypeTest.unsupportedFormats` and
`chdb_jni_test.cpp`.

---

## 9. `chdb_shutdown()` cannot make an unclean exit clean

**Severity: the exit-time abort stays a driver problem. Not worked around; needs either an
upstream change or an accepted limitation.**

Issue #8 moved the baseline to v26.7.2-rc.2 to get `chdb_shutdown()`, on the expectation that
it would replace the driver's shutdown hook. It cannot, and measuring it says why. The header
is explicit about the reason:

> Close every connection and destroy every result first. While a connection is still open this
> does nothing and returns `CHDBError`, because tearing the engine down under a live connection
> would leave it dangling.

So it is not a teardown a host can call on the way out — it is a teardown a host can call once
it has already done the hard part. Measured on macOS arm64, a JVM that leaks a streaming
`ResultSet` and falls off the end of `main` with the driver's hook off:

| what the process does before exiting | `chdb_shutdown()` returns | exit |
|---|---|---|
| nothing | — | 134 |
| calls it with the connection still open | 1 (`CHDBError`) | 134 |
| closes the connection, then calls it | 0 | 0 |

The third row is what the driver's hook now does: drain the connection registry, then call it.
The second row is why the registry could not be deleted.

What the call buys, measured, is nothing an exit code can see: every shape tried came out
identical with and without it, six runs each. It is kept because the ordering it guarantees —
no engine thread alive when the host proceeds to its own native teardown — is real even where
no exit code reflects it, and because it is free once the connections are closed.

**A second finding fell out of the same measurement.** Closing a connection whose query is
still executing can itself trigger the abort. Four threads each running one long aggregate,
`main` returning while they are in flight:

| engine | driver hook | exit, over runs |
|---|---|---|
| 26.7.0 | on | 134, 134, 0 |
| 26.7.0 | off | 0, 0, 0 |
| 26.7.2-rc.2 | on (with or without `chdb_shutdown()`) | 134 × 12 |
| 26.7.2-rc.2 | off | 0 × 6 |

Both engines behave the same way, so this is not something the baseline move introduced, and
the `chdb_shutdown()` call makes no difference to it. But it means the hook can turn a clean
exit into an abort for an application that queries from background threads — the one case
where the safety net is the hazard. `docs/unsupported.md` says so, and
`-Dchdb.shutdownHook=false` is the switch.

**Suggested upstream fix:** a shutdown that does not require the caller to have closed
everything first — cancel and join whatever is running, since the process is going away
regardless — would let a host make its own exit safe without racing its own application
threads. Failing that, `chdb_close_conn()` on a connection with a query in flight should be
safe, which would let the hook do its job in the one case it currently cannot.

---

## 10. The streaming Arrow API refuses every non-`SELECT` read

**Severity: would have shipped a driver where `SHOW TABLES` fails. Worked around with a second
route; the parameter half still needs upstream.**

`chdb_stream_query_arrow_n` admits a statement only if it parses as an `ASTSelectWithUnionQuery`:

```cpp
// src/Client/ClientBase.cpp, ClientBase::processTextAsSingleQuery
else if (streaming_query_context->is_streaming_query && parsed_query->as<ASTSelectWithUnionQuery>())
    ...
else
    throw Exception(ErrorCodes::BAD_ARGUMENTS, "Streaming query is not supported for query: {}", full_query);
```

Every other row-returning statement is refused. Measured on v26.7.0, macOS arm64: `SHOW
TABLES`, `SHOW DATABASES`, `SHOW CREATE TABLE`, `SHOW COLUMNS`, `SHOW SETTINGS`, `DESCRIBE`,
`DESC`, `EXISTS TABLE`, `EXISTS DATABASE`, `CHECK TABLE` and all seven `EXPLAIN` forms — all
with `Code: 36 ... Streaming query is not supported`. The engine's own classifier puts them in
`CHDB_QUERY_READ_ONLY`, so a driver that treats "read-only" as "streamable" has no route for
any of them.

`chdb_query_arrow_n` accepts all of them and exports the whole result through the same Arrow C
Data Interface, so nothing above the shim has to change. The driver therefore carries three
routes rather than two, and `StatementShape` decides which from the leading keyword; see
`NonStreamableResultsIT`.

Two things are worth noting for upstream:

- **The refusal is raised before execution**, in `processTextAsSingleQuery` and ahead of
  `processParsedSingleQuery`. Confirmed by measurement: `WITH q AS (SELECT 2 AS v) INSERT INTO
  t SELECT v FROM q` is accepted by the parser, refused by the streaming door, and leaves the
  table untouched. That ordering is what would make a retry safe — but it is only observable
  through an error-message prefix, and this driver will not key a re-execution decision on
  one. A `chdb_state` or error code distinguishing "not streamable, nothing ran" from every
  other failure would let a client retry without guessing.
- **`chdb_query_arrow_n` has no `_with_params_n` variant.** `chdb_query_arrow_with_settings_n`
  takes a `NameToNameMap` of parameters but lives in `namespace CHDB` and is absent from
  `chdb/libchdb_export_macos.txt` and `chdb/libchdb_export.map`. So a non-streamable statement
  with server-side bindings has no entry point at all, and the driver reports that rather than
  interpolating the values into the SQL.

**Suggested upstream fix:** export a `chdb_query_arrow_with_params_n`, and give the streaming
initialisation a machine-readable "this statement is not streamable" state so a client can
route on it instead of on a keyword scan.

### The materialized route's peak is entirely engine-side

Worth recording separately, because it decides what a client can and cannot do about it.
`chdb_query_arrow_n` has already allocated the whole result by the time it returns: measured
RSS after the open, with not one row read, is +234 MB for a 108 MB result and +896 MB for an
864 MB one, and it does not move while the caller iterates. The streaming route grows by
48 KB–6.5 MB for the same queries. `chdb-arrow-output.cpp` shows why: `runMaterializedArrowQuery`
collects every chunk, converts all of them into one `arrow::Table`, and exports a
`TableBatchReader` over that — so the ClickHouse chunks and their Arrow copy are both live
before the function returns.

The consequence for any client is that a row cap applied on its side of the ABI is too late by
construction. What does work is the engine's own per-query accounting, which does cover this
path: under `SET max_memory_usage`, an oversized materialization fails in milliseconds with
`Code: 241` and leaves no handles behind (15/15 clean refusals under a 100 MB cap).

Two smaller observations from measuring it:

- **No setting passed in the connect argument vector reaches the session.** Given as
  `--max_memory_usage=…`, `--max_threads=…`, `--max_result_rows=…` or `--max_block_size=…`,
  `getSetting()` reports the default in every case; the same values via `SET` all take effect.
  This is not specific to the Arrow routes and predates them, but it means a memory cap cannot
  currently be set from a JDBC URL, only with `SET`. Related to finding 5 above, and the reason
  `ChdbUrl.toConnectArguments`'s promise that "every ClickHouse query setting [is] reachable
  from a JDBC URL" does not hold today.
- **Errors on this path arrive wrapped two or three times**: `Code: 241. DB::Exception: Code:
  241. DB::Exception: Code: 241. DB::Exception: Query memory limit exceeded: …`, with the
  nesting depth varying between runs. Cosmetic — `ChdbExceptions` still extracts 241 from the
  first occurrence — but the message a user sees is worse than the streaming path's single
  wrap.

**Documented in:** `NonStreamableResultsIT`, `StatementShape`, `docs/memory.md`, issue #12.

---

## 10. Nothing can be cancelled until a query has returned a handle

**Severity: made `setQueryTimeout` silently ineffective for a whole class of statements. Worked
around; needs an upstream API to fix properly.**

Every cancel the C ABI exports takes a result or stream handle — `chdb_stream_cancel_query`,
`chdb_streaming_cancel_query`, `chdb_stream_cancel_insert` — and there is no connection-level
cancel. So the call that *produces* the handle cannot be interrupted: there is nothing to pass
to a cancel function while it runs.

How long that window is depends entirely on the statement:

| statement | where the time goes |
|---|---|
| `SELECT` that emits as it scans | milliseconds in the open, the rest in the fetches |
| full aggregate, `GROUP BY`, `ORDER BY` without `LIMIT` | **the whole query is in the open** — there is no first batch until the scan finishes |
| anything on the materialized Arrow route | **the whole statement is in the open** |

Measured on v26.7.0 before the driver handled it: `setQueryTimeout(1)` on `SELECT
max(sipHash64(number)) FROM numbers(2000000000)` returned a *working result set* after 12.65
seconds. The timer fired on schedule, found no handle to cancel, and did nothing — so the
caller got a late success rather than a timeout, which is worse than either a timeout or a
hang.

The driver now treats an expired timeout as a deadline once the open returns: it closes the
result set and raises `SQLTimeoutException` (SQLSTATE `57014`) naming why nothing was
interrupted. The work is still spent — that part cannot be fixed from a client — but
`setQueryTimeout` is no longer a setting that quietly does nothing. `Statement.cancel()` from
another thread during the same window is handled the same way.

**Suggested upstream fix:** a `chdb_cancel_query(chdb_connection)` that interrupts whatever the
connection is currently executing, so a client can act before a handle exists.

**Documented in:** `ChdbStatement.checkDeadlineSurvivedTheOpen`, `QueryTimeout`,
`StreamingLifecycleIT.queryTimeoutDuringTheOpen`,
`NonStreamableResultsIT.queryTimeoutOnTheMaterializedRoute`.

---

## Things that worked exactly as documented

Worth recording, because they carried the design:

- **Arrow C Data Interface ownership.** An `ArrowArray` from `get_next` is owned
  independently of the batch stream it came from, so the stream can be released immediately
  and the array kept — which is what makes one-batch-at-a-time streaming possible. The
  schema from `get_schema` likewise outlives its stream.
- **Eager first fetch for the schema.** `chdb_stream_query_arrow` produces no schema until a
  batch exists, and fetching the first batch at open both yields the schema and lets JDBC's
  `getMetaData()` answer before `next()`. chdb-core's ADBC driver does the same thing for the
  same reason.
- **`chdb_stream_fetch_result` idempotence past end of stream** behaves as documented, which
  is what lets `next()` be safely called after it has returned false.
- **Streaming memory.** 20 million rows read in a JVM with `-Xmx512m` grew RSS by 17 MB.
- **Multiple connections to one storage path** work, and see each other's committed writes.
