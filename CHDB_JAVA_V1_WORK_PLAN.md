# chDB Java binding V1 work plan

> Status: Draft  
> Updated: 2026-09-08  
> Target repository: `chdb-io/chdb-java`

## 1. Project goal

The goal of V1 is to make chDB usable from Java 11 and later by dynamically linking it into the JVM process, and to ship the smallest driver that real Java applications and JDBC frameworks can actually adopt.

V1 in one sentence:

> On Linux x86_64/aarch64 and macOS x86_64/arm64, load a pinned version of `libchdb` through a small JNI shim and expose JDBC queries that are safe, streaming, correctly typed, cancellable and explicitly releasable, published as Maven platform packages.

V1 does not try to cover the whole JDBC specification in one pass, and it does not try to cover every advanced chDB capability. The first job is to get native lifetimes, signal handling, off-heap memory, platform packaging and result types right.

## 2. V1 scope boundaries

### 2.1 What V1 must deliver

- Java 11 as the minimum runtime version.
- Verification on Java 11, 17, 21 and 25, with Java 26 as the forward-compatibility test version.
- Support for four platforms:
  - Linux x86_64 glibc
  - Linux aarch64 glibc
  - macOS x86_64
  - macOS arm64
- A pinned version of the stable chDB C ABI. The initial V1 baseline is `chdb-core v26.7.0`; it may be raised before release, but then it must be re-pinned and the full test suite re-run.
- The small JNI shim and `libchdb` stay two separate dynamic libraries. The full chDB engine is never statically linked into the Java JNI library.
- Maven artifacts follow the "pure Java main package plus per-platform native packages" model.
- A JDBC Driver, Connection, Statement, PreparedStatement and a streaming ResultSet.
- Automatic discovery through `DriverManager` and `META-INF/services/java.sql.Driver`.
- In-process chDB sessions and multiple Connections against the same storage path.
- A clear, diagnosable error when something asks for two different storage paths at the same time.
- Results read as bounded streaming batches. Materialising the whole result into CSV, JSON or a Java byte array must not be the default.
- The common JDBC scalar types, NULL, ResultSetMetaData and enough DatabaseMetaData to work with.
- PreparedStatement built on chDB server-side typed parameters, never on SQL string interpolation.
- `Statement.cancel()`, early ResultSet close and deterministic release of native resources.
- Every C++ exception converted into a Java exception at the JNI boundary. Nothing escapes through it.
- A resolution for the conflict between the JVM signal handlers and the chDB signal handlers.
- Fail-fast behaviour on a native version mismatch, a missing symbol, an unsupported platform or a failed library load.
- Tests for native memory, handle leaks, use-after-free, concurrency and lifetimes.
- Published user documentation covering platform selection, limitations and a minimal example.

### 2.2 Experimental in V1, and not a release blocker

- A `chdb-adbc` convenience module wrapping how the Apache Arrow ADBC JNI Driver Manager loads `chdb_adbc_init`.
- A few framework compatibility examples, such as Spring JDBC, HikariCP and ShardingSphere.
- Reading complex ClickHouse types through `getObject()` or a stable text form.
- A macOS universal binary.

Anything experimental must be labelled as experimental and stays outside the V1 stable compatibility promise.

### 2.3 Explicitly out of scope for V1

- Java 8 support.
- A Windows native package.
- Linux musl/Alpine native packages.
- A single `chdb-jdbc-all` mega JAR containing every platform engine.
- Downloading `libchdb` over the network at runtime.
- GraalVM Native Image.
- Android.
- Full OSGi support.
- Loading a separate copy of the chDB native library in each of several isolated ClassLoaders.
- Full JDBC TCK compliance.
- Scrollable ResultSets, updatable ResultSets and scrollable cursors.
- JDBC transactions, savepoints and XA transactions.
- CallableStatement and stored procedures.
- JDBC batch updates.
- Arrow scan, Arrow table registration and zero-copy writes.
- Streaming insert and high-throughput bulk ingest.
- Java UDFs.
- Full support for Array, Map, Tuple, Nested, Variant, Dynamic and the rest of the complex ClickHouse types.
- Automatic cross-version compatibility with an arbitrary user-supplied `libchdb`.
- Native crash isolation. V1 is an in-process binding, so a native crash can take the JVM down with it.

## 3. V1 architecture decisions

### 3.1 Call chain

```text
Java application / Spring / JDBC framework
                |
            chdb-jdbc
   JDBC API, loader, lifetimes, type mapping
                |
        libchdb_java_jni
   JNI handles, exceptions, signal protection, batch access
                |
             libchdb
        chDB stable C ABI
```

### 3.2 Main implementation route

The production JDBC driver in V1 goes straight through JNI:

```text
JDBC -> JNI shim -> chDB C API
```

Why:

- V1 stability should not depend on the chDB ADBC implementation while it is still marked experimental.
- It avoids forcing the whole Arrow Java dependency into the base JDBC package.
- It gives direct control over signal handlers, handle lifetimes and error mapping.
- JDBC and ADBC can still share the same platform `libchdb` package later.

### 3.3 Data path

- Prefer the chDB Arrow streaming C API to get typed, bounded batches.
- The JNI layer owns the current stream, the Arrow schema, the Arrow batch and the underlying native buffer.
- The Java ResultSet holds only an opaque native handle, never a raw pointer that has already been freed.
- The current batch may be released only when the ResultSet advances to the next one or closes.
- The base Java JDBC module does not expose Arrow Java types to users.
- Before settling on a batch access mechanism, benchmark all three:
  - one JNI getter per cell;
  - one DirectByteBuffer per column;
  - conversion on the native side into a compact Java-owned batch.
- The chosen design has to give correct lifetimes, bounded memory and an acceptable JNI call overhead at the same time.

### 3.4 JDBC capability constraints

- ResultSet is always `TYPE_FORWARD_ONLY`.
- ResultSet is always `CONCUR_READ_ONLY`.
- `supportsTransactions()` returns `false`.
- `setAutoCommit(false)`, `commit()`, `rollback()` and the savepoint API throw `SQLFeatureNotSupportedException`.
- Any unimplemented JDBC method throws `SQLFeatureNotSupportedException`. It must not return a fake `null`, `0` or success.
- One Connection runs one statement at a time. Concurrent calls are either refused or serialised, and the documentation says which.

### 3.5 ClassLoader and the single native instance model

The invariant V1 enforces:

> One JVM process has exactly one native owner ClassLoader, with one JNI shim and one `libchdb` loaded. V1 does not copy the dynamic libraries so that several isolated ClassLoaders can each load their own chDB.

Supported deployment models:

```text
Plain Java application
Application/System ClassLoader
└── chdb-jdbc + chdb-native-<platform> + the one native runtime

Application server or plugin container
System/Common/Shared Parent ClassLoader
├── chdb-jdbc + chdb-native-<platform> + the one native runtime
├── Child ClassLoader A (uses chDB through parent delegation)
└── Child ClassLoader B (uses chDB through parent delegation)
```

Implementation requirements:

- Every class that declares a JNI native method, the native loader and the runtime singleton are all loaded by the same owner ClassLoader.
- In a plain single-application JVM, the owner is the application/system ClassLoader.
- Under Tomcat, Spark, Flink and plugin containers, the user puts `chdb-jdbc` and the platform native package in a shared parent ClassLoader. Child applications do not bundle a second copy of the driver.
- Native files are extracted to a content-addressed fixed path, so the same version and checksum always resolve to the same absolute path. There is no random per-ClassLoader copy.
- The first load writes a JVM-level owner marker holding string information only, recording at least the owner, the native path, the engine version and the binding version. It exists to produce a diagnosable error before a second load. The JNI/OS loader is still the real safety boundary.
- If a second isolated ClassLoader cannot reach the existing runtime through parent delegation, it fails fast and says to move the driver into a shared parent ClassLoader.
- V1 does not try to hand JNI objects, functions or handles already loaded in the first child ClassLoader over to a second child ClassLoader.

What "multiple ClassLoaders" means in V1: several child ClassLoaders can share one chDB from a parent layer. It does not mean several mutually isolated ClassLoaders can each own a chDB.

## 4. Maven artifact design

### 4.1 V1 artifacts

```text
org.chdb:chdb-jdbc:<version>
org.chdb:chdb-native-linux-x86_64-gnu:<version>
org.chdb:chdb-native-linux-aarch64-gnu:<version>
org.chdb:chdb-native-macos-x86_64:<version>
org.chdb:chdb-native-macos-aarch64:<version>
org.chdb:chdb-bom:<version>
```

- `chdb-jdbc`: the pure Java API, the JDBC implementation and the native loader. It does not contain the chDB engine.
- `chdb-native-*`: the JNI shim for that platform plus `libchdb`, the manifest, checksums, licences and an SBOM.
- `chdb-bom`: keeps the Java, JNI and engine packages on the same version. It carries no executable code.
- A platform native package may depend transitively on `chdb-jdbc`, so an application that declares one platform package gets a complete runtime.

### 4.2 Native JAR layout

```text
META-INF/chdb/native/<os>/<arch>/<libchdb>
META-INF/chdb/native/<os>/<arch>/<jni-shim>
META-INF/chdb/native/<os>/<arch>/manifest.properties
META-INF/chdb/native/<os>/<arch>/sha256sums.txt
META-INF/licenses/
META-INF/sbom/
```

`manifest.properties` records at least:

- the Java binding version;
- the expected chDB engine version;
- the JNI ABI version;
- OS, CPU and libc;
- the build commit;
- the build toolchain;
- the dynamic library checksums.

### 4.3 Versioning rules

The scheme is "full engine version plus binding revision":

```text
<engine-version>.<binding-revision>
```

- `engine-version` keeps the chDB Core release version verbatim, `rc` qualifier included.
- `binding-revision` is the trailing positive integer covering Java, JNI, loader and platform packaging revisions. It restarts at `1` for every new engine version.
- This is an engine-aligned scheme. Do not read it as Java SemVer.

Examples:

| Case | Maven version | Meaning |
|---|---|---|
| First binding against stable engine 26.7.0 | `26.7.0.1` | engine=`26.7.0`, binding revision=`1` |
| Java/JNI/loader fix only, same stable engine | `26.7.0.2` | engine unchanged, binding revision incremented |
| First binding against engine 26.7.2-rc.2 | `26.7.2-rc.2.1` | engine=`26.7.2-rc.2`, binding revision=`1` |
| Re-release on the same rc.2 after an addon change | `26.7.2-rc.2.2` | engine unchanged, binding revision incremented |
| Engine moves to rc.3 | `26.7.2-rc.3.1` | new engine, binding revision back to `1` |
| Engine reaches stable 26.7.2 | `26.7.2.1` | first binding release on that stable engine |

Release rules:

- A release artifact in a Maven repository is immutable. Never overwrite `26.7.2-rc.2.1`. Any addon, JNI, Java, POM, loader, checksum or single-platform fix ships as `.2`.
- Within one binding release, `chdb-jdbc`, the four platform packages and `chdb-bom` carry exactly the same version. They ship together even when some platform content did not change, so the BOM and the platform packages never end up on mixed versions.
- Moving the engine from one RC to another, or from an RC to a stable release, counts as a new engine version, and the binding revision restarts at `1`.
- A Java artifact built on an engine RC is itself a preview and cannot be the engine dependency of V1 GA. V1 GA has to bind a stable chDB Core release.
- Development builds may use `26.7.2-rc.2.2-SNAPSHOT`, but `SNAPSHOT` never enters a Maven Central release.
- If the Java binding on a stable engine needs its own release candidates, use `26.7.0.1-rc.1`, `26.7.0.1-rc.2`, with `26.7.0.1` as the final GA. A candidate and the GA never reuse the same immutable artifact.
- Any engine change produces a new Maven version and a full platform test run.

To remove string-parsing ambiguity, every artifact manifest records these separately:

```properties
engine.version=26.7.2-rc.2
binding.revision=2
binding.version=26.7.2-rc.2.2
java.api.version=1
jni.abi.version=1
```

Runtime compatibility checks read `engine.version`, `java.api.version`, `jni.abi.version` and the symbol set directly. They never guess by splitting the Maven version string.

### 4.4 Pre-release research

- [ ] Confirm the current Maven Central limit for a single 100 to 180 MB artifact.
- [ ] Confirm the redistribution licence requirements for chDB and every bundled third-party library.
- [ ] Decide whether a chDB-owned Maven repository is needed as a fallback.
- [ ] Confirm the Maven Central requirements for signing, SBOM, sources and javadoc.
- [ ] Measure the real compressed JAR/ZIP size and the installed disk footprint.

## 5. Work breakdown

### 5.1 Phase 0: freeze the V1 decisions and the upstream dependency

- [ ] Submit this plan to `chdb-io/chdb-java` and have the chDB Core, Java and release owners review it.
- [ ] Confirm Java 11 as the V1 minimum.
- [ ] Confirm that V1 supports only the four existing glibc/macOS platforms.
- [ ] Confirm that direct JNI is the production JDBC line and ADBC is the experimental branch.
- [ ] Pin the chDB Core release, the headers, the library checksums and the required symbol set.
- [ ] Add a check that `chdb_version()`, the release tag and the header version agree.
- [ ] List the C API that V1 uses, separating required from optional symbols.
- [ ] Agree the C ABI versioning and compatibility promise with the chDB Core maintainers.
- [ ] File or confirm an upstream plan for the Java signal handler requirement.

Exit condition: every decision touching the public API, the Java minimum, the platform set and the native ABI is confirmed in writing.

### 5.2 Phase 1: rebuild the repository and the build skeleton

- [ ] Keep the existing history, licences and the package names that matter, and remove or quarantine the unsafe old JNI implementation.
- [ ] Set up the Maven multi-module root project.
- [ ] Set up the `chdb-jdbc` module.
- [ ] Set up the JNI CMake build directory.
- [ ] Set up the build definitions for the four platform native artifacts.
- [ ] Set up the `chdb-bom` module.
- [ ] Set up the integration test and example modules.
- [ ] Generate the JNI headers with `javac -h`.
- [ ] Use CMake `find_package(JNI)` and stop committing the system `jni.h`/`jni_md.h`.
- [ ] Turn on C++17, strict compiler warnings and symbol visibility control.
- [ ] Add Java formatting, static analysis and native formatting to CI.

Exit condition: an empty implementation builds the Java JAR, the JNI shim and the native JAR on all four platforms.

### 5.3 Phase 2: platform engine acquisition and dynamic linking

- [ ] Write a build-time download script that fetches only a pinned official chDB Core release asset.
- [ ] Verify the checksum after download, with no way to skip it.
- [ ] Forbid `latest` URLs in CI.
- [ ] Have the Linux JNI shim link dynamically against the `libchdb.so` next to it.
- [ ] Set `RUNPATH=$ORIGIN` on Linux.
- [ ] Check that no build-machine absolute path appears in the Linux dynamic dependencies.
- [ ] Check the minimum glibc and libstdc++ versions.
- [ ] Reference the neighbouring `libchdb` through `@loader_path` on macOS.
- [ ] Check that no build-machine absolute path appears in the macOS dynamic dependencies.
- [ ] Determine whether macOS codesign/notarization affects loading a library extracted from a JAR.
- [ ] Strip debug symbols from the runtime JAR and keep them as a separate CI artifact.
- [ ] Generate licences, checksums and the SBOM.

Exit condition: on all four platforms, both libraries can be extracted from the native JAR and pass `System.load`.

### 5.4 Phase 3: the native loader

- [ ] Detect OS, CPU and libc. V1 reports musl as unsupported outright.
- [ ] Normalise names such as `amd64/x86_64` and `aarch64/arm64`.
- [ ] Define the load order:
  1. `-Dchdb.library.path=<path>`;
  2. the platform native JAR;
  3. `java.library.path`.
- [ ] Let a user point at an external directory holding both the JNI shim and `libchdb`.
- [ ] Allow no network download at runtime.
- [ ] Extract into a content-addressed directory keyed by checksum.
- [ ] Write to a temporary file and atomically rename, so a half-written library is never loadable.
- [ ] Use a cross-process file lock so two JVMs extracting at once cannot corrupt the files.
- [ ] Verify the checksum after extraction.
- [ ] Set safe file permissions and the executable bit.
- [ ] Support `-Dchdb.tmpdir` or `-Dchdb.cache.dir`.
- [ ] Give a clear error and a fix for a read-only directory or a `noexec /tmp`.
- [ ] Load `libchdb` first, then the JNI shim.
- [ ] Check the JNI ABI, the engine version and the required symbols at runtime.
- [ ] Catch the "already loaded in another ClassLoader" error and explain how to move to a shared parent ClassLoader.
- [ ] Never work around the ClassLoader restriction by copying the native library under a random name.
- [ ] Keep a JVM-level native owner marker and detect owner, path and version conflicts before a second library is loaded.
- [ ] Resolve the same engine/checksum to the same content-addressed extraction path in every ClassLoader.
- [ ] Make sure the native-facing classes, the loader and the runtime singleton are defined by one owner ClassLoader.

Exit condition: automated tests cover the normal load, an external path override, a checksum failure, an unsupported platform, `noexec` and a ClassLoader conflict.

### 5.5 Phase 4: the JNI core and handle lifetimes

- [ ] Define opaque handle types for connection, query stream, batch and result.
- [ ] Give each handle a magic value, an ABI version, a state and an owner relationship, and reject an invalid or already closed handle.
- [ ] Make every close/destroy operation idempotent.
- [ ] Convert every C++ exception into a Java exception at the JNI layer.
- [ ] Keep the chDB error code, the message and the query context a native error needs.
- [ ] Convert Java strings to standard UTF-8 bytes and call the length-taking `_n` APIs.
- [ ] Do not use `GetStringUTFChars` for parameters that may hold non-ASCII or NUL bytes.
- [ ] Implement connection creation and close.
- [ ] Implement query/stream open, fetch, cancel and destroy.
- [ ] Implement the batch lifecycle so a DirectByteBuffer is never exposed after its memory is freed.
- [ ] Add an open-handle counter to debug and test builds.
- [ ] Add a `Cleaner` as leak insurance, while every normal path still requires an explicit close.
- [ ] If a Java callback ever runs from a native thread, attach and detach the thread on the JVM properly. V1 avoids calling back into Java from a chDB worker thread where it can.

Exit condition: the JNI unit tests cover the normal path, exceptions, double close, early close, cancel and bad handles, and ASan reports no use-after-free.

### 5.6 Phase 5: JVM signal handler safety

- [ ] Document that `chdb_set_signal_handlers_enabled(0)` currently resets some already installed signal handlers to `SIG_DFL`.
- [ ] Push chDB Core for an API that only blocks future installation and leaves the host handlers alone.
- [ ] Until that API exists, protect the JNI shim locally:
  - save the current `sigaction` for every signal chDB touches;
  - call `chdb_set_signal_handlers_enabled(0)`;
  - restore the original JVM `sigaction`;
  - apply the same protection to the reset path on the first connect;
  - serialise all of it behind a process-level lock.
- [ ] Compare the SIGSEGV, SIGBUS, SIGILL, SIGFPE and SIGABRT handlers before and after loading.
- [ ] Compare the handlers again before and after connect, query and close.
- [ ] Run the signal regression tests on Linux and macOS, x86_64 and arm64.
- [ ] Test that chDB does not take over Java's own SIGTERM/SIGINT graceful shutdown.
- [ ] Test that the JVM and the OS still write the expected diagnostic files on a native crash.
- [ ] Document that disabling the chDB handlers costs the ClickHouse-format native crash stack trace.

Exit condition: the JVM signal handlers are unchanged before and after chDB is loaded and connected. If that cannot be met, V1 does not ship.

### 5.7 Phase 6: Connection and the process-level storage path

- [ ] Define and implement the JDBC URL `jdbc:chdb:<path-or-memory>`.
- [ ] Define the sharing scope and lifetime of `:memory:`.
- [ ] Normalise a file storage path to an absolute real path.
- [ ] Keep a JVM process-level storage path registry.
- [ ] Allow several independent chDB Connections on the same path.
- [ ] Refuse a different path while connections are still open, and return an error naming the current path, the requested path and the way out.
- [ ] Allow a new path to be bound once the last Connection closes.
- [ ] Handle Statements and ResultSets still open when a Connection closes.
- [ ] Have the unsupported transaction and advanced methods on Connection throw explicitly.
- [ ] Implement `isValid()`, `isClosed()`, client info and the read-only semantics that are needed.
- [ ] Define what concurrent use of one Connection does.

Exit condition: integration tests cover several connections on one path, the conflicting-path case, releasing the last connection and memory mode.

### 5.8 Phase 7: Statement, PreparedStatement and cancellation

- [ ] Implement `executeQuery()`.
- [ ] Implement `execute()`.
- [ ] Implement `executeUpdate()` and the update count that DDL/DML needs. Where the count cannot be computed reliably, document it and return a value JDBC permits.
- [ ] Implement query timeout so that it issues an explicit cancel instead of only stopping the wait on the Java side.
- [ ] Implement `Statement.cancel()`.
- [ ] Define the locks and the state machine for cancel, fetch and close.
- [ ] Either forbid two statements running on one Connection at once, or serialise them under a stated rule.
- [ ] Write the SQL parameter lexer for PreparedStatement.
- [ ] Have the lexer skip a `?` inside a string, a quoted identifier, escaped content or a SQL comment.
- [ ] Translate the JDBC `?` into a chDB typed parameter.
- [ ] Support the common `setBoolean/setInt/setLong/setDouble/setBigDecimal/setString/setBytes/setDate/setTimestamp/setNull/setObject`.
- [ ] Use `chdb_query_with_params_n` and never concatenate parameters into the SQL.
- [ ] Check unbound parameters, repeated execution, NULL types and unsupported types.
- [ ] Scope PreparedStatement parameters to the current execution and clear the state afterwards.

Exit condition: the parameter injection tests, the special character / NUL / Unicode tests, and the cancel race and timeout tests all pass.

### 5.9 Phase 8: streaming ResultSet and type mapping

- [ ] Implement the forward-only batch cursor.
- [ ] Implement early ResultSet close that releases the stream and the batch immediately.
- [ ] Implement `wasNull()`.
- [ ] Implement column lookup by index and by name, honouring the JDBC 1-based column index strictly.
- [ ] Implement ResultSetMetaData.
- [ ] Give these types a stable mapping in V1:
  - Bool -> `boolean/Boolean`
  - Int8/16/32/64 -> the matching Java integer type
  - UInt8/16/32 -> a Java integer type that can hold them
  - UInt64 -> `BigInteger`, with `getLong()` raising an error when the value does not fit
  - Float32/64 -> `float/double`
  - Decimal -> `BigDecimal`
  - String/FixedString -> `String`
  - Binary -> `byte[]`
  - Date/Date32 -> `LocalDate` and `java.sql.Date`
  - DateTime/DateTime64 -> `Instant`/`Timestamp` under a stated time zone
  - UUID -> `UUID`/String
  - Nullable -> JDBC NULL and `wasNull()`
- [ ] Add tests for time zones, Decimal precision, NaN/Infinity and unsigned overflow.
- [ ] Make complex types either safely readable or an explicit unsupported error. They must never shift the data or crash the JVM.
- [ ] Keep any text fallback consistent between the documentation and the metadata.
- [ ] Avoid allocating a pile of temporary Java objects per row, and profile the allocations.

Exit condition: the type matrix round-trips, and the NULL, column index boundary, large batch and early close tests pass.

### 5.10 Phase 9: JDBC ecosystem compatibility

- [ ] Add `META-INF/services/java.sql.Driver`.
- [ ] Implement JDBC URL property parsing and `DriverPropertyInfo`.
- [ ] Implement the minimum DatabaseMetaData that frameworks use.
- [ ] Determine how the service file merges under shading and fat JARs, and document it.
- [ ] Add a Spring `JdbcTemplate` smoke test.
- [ ] Add a HikariCP smoke test, and state the pool size and the Connection concurrency limit.
- [ ] Add a ShardingSphere URL/dialect smoke test.
- [ ] Test the plain classpath, the JPMS module path, and the native access warnings for named and unnamed modules.
- [ ] Add `Automatic-Module-Name`.
- [ ] Test the failure diagnostics for two child ClassLoaders in one JVM.
- [ ] Test that two child ClassLoaders sharing a common parent do share one chDB runtime.
- [ ] Test that two fully isolated ClassLoaders fail fast before the second load, with no second native copy on disk or in the process.
- [ ] Write the deployment notes for a shared parent ClassLoader under Tomcat, Spark and Flink.

Exit condition: the three common frameworks can connect and run basic queries, and a ClassLoader error tells the user what to do about it.

### 5.11 Phase 10: off-heap memory and stability testing

- [ ] Expose the connection, result, stream and batch handle counts in debug builds.
- [ ] Assert that the handle counts return to zero at the end of every unit and integration test.
- [ ] Run ASan, LSan and UBSan over the JNI shim and a full chDB sanitizer build.
- [ ] Build an RSS/PSS sampler that runs in its own JVM process.
- [ ] Run at least 1,000 queries after warm-up and check that RSS/PSS does not grow linearly.
- [ ] Verify streaming with a result logically much larger than memory, and check that peak memory does not scale with the full result size.
- [ ] Test a slow consumer and backpressure.
- [ ] Test reading one row and closing.
- [ ] Test that native memory returns to a stable plateau after a long query is cancelled.
- [ ] Test the SQL error, type error and JNI exception paths for leaks.
- [ ] Test the cleanup when a Connection closes with ResultSets still open.
- [ ] Test the Cleaner leak insurance without treating GC timing as the normal release mechanism.
- [ ] Combine `-Xmx`, a cgroup memory limit and the chDB `max_memory_usage` inside a container.
- [ ] Have a high-memory query return a Java exception instead of getting the process OOM killed.
- [ ] Run a concurrent soak test between one and six hours.
- [ ] Separate allocator caching from a real leak, judging by a stable plateau and the growth slope rather than by a return to the initial RSS.
- [ ] Document that `jcmd VM.native_memory` covers the JVM itself and cannot account for all `libchdb` memory.

Exit condition: handle counts reach zero, the sanitizers are clean, RSS/PSS settles on a plateau, and a memory limit error is recoverable.

### 5.12 Phase 11: the platform and JDK test matrix

- [ ] Linux x86_64 glibc with Java 11/17/21/25/26.
- [ ] Linux aarch64 glibc with Java 11/17/21/25/26.
- [ ] macOS x86_64 with Java 11/17/21/25/26.
- [ ] macOS arm64 with Java 11/17/21/25/26.
- [ ] Temurin/HotSpot as the primary JVM.
- [ ] At least one OpenJ9/Semeru smoke test.
- [ ] Test paths with spaces, Unicode and very long file names.
- [ ] Test a read-only directory, a temporary directory without execute permission, and a full disk.
- [ ] Test a corrupted library, a checksum mismatch, an architecture mismatch and a missing dependency.
- [ ] Test loading from an IDE, Maven Surefire, Gradle Test and a plain `java -jar`.
- [ ] Run the new-JDK CI in strict native-access mode to catch the future deny-by-default behaviour early.

Exit condition: every supported platform and officially supported JDK passes. Java 26 failures are classified, and the decision to block or record them is taken before release.

### 5.13 Phase 12: the experimental ADBC branch

- [ ] Load the platform `libchdb` through the Apache Arrow `adbc-driver-jni`.
- [ ] Set the entrypoint to `chdb_adbc_init` explicitly.
- [ ] Verify query, Arrow batch, metadata, parameter bind, cancel and close.
- [ ] Verify that the Java ADBC BufferAllocator ends at zero.
- [ ] Handle the JVM signal handler problem on the ADBC path.
- [ ] Decide whether ADBC can reuse or replace the JDBC data path in a later version.
- [ ] If `chdb-adbc` ships, label it experimental and keep it apart from the JDBC GA stability promise.

Exit condition: a written conclusion on which ADBC capabilities work, which are missing, and whether it is a suitable base for V2. This phase does not block JDBC V1.

### 5.14 Phase 13: documentation and examples

- [ ] README: what the project is, the support matrix and Maven installation.
- [ ] A dependency example per platform.
- [ ] An example of overriding the `libchdb` path externally.
- [ ] The JDBC URL and the session/path semantics.
- [ ] Statement, PreparedStatement and ResultSet examples.
- [ ] The list of unsupported JDBC capabilities.
- [ ] The type mapping table.
- [ ] Signal handler behaviour and the diagnostic differences.
- [ ] A note on off-heap memory and on `-Xmx` not covering chDB memory.
- [ ] A plain-language explanation of the ClassLoader rule and the Tomcat/Spark/Flink deployment shapes.
- [ ] The native-access JVM flags.
- [ ] The way out of an unsupported-platform error.
- [ ] Security and crash report templates asking for the JVM, OS, arch, libc, engine and binding versions plus the path actually loaded.

Exit condition: a Java user who knows nothing about the implementation can install, connect, run a parameterised query and release the resources correctly from the README alone.

### 5.15 Phase 14: release preparation

- [ ] Publish a Maven snapshot.
- [ ] Have at least two external Java projects try the snapshot.
- [ ] Verify the POM transitive dependencies and the BOM.
- [ ] Verify sources, javadoc, signatures, licences and the SBOM.
- [ ] Verify that a Maven Central download checksums identically to the CI artifact.
- [ ] Verify the real download and first load on all four platforms.
- [ ] Freeze the public Java API and the JNI ABI.
- [ ] Write the release notes and the known limitations.
- [ ] Publish an RC and hold a soak and external validation window of at least one week.
- [ ] Once every V1 release gate passes, publish the first release aligned to a stable engine, for example `26.7.0.1`.

## 6. Milestones

| Milestone | Demonstrable result | Exit condition |
|---|---|---|
| M0: scope frozen | Reviewed V1 API, platform and ABI decisions | Phase 0 complete |
| M1: native smoke | `SELECT 1` runs on all four platforms | Core items of phases 1 to 5 complete |
| M2: JDBC alpha | Driver, Connection, Statement and a streaming ResultSet work | Basic types, close and error paths pass |
| M3: JDBC beta | PreparedStatement, cancel, metadata and framework smoke tests work | Phases 6 to 9 complete |
| M4: release candidate | Maven platform packages, full documentation and stability data | RC conditions of phases 10 to 14 complete |
| M5: V1 GA | A publicly supportable four-platform JDBC binding | Every release gate passes |

## 7. V1 release gate

V1 does not ship if any one of these is unmet:

- [ ] All four supported platforms complete a first load and `SELECT 1` from the Maven artifacts.
- [ ] Java 11, 17, 21 and 25 all pass the required tests.
- [ ] A chDB/JNI version or symbol mismatch fails before first use.
- [ ] The signal handlers keep the host JVM state across load, connect, query and close.
- [ ] ASan, LSan and UBSan report no unhandled errors.
- [ ] The native handle count is zero after every normal and error-path test.
- [ ] Streaming a large result uses bounded memory that does not scale with the full result size.
- [ ] RSS/PSS does not grow linearly after 1,000 queries and a long soak.
- [ ] Cancel, timeout, early close and cascading Connection close leak nothing and deadlock nowhere.
- [ ] PreparedStatement does not bind parameters by SQL concatenation.
- [ ] The UTF-8, NUL, quoting, comment and malicious parameter tests pass.
- [ ] Every unsupported JDBC method throws `SQLFeatureNotSupportedException` explicitly.
- [ ] The process-level storage path restriction behaves correctly and its error message is clear.
- [ ] A failed second ClassLoader load produces actionable diagnostics without crashing the JVM.
- [ ] When two child ClassLoaders share a common parent, the process has one native owner, one JNI shim and one `libchdb` mapping.
- [ ] The two-isolated-ClassLoader test does not get a native library loaded through a random file name or a second extraction directory.
- [ ] The Maven artifacts touch no network at runtime.
- [ ] The Maven Central large file, licence, signature and SBOM requirements are verified.
- [ ] The README matches the real support scope and claims nothing untested.

## 8. Main risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| The chDB C ABI is still moving fast | Java/native version mismatch or a crash | Pin the engine and JNI ABI, probe symbols, fail fast |
| chDB changes the process signal handlers | Random JVM crashes or lost diagnostics | New upstream API; save/restore in JNI; regression tests on four platforms |
| The native packages are large | Maven Central limits, download and cache cost | One package per platform; no default all-in-one; verify the repository limit first |
| A DirectByteBuffer lifetime bug | Use-after-free and a JVM crash | Native owner handles; a batch lifecycle state machine; ASan |
| chDB off-heap memory is outside `-Xmx` | OOM kills and unpredictable memory | Streaming, handle counts, RSS/PSS, cgroup and query memory limits |
| Multiple ClassLoaders | `UnsatisfiedLinkError`, ClassLoader leaks or several huge engine copies | One native owner per JVM; shared parent ClassLoader; owner marker; fail fast on isolated loads; no random native copies |
| One storage path per process | Surprising connection pool or multi-tenant behaviour | A JVM-level path registry; a clear error and documentation |
| ClickHouse has far more types than JDBC | Data loss or a wrong mapping | A stated V1 type matrix; a safe fallback or unsupported error for complex types |
| A native crash kills the JVM | The host service goes down with it | Document the boundary; revisit a subprocess isolation mode in V2 |
| ADBC is still experimental | Compatibility and dependency risk | Keep it off the JDBC V1 release path |

## 9. Candidate work after V1

These land in their own planning round after V1 and are not automatically promised for V1.1:

- A Java 8 compatibility layer.
- Windows and Linux musl platform packages.
- Stabilising `chdb-adbc`.
- A direct Arrow Java API.
- Streaming insert and bulk ingest.
- Arrow scan and registering Java in-memory tables.
- The full complex type mapping.
- JDBC batch updates.
- GraalVM Native Image.
- Gradle variant selection of the platform package.
- An optional all-platform aggregate package.
- A subprocess isolation mode.
- A fuller JDBC compliance suite.

## 10. Research references

- chDB Java driver request: [chdb-io/chdb#243](https://github.com/chdb-io/chdb/issues/243)
- The existing official prototype: [chdb-io/chdb-java](https://github.com/chdb-io/chdb-java)
- A community FFM prototype: [linux-china/chdb-java-ffm](https://github.com/linux-china/chdb-java-ffm)
- chDB C API: [programs/local/chdb.h](https://github.com/chdb-io/chdb-core/blob/main/programs/local/chdb.h)
- chDB streaming API: [streaming.rst](https://github.com/chdb-io/chdb-core/blob/main/docs/streaming.rst)
- chDB ADBC: [adbc.rst](https://github.com/chdb-io/chdb-core/blob/main/docs/adbc.rst)
- chDB signal handler control: [chdb-core PR #11](https://github.com/chdb-io/chdb-core/pull/11)
- A real signal handler failure in Go: [chdb-go Issue #30](https://github.com/chdb-io/chdb-go/issues/30)
- chDB Node platform package design: [chdb-node package.json](https://github.com/chdb-io/chdb-node/blob/main/package.json)
- Apache Arrow ADBC Java JNI: [jni.rst](https://github.com/apache/arrow-adbc/blob/main/docs/source/java/jni.rst)
- DuckDB Java: [duckdb/duckdb-java](https://github.com/duckdb/duckdb-java)
- SQLite JDBC: [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)
- The JNI ClassLoader restriction: [JNI Invocation API](https://docs.oracle.com/en/java/javase/26/docs/specs/jni/invocation.html)
- JVM signal handling: [Oracle Handle Signals and Exceptions](https://docs.oracle.com/en/java/javase/17/troubleshoot/handle-signals-and-exceptions.html)
- Native Memory Tracking limits: [Oracle NMT](https://docs.oracle.com/en/java/javase/13/vm/native-memory-tracking.html)
