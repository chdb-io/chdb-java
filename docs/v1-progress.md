# V1 progress

Status of every phase in [`CHDB_JAVA_V1_WORK_PLAN.md`](../CHDB_JAVA_V1_WORK_PLAN.md), as of the
JDBC-Alpha rebuild. Kept separate from the plan so the plan stays the specification and this
stays the report.

Legend: ✅ done and tested · 🟡 partly done · ⬜ not started · ➖ out of this milestone

Verified in CI against **engine 26.7.0** on all four platforms — Linux x86_64 and aarch64,
macOS arm64 and x86_64 — each running the full suite on **Java 11, 17, 21 and 25**, plus
sanitizer runs on one Linux and one macOS toolchain. Sixteen platform-and-JDK combinations, all
green.

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
| ✅ | glibc and libstdc++ minimum versions checked | `build-native.sh` derives both from the binaries, records them in `manifest.properties`, and fails the build above a ceiling in `engine.properties`. **libstdc++: eliminated** — statically linked, as the engine does. **glibc: 2.25**, achieved by building the Linux shim in a manylinux_2_28 container, and proven by running the whole suite on AlmaLinux 8 |
| ⬜ | macOS codesign/notarization effect on loading from an unpacked JAR |

### Closed: the Linux glibc floor is 2.25

It was **2.34**, an artefact of building on `ubuntu-22.04`: glibc 2.34 merged libpthread, libdl
and librt into libc, so anything linked there references symbols versioned at 2.34. Nothing
about the shim needed it. The Linux shim now builds in a `manylinux_2_28` container and needs
**glibc 2.25**, below the 2.28 of the oldest platform still supported.

Three things hold it there rather than one:

- `scripts/build-native-in-container.sh` compiles the Linux shim in manylinux_2_28.
- `build-native.sh` fails the build if the measured floor exceeds `max.glibc.<platform>` in
  `engine.properties`, so forgetting the container is an error rather than a quietly narrower
  package.
- A CI job runs the entire integration suite on **AlmaLinux 8** — glibc 2.28, the base RHEL 8
  uses — against the artefacts the release job would publish. Symbol versions are necessary,
  not sufficient; this is the part that demonstrates it works.

Building for the older glibc immediately found a real portability bug: `dlsym` is used by
`chdb_optional_api.cpp` and nothing linked `libdl`. glibc 2.34 merged libdl into libc, so the
build on Ubuntu 22.04 linked by accident; on 2.28 it fails with an undefined reference. That is
the whole argument for this job existing.

**The ceiling is 2.28, not chdb-core's 2.17.** Checked against what is still alive in September
2026, only one excluded platform family was:

| glibc | Platforms | Status |
|---|---|---|
| 2.17 | RHEL 7, CentOS 7 | end of life, June 2024 |
| 2.26 | Amazon Linux 2 | end of life, June 2026 |
| 2.28 | **RHEL 8, Rocky 8, Alma 8, Oracle Linux 8** | **maintenance support to May 2029** |
| 2.31 | Ubuntu 20.04, Debian 11 | end of life, April 2025 and 31 August 2026 |
| 2.34 | RHEL 9, Ubuntu 22.04, Amazon Linux 2023 | current, and where the floor sits today |

Going to 2.28 buys the RHEL 8 family, which has three years of maintenance support left and
belongs to the distribution family with the largest enterprise Linux share. Going further, to
the manylinux2014 baseline chdb-core targets, buys only RHEL 7 — which is dead. Matching the
engine exactly would be effort spent on nobody.

For calibration, measured from the published artifacts of comparable Java projects that ship
native code — all of them below us:

| project | glibc floor | libstdc++ |
|---|---|---|
| sqlite-jdbc 3.49.1.0 | 2.3 | statically linked |
| snappy-java 1.1.10.7 | 2.3.2 | statically linked |
| zstd-jni 1.5.7-3 | 2.8 | statically linked |
| rocksdbjni 10.2.1 | 2.12 | dynamic |
| duckdb_jdbc 1.3.1.0 | 2.25 | dynamic |
| **chdb-java** | **2.25** | statically linked |

duckdb_jdbc is the closest analogue — an embedded analytical engine behind JNI — and the
highest of them, at 2.25.

duckdb_jdbc is the closest analogue — an embedded analytical engine behind JNI — and at 2.25
we now match it exactly.

manylinux2014 is not usable directly, and would buy nothing anyway: it is CentOS 7-based and
its glibc is too old for the Node runtime GitHub Actions needs inside a container. The
container is therefore run as a step rather than as a job `container:`, which keeps Actions on
the host and puts only the compiler in the container.

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

The handle registry and the Arrow layout parser are additionally covered by `chdb_jni_test`,
a JVM-free harness run under ASan and UBSan — 197 checks, which found five parser defects on
its first run. ⬜ `Cleaner` as a leak backstop.

## Phase 5 — JVM signal-handler safety

✅ and this is the milestone's most load-bearing result. The side effect is documented,
measured (`SIGSEGV, SIGILL, SIGBUS, SIGFPE, SIGTSTP` clobbered on HotSpot 21 / macOS arm64) and
repaired by a `SignalGuard` around both call sites that re-run the reset, under a process-wide
lock. Dispositions are compared across load, connect, query and close, and a real
`NullPointerException` after connecting proves the JVM still owns SIGSEGV. The lost
ClickHouse-format crash trace is documented.

Verified on all four platforms. CI also caught a bug local testing could not: the guard
treated glibc's `SA_RESTORER` as a changed disposition, so on Linux it reported four signals as
clobbered forever ([findings §8](upstream-findings.md)). ⬜ SIGTERM/SIGINT graceful-shutdown
test; ⬜ native-crash diagnostic-file test. ⬜ Upstream API request not yet filed.

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
| 9 — JDBC ecosystem | 🟡 | `META-INF/services/java.sql.Driver` ✅, `DriverPropertyInfo` ✅, minimum `DatabaseMetaData` ✅, `Automatic-Module-Name` ✅, ClassLoader diagnostics ✅ and documented for Tomcat/Spark/Flink. HikariCP, MyBatis and jOOQ smoke tests ✅, and every `DatabaseMetaData` method swept reflectively with nothing throwing ✅ (179 on JDK 11 through 26). ⬜ Spring `JdbcTemplate`; 🚫 ShardingSphere, which cannot parse a `jdbc:chdb:` URL at all; ⬜ JPMS module-path and two-child-ClassLoader tests |
| 10 — Off-heap memory and stability | 🟡 | Handle counters asserted zero after every test ✅; bounded streaming, slow consumer, early close, cancel and 1000-query RSS plateau ✅. UBSan over the whole JDBC suite ✅ and ASan+UBSan over the shim's own logic ✅, on both a Linux and a macOS toolchain — but **ASan cannot run against the released engine at all** ([findings §8](upstream-findings.md)), so full-process ASan and LSan need an upstream sanitizer build. ⬜ 1-6 hour soak; ⬜ cgroup + `max_memory_usage` matrix; ⬜ `Cleaner` backstop |
| 11 — Platform and JDK matrix | 🟡 | ✅ All four platforms × Java 11/17/21/25 run the full suite in CI, plus Java 26 as allow-failure and a packaged-JAR load on each. ⬜ OpenJ9; ⬜ awkward paths; ⬜ corrupted-library and arch-mismatch cases |
| 12 — ADBC experiment | ⬜ | Untouched. Does not block V1 |
| 13 — Documentation | 🟡 | README, type mapping, unsupported JDBC, native loading, signal handlers, memory, ClassLoaders and upstream findings ✅, plus a runnable example. ⬜ Per-platform dependency snippets await published coordinates; ⬜ crash-report template |
| 14 — Release preparation | ⬜ | Nothing published. The §4.4 research is open, except that the package size is now measured: 103 MB compressed for 350 MB of engine |

---

## Release gates

| Gate | |
|---|---|
| Four platforms load from a Maven artifact and run `SELECT 1` | ✅ all four, from a real packaged JAR, in CI |
| Java 11, 17, 21, 25 pass | ✅ all four, on all four platforms |
| Version and symbol mismatch fails before first use | ✅ |
| Signal handlers preserved across load/connect/query/close | ✅ on macOS arm64 |
| ASan, LSan, UBSan clean | 🟡 UBSan clean over the whole suite and ASan clean over the shim harness, on Linux and macOS; full-process ASan and LSan blocked on an upstream sanitizer build of chdb-core |
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

1. **File the two upstream issues.** The signal-handler API (§1) is the one release gate whose
   workaround depends on upstream behaviour not changing underneath it. A sanitizer build of
   chdb-core (§8) is what unblocks the other half of the sanitizer gate.
2. **Framework smoke tests.** HikariCP ✅, because it is where the one-statement-per-connection
   rule meets real pooling; MyBatis ✅ and jOOQ ✅, because they are what read `DatabaseMetaData`
   heavily; and every method of that interface swept reflectively ✅ — 179 of them on JDK 11
   through 26 — standing in for the JDBC
   GUI nobody has run. What is left is Spring `JdbcTemplate`. ShardingSphere is not doable until
   upstream stops parsing every JDBC URL as client/server — it is also where issue #2's reporter
   came from.
3. **The soak test**, the remaining phase-10 item that a CI run cannot stand in for.
4. **The §3.3 batch-access benchmark**, so the data-path choice is recorded as measured rather
   than as reasoned.
5. **Phase 14 release preparation**, which is now the largest untouched block: nothing is
   published, and the Maven Central size, signing and SBOM requirements are unverified. The
   package sizes are at least measured: 112 MB (macOS arm64), 128 MB (macOS x86_64), 130 MB
   (Linux aarch64), 167 MB (Linux x86_64).
