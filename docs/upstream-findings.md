# Engine findings from building the V1 binding

Facts about `chdb-core` that this binding had to work around, each one observed against the
pinned baseline **v26.7.0** on macOS arm64 while implementing work-plan phases 0–8. They are
recorded here because every one of them is a decision the driver's code depends on, and because
several belong upstream rather than in a Java driver.

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
reset does not change them.

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

**What would remove it:** an entry point that suppresses future installs without touching the
incumbent handlers — the work plan's §5.6 first choice. `chdb_reset_signal_handlers()` is
useful on its own and should keep its current behaviour; it is
`chdb_set_signal_handlers_enabled(0)` calling it that makes the compound operation unusable
from a host runtime.

**Tests:** `SignalHandlerIT` — including `jvmStillOwnsSegv`, which triggers a real
NullPointerException after connecting, and would terminate the JVM if the guard regressed.

---

## 2. `CHDB_VERSION` in the v26.7.0 header says `26.5.1-rc.3`

**Severity: breaks a documented release check. Not worked around; the header constant is
unusable.**

The header at tag `v26.7.0` carries:

```c
#define CHDB_VERSION "26.5.1-rc.3"
```

while `chdb_version()` from the same release's shared library returns `26.7.0`. The constant
was not bumped for the release.

Work plan §5.1 asks for a consistency check across `chdb_version()`, the release tag and the
header version. Two of the three agree; the header does not, and no Java-side check can fix
that.

**Consequence for this binding:** the loader compares `chdb_version()` against
`CHDB_JNI_EXPECTED_ENGINE_VERSION`, which comes from `scripts/engine.properties` via CMake, and
treats the header's `CHDB_VERSION` as build provenance only. It is reported by
`ChdbNative.shimBuildInfo()` as `shim.built.against.engine.header` so a mismatched pair is
diagnosable, and is deliberately not used for any decision.

**Suggested upstream fix:** derive `CHDB_VERSION` from the release version at build time, or
drop it, since `chdb_version()` already answers the question correctly at runtime.

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

## 5. Invalid setting values do not fail the connection

**Severity: documentation mismatch. Not worked around; nothing to work around.**

`chdb.h` says of `chdb_connect`:

> An invalid value for a known setting fails the connection.

On v26.7.0 it does not. All three of these connect successfully:

```
--max_threads=not-a-number
--max_threads=-5
--no_such_setting_at_all=1
```

A caller that mistypes a setting in a JDBC URL gets a working connection with the setting
silently absent.

**Consequence for this binding:** the driver forwards unknown properties to the engine as
`--key=value` and cannot validate them, so a typo is invisible. `docs/native-loading.md` says
so rather than implying the engine checks.

**Suggested upstream fix:** either validate at connect as documented, or amend the
documentation. Validating is the more useful of the two for every binding.

---

## 6. `chdb_classify_query_n` and `chdb_shutdown` are absent from v26.7.0

**Severity: expected; handled as optional symbols.**

Both landed in v26.7.1-rc.1, after the pinned V1 baseline. Verified against the release
library:

```
$ nm -gU libchdb.so | grep -E ' _chdb_(classify_query_n|shutdown)$'
(no output)
```

This is what work plan §5.1's split between required and optional symbols is for. The required
set — the 18 entry points `chdb_jni.cpp` calls directly — is checked by the linker at build time
and re-checked by the loader at startup. The optional two are resolved with
`dlsym(RTLD_DEFAULT)` in `chdb_optional_api.cpp` and their absence is not an error:

- no classifier: `StatementShape` falls back to a leading-keyword scan to decide whether a
  statement has a result set to stream;
- no `chdb_shutdown`: `ChdbNative.shutdown()` returns 2, and the engine threads are reaped by
  process exit as they always were.

Both are worth having, so V1.1 on a stable engine that exports them should prefer the
classifier over the keyword scan — the engine's own parser is authoritative and the scan is
not.

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

### What the harness found

Five formats the shim's parser accepted and the Java parser refused: `d:9`, `d:`, `d:a,b`,
`ts`, `tsX:UTC`. The shim's parser decides how many bytes of each Arrow buffer Java may see and
the Java parser decides what the bytes mean, so the two disagreeing is a latent inconsistency
even where it happens to be harmless. The cause was `std::atoi`, which returns 0 for
non-numeric input, and an unconditional 8-byte width for anything starting `ts`. Both parsers
are now pinned to the same table, in `ArrowFieldTypeTest.unsupportedFormats` and
`chdb_jni_test.cpp`.

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
