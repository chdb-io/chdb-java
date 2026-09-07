# V1 progress

Status of every phase in [`CHDB_JAVA_V1_WORK_PLAN.md`](../CHDB_JAVA_V1_WORK_PLAN.md), as of the
JDBC-Alpha rebuild. Kept separate from the plan so the plan stays the specification and this
stays the report.

Legend: ✅ done and tested · 🟡 partly done · ⬜ not started · ➖ out of this milestone

Verified on **macOS arm64 / JDK 21 / engine 26.7.0**. The other three platforms have build and
CI definitions but have not been run — that is the single largest gap.

---

## Phase 0 — Freeze V1 decisions and upstream dependencies

| | Item | Notes |
|---|---|---|
| ⬜ | Plan reviewed by Core, Java and release owners | needs people, not code |
| ✅ | Java 11 floor | `maven.compiler.release=11`; CI matrix starts at 11 |
| ✅ | Four glibc/macOS platforms | `Platform`, with musl detected and refused |
| ✅ | Direct JNI is the mainline, ADBC the experiment | no Arrow Java dependency anywhere |
| ✅ | Engine release, dynamic-library checksum and symbol set pinned | `scripts/engine.properties`, SHA-256 per platform from the GitHub release digests |
| 🟡 | Consistency check across `chdb_version()`, tag and header version | the check exists; the **header constant is wrong upstream** (reads `26.5.1-rc.3` at tag `v26.7.0`), so it is provenance only — [findings §2](upstream-findings.md) |
| ✅ | C API split into required and optional symbols | 18 required, linker- and load-checked; `chdb_classify_query_n` and `chdb_shutdown` optional via `dlsym` |
| ⬜ | C ABI version and compatibility promise confirmed with Core | needs upstream agreement |
| 🟡 | Signal-handler requirement raised upstream | reproduced, measured and written up in [findings §1](upstream-findings.md); not yet filed |

## Phase 1 — Rebuild the repository and build skeleton

All ✅. History, `LICENSE` and the `org.chdb.jdbc` package name kept; the old prototype's
`src/` removed. Multi-module root, `chdb-jdbc`, `chdb-jni`, four platform modules, `chdb-bom`,
integration-test and example modules. `javac -h` generates the JNI header and the shim compiles
against it, so a Java/native signature drift is a build error. `find_package(JNI)` replaces the
committed `jni.h`. C++17, `-Wall -Wextra -Wpedantic` with the error-swallowing warnings as
errors, `-fvisibility=hidden` — the shim exports 24 `Java_*` symbols and nothing else.

🟡 CI runs `-Xlint:all` and the hygiene job; no formatter or static-analysis gate yet.

## Phase 2 — Platform engine acquisition and dynamic linking

| | Item |
|---|---|
| ✅ | `scripts/fetch-libchdb.sh` downloads only the pinned release asset, caching the tarball |
| ✅ | SHA-256 enforced before the bytes are used, with the expected and actual digests on failure |
| ✅ | `latest` URLs refused by the script and by a CI hygiene check |
| ✅ | Linux `RUNPATH=$ORIGIN`, macOS `@loader_path` — verified by `build-native.sh`, which fails the build otherwise |
| ✅ | No build-machine absolute path in the shim's load commands — verified the same way |
| ✅ | Debug symbols split out of the runtime JAR and archived by CI |
| ✅ | Licences, checksums and a CycloneDX SBOM generated per package |
| ⬜ | glibc and libstdc++ minimum versions checked | CI builds on `ubuntu-22.04` for this, but nothing asserts the resulting floor |
| ⬜ | macOS codesign/notarization effect on loading from an unpacked JAR |

## Phase 3 — Native loader

All ✅ except where noted.

OS/CPU/libc detection with `amd64`/`x86_64` and `arm64`/`aarch64` normalized; musl detected via
`/proc/self/maps` with an `ldd` fallback and refused by name. Load priority
`chdb.library.path` → platform JAR → `java.library.path`. No network access. Content-addressed
unpack directory keyed on the pair's SHA-256, built under a cross-process file lock with
temp-file-plus-atomic-rename, checksums verified after unpacking, POSIX permissions set.
`chdb.cache.dir`/`chdb.tmpdir` honoured, with `chdb.verifyCache` for re-verifying a completed
cache. Engine loaded before the shim. JNI ABI, engine version and required symbols checked
before any query. ClassLoader conflict caught and answered with the owner marker and two fixes;
no library copying to evade the limit.

Five failure paths have automated tests: missing package, override pointing at a non-directory,
override missing the shim, a corrupted completed cache, and a tampered checksum in the JAR.

⬜ A `noexec` /tmp is handled with a specific message but is not yet tested — it needs a mount,
so it belongs in CI rather than in a unit test.

## Phase 4 — JNI core and handle lifetime

All ✅. Opaque connection, result and stream handles carrying kind, ABI and state; every
close is idempotent; owner relations checked (a stream may only be driven by its own
connection). No C++ exception can unwind across the boundary. Engine error text, including the
ClickHouse code, is preserved and mapped to `SQLException` subclasses. Text crosses as UTF-8
`byte[]` into the `_n` entry points — no `GetStringUTFChars` — and an embedded NUL round-trips.
Batch lifetime is a single place: the Java view is invalidated before the shim frees the batch,
so a late read is an exception rather than a use-after-free. Handle counters are exposed and
asserted zero after every test. Nothing calls back into Java from a native thread.

⬜ `Cleaner` as a leak backstop, and ASan/LSan/UBSan runs (phase 10).

## Phase 5 — JVM signal-handler safety

✅ and this is the milestone's most load-bearing result. The side effect is documented,
measured (`SIGSEGV, SIGILL, SIGBUS, SIGFPE, SIGTSTP` clobbered on HotSpot 21 / macOS arm64) and
repaired by a `SignalGuard` around both call sites that re-run the reset, under a process-wide
lock. Dispositions are compared across load, connect, query and close, and a real
`NullPointerException` after connecting proves the JVM still owns SIGSEGV. The lost
ClickHouse-format crash trace is documented.

🟡 Verified on macOS arm64 only. ⬜ SIGTERM/SIGINT graceful-shutdown test; ⬜ native-crash
diagnostic-file test. ⬜ Upstream API request not yet filed.

## Phase 6 — Connection and process-level storage path

All ✅. `jdbc:chdb:<path-or-memory>` with `?key=value` properties; `:memory:` scope and
lifetime defined and **not** passed as `--path` ([findings §3](upstream-findings.md)); file
paths normalized so two spellings share one binding; process-level registry allowing many
connections per path and refusing a second path with the bound path, the requested path, the
open count and the holding URLs; binding released when the last connection closes and on a
failed connect; `Connection.close()` closes its statements and their result sets first;
unsupported transaction and advanced methods throw; `isValid()` makes a real round trip.
Concurrent use has a defined answer — see phase 7.

## Phase 7 — Statement, PreparedStatement and cancellation

All ✅. `executeQuery`, `execute`, `executeUpdate`/`executeLargeUpdate` with `rows_written` as
the update count. Query timeout mapped to an explicit engine cancel, armed for the whole result
set rather than only the open, and reported as `SQLTimeoutException`; the C ABI has no way to
interrupt a non-streaming statement, which is documented rather than faked. `Statement.cancel()`
works from another thread and deliberately does not take the connection's slot. One statement
per connection enforced across the result set's lifetime by `StatementSlot`, which waits rather
than rejecting and raises rather than deadlocking on same-thread re-entry.

The parameter lexer skips `?` inside single, double and backtick quotes, `''` and `\'` escapes,
`--`/`#!` and `/* */` comments and `$$`/`$tag$` strings, and refuses a statement that ends
inside any of them. Values go through `chdb_query_with_params_n` in escaped-text form; nothing
is interpolated. Unbound parameters, out-of-range indices, NULL and unsupported Java types are
all checked. Parameters survive execution deliberately and `clearParameters()` unbinds.

## Phase 8 — Streaming result set and type mapping

All ✅ for the V1 matrix. Forward-only batch cursor releasing on advance and on close;
`wasNull()`; 1-based column indices with precise out-of-range errors; duplicate labels resolve
to the lowest index; full `ResultSetMetaData`.

The documented matrix is covered end to end, including `UInt64` → `BigInteger` with `getLong()`
refusing above 2⁶³, `UInt32` refusing to truncate into `getInt()`, `Decimal128`/`Decimal256`
beyond double precision, pre-epoch sub-second timestamps, NaN and both infinities, and
timezone-tagged `DateTime64`. Unreadable types report `Unsupported(arrow=<format>)` in metadata
and raise a typed error naming the column and the SQL cast that reads it — never a wrong value.

⬜ Allocation profiling. The per-batch (not per-cell) JNI design and per-column direct buffers
are in place, but the three-way benchmark the plan asks for in §3.3 has not been run.

## Phases 9-14

| Phase | | Notes |
|---|---|---|
| 9 — JDBC ecosystem | 🟡 | `META-INF/services/java.sql.Driver` ✅, `DriverPropertyInfo` ✅, minimum `DatabaseMetaData` ✅, `Automatic-Module-Name` ✅, ClassLoader diagnostics ✅ and documented for Tomcat/Spark/Flink. ⬜ Spring, HikariCP and ShardingSphere smoke tests; ⬜ JPMS module-path and two-child-ClassLoader tests |
| 10 — Off-heap memory and stability | 🟡 | Handle counters asserted zero after every test ✅; bounded streaming, slow consumer, early close, cancel and 1000-query RSS plateau ✅. ⬜ ASan/LSan/UBSan; ⬜ 1-6 hour soak; ⬜ cgroup + `max_memory_usage` matrix; ⬜ `Cleaner` backstop |
| 11 — Platform and JDK matrix | 🟡 | CI defines all four platforms × JDK 11/21 plus unit tests on 11/17/21/25 and 26 as allow-failure. ⬜ Nothing but macOS arm64 has actually run; ⬜ OpenJ9; ⬜ awkward paths; ⬜ corrupted-library and arch-mismatch cases |
| 12 — ADBC experiment | ⬜ | Untouched. Does not block V1 |
| 13 — Documentation | 🟡 | README, type mapping, unsupported JDBC, native loading, signal handlers, memory, ClassLoaders and upstream findings ✅, plus a runnable example. ⬜ Per-platform dependency snippets await published coordinates; ⬜ crash-report template |
| 14 — Release preparation | ⬜ | Nothing published. The §4.4 research is open, except that the package size is now measured: 103 MB compressed for 350 MB of engine |

---

## Release gates

| Gate | |
|---|---|
| Four platforms load from a Maven artifact and run `SELECT 1` | 🟡 one platform, from a real packaged JAR |
| Java 11, 17, 21, 25 pass | 🟡 CI defined, only 21 run |
| Version and symbol mismatch fails before first use | ✅ |
| Signal handlers preserved across load/connect/query/close | ✅ on macOS arm64 |
| ASan, LSan, UBSan clean | ⬜ |
| Native handle count zero after every test | ✅ 221 tests |
| Large results stream in bounded memory | ✅ 20M rows, +17 MB RSS |
| 1000 queries and a soak show no linear RSS growth | 🟡 1000 queries ✅, soak ⬜ |
| Cancel, timeout, early close and cascading close leak-free and deadlock-free | ✅ |
| PreparedStatement does not interpolate | ✅ |
| UTF-8, NUL, quotes, comments and hostile parameters pass | ✅ |
| Unsupported JDBC throws `SQLFeatureNotSupportedException` | ✅ |
| Storage-path rule correct with clear errors | ✅ |
| Second ClassLoader fails with actionable diagnostics, no crash | 🟡 diagnostics and marker ✅, two-child test ⬜ |
| No runtime network access | ✅ |
| Maven Central size, licence, signing and SBOM requirements verified | 🟡 size measured, licences and SBOM generated, Central requirements unverified |
| README matches actual support | ✅ |

## What to do next, in order

1. **Run the CI matrix.** Everything else is guesswork until the other three platforms build
   and pass. The linkage checks in `build-native.sh` are most likely to find something on
   Linux, where `$ORIGIN` and `-z defs` behave differently from macOS.
2. **File the signal-handler API request upstream.** It is the one release gate whose
   workaround depends on upstream behaviour not changing underneath it.
3. **Sanitizers.** The batch lifetime and handle registry are designed for ASan to have nothing
   to say; that is worth confirming rather than believing.
4. **Framework smoke tests.** HikariCP in particular, because it is where the
   one-statement-per-connection rule meets real pooling.
5. **The §3.3 batch-access benchmark**, so the data-path choice is recorded as measured rather
   than as reasoned.
