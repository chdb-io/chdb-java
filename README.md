# chdb-java

A JDBC driver for [chDB](https://github.com/chdb-io/chdb-core), the embedded build of
ClickHouse. It runs the engine in your JVM's process — no server, no network — and gives you
streaming, forward-only result sets over ClickHouse SQL.

> **Status: pre-release.** V1 is under construction against the work plan in
> [`CHDB_JAVA_V1_WORK_PLAN.md`](CHDB_JAVA_V1_WORK_PLAN.md). Nothing is published to Maven
> Central yet, and the public API is not frozen. See [What works today](#what-works-today).

```java
try (Connection connection = DriverManager.getConnection("jdbc:chdb::memory:");
        PreparedStatement statement = connection.prepareStatement(
                "SELECT count() FROM url(?, 'JSONEachRow') WHERE status = 200")) {
    statement.setString(1, "https://example.com/logs.jsonl");
    try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        System.out.println(rs.getLong(1));
    }
}
```

## Support matrix

Every one of the four platforms builds, passes the full test suite on Java 11, 17, 21 and 25,
and is loaded from a real packaged JAR, in CI:

| | Linux glibc | macOS |
|---|---|---|
| x86_64 | ✅ `chdb-native-linux-x86_64-gnu` | ✅ `chdb-native-macos-x86_64` |
| aarch64 / arm64 | ✅ `chdb-native-linux-aarch64-gnu` | ✅ `chdb-native-macos-aarch64` |

- **Java 11 or later.** 11, 17, 21 and 25 each run the full suite on all four platforms. Java 26
  is tested for forward compatibility only.
- **macOS 11 or later** on arm64, **10.15 or later** on x86_64 — matching what the engine
  itself supports, and enforced at build time.
- **Engine:** chDB Core **26.7.0**, pinned. The C ABI is version-locked, so the driver refuses
  to run against a different engine build rather than risking a struct-layout mismatch.
- **Not supported in V1:** Windows, musl (Alpine), 32-bit, GraalVM Native Image, Android. See
  [work plan §2.3](CHDB_JAVA_V1_WORK_PLAN.md).

[docs/v1-progress.md](docs/v1-progress.md) has the phase-by-phase status and what is left.

## Installing

Two artifacts: the driver, which is pure Java, and one native package for the platform you run
on. The native package pulls in the driver, so declaring it alone is enough.

```xml
<dependency>
  <groupId>org.chdb</groupId>
  <artifactId>chdb-native-linux-x86_64-gnu</artifactId>
  <version>26.7.0.1</version>
</dependency>
```

Building for several platforms — a CI matrix, or a distribution your users install on either
architecture — declare the driver plus each native package you need:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.chdb</groupId>
      <artifactId>chdb-bom</artifactId>
      <version>26.7.0.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>org.chdb</groupId>
    <artifactId>chdb-jdbc</artifactId>
  </dependency>
  <dependency>
    <groupId>org.chdb</groupId>
    <artifactId>chdb-native-linux-x86_64-gnu</artifactId>
  </dependency>
  <dependency>
    <groupId>org.chdb</groupId>
    <artifactId>chdb-native-macos-aarch64</artifactId>
  </dependency>
</dependencies>
```

The BOM keeps the driver and every native package on one version, which they must be.

**A native package is large**, because it contains the whole engine: 112 MB for macOS arm64,
128 MB for macOS x86_64, 130 MB for Linux aarch64, 167 MB for Linux x86_64, and around 350 MB
unpacked. There is no all-platforms package, on purpose: it would be the sum of those four.

### Versioning

`<engine version>.<binding revision>`, so `26.7.0.1` is the first Java release built against
engine 26.7.0. A new engine always means a new version. This is not SemVer; see
[work plan §4.3](CHDB_JAVA_V1_WORK_PLAN.md).

## Connecting

```
jdbc:chdb:                         in-memory
jdbc:chdb::memory:                 in-memory, explicit
jdbc:chdb:/var/lib/myapp/chdb      on-disk storage
jdbc:chdb:./data?max_threads=4     relative path, plus engine settings
```

Properties after `?` that the driver does not recognize are passed to the engine as ClickHouse
settings, so any `SET`-able setting works in the URL. Properties passed to
`DriverManager.getConnection(url, properties)` win over the URL's.

### One storage path per JVM

The engine binds a storage path when the first connection opens and keeps it until the last one
closes. Any number of connections may share that path — a pool of ten is fine, and they see
each other's committed writes. A connection asking for a *different* path is refused, with the
bound path, the requested path and the connections holding it in the message.

That is an engine property, not a driver limitation. If you need two storage paths at once, use
two JVMs.

`:memory:` counts as a bound path, and is shared: two in-memory connections in a JVM see each
other's tables, and the data lives until the last of them closes.

### One statement at a time per connection

A `Connection` runs one statement at a time, including the fetches its `ResultSet` makes. A
second statement — from any thread — waits for the first `ResultSet` to be closed.

So this is fine:

```java
try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT ...")) {
    while (rs.next()) { ... }
}
// closed, so the next statement can run
```

and this raises a `SQLException` rather than deadlocking, because the same thread cannot wait
for itself:

```java
ResultSet outer = statementA.executeQuery("SELECT ...");
ResultSet inner = statementB.executeQuery("SELECT ...");   // SQLException: 25000
```

Use a second `Connection` — they can share the storage path — or close the first result set.

## What works today

| | |
|---|---|
| ✅ | `Driver`, `Connection`, `Statement`, `PreparedStatement`, streaming `ResultSet` |
| ✅ | Auto-discovery through `DriverManager` and `META-INF/services/java.sql.Driver` |
| ✅ | Server-side parameter binding — no SQL string interpolation anywhere |
| ✅ | Scalar type matrix, `NULL` and `wasNull()` — see [type mapping](docs/type-mapping.md) |
| ✅ | `ResultSetMetaData`, and the `DatabaseMetaData` frameworks read |
| ✅ | `Statement.cancel()`, `setQueryTimeout()`, early `ResultSet` close |
| ✅ | Bounded memory on results far larger than the heap |
| ✅ | Host JVM signal handlers preserved — see [signal handlers](docs/signal-handlers.md) |
| ✅ | Native loading from the platform JAR, or a directory you point at |
| ✅ | UBSan over the whole suite; ASan over the shim's own logic |
| ✅ | All four platforms × Java 11/17/21/25, in CI |
| 🚧 | Framework smoke tests (Spring, HikariCP, ShardingSphere) |
| 🚧 | Soak tests; full-process ASan, which needs an upstream sanitizer build of chdb-core |
| 🚧 | Maven Central publishing |
| ❌ | Transactions, batch updates, scrollable/updatable result sets, `CallableStatement` |
| ❌ | `Array`, `Map`, `Tuple`, `Nested`, `Variant`, `JSON`, `Dynamic` columns |

Everything marked ✅ is covered by 238 tests — 148 that need no engine and 90 that do — plus a
199-check native sanitizer harness. Each of the four platforms runs all of them on each of the
four JDKs, so ✅ means sixteen platform-and-JDK combinations, not one.

The full list of refusals, and why each one is a refusal rather than a fake success, is in
[docs/unsupported.md](docs/unsupported.md).

## Documentation

| | |
|---|---|
| [Type mapping](docs/type-mapping.md) | ClickHouse ↔ JDBC ↔ Java, and how timezones are decided |
| [Unsupported JDBC](docs/unsupported.md) | What throws `SQLFeatureNotSupportedException`, and what to do instead |
| [Native loading](docs/native-loading.md) | Where libraries come from, cache layout, system properties, error messages |
| [Signal handlers](docs/signal-handlers.md) | What chDB does to your JVM's handlers, what the driver does about it, what you lose |
| [Memory](docs/memory.md) | Why `-Xmx` does not bound chDB, and what does |
| [ClassLoaders](docs/classloaders.md) | Tomcat, Spark, Flink: where to put the driver |
| [Upstream findings](docs/upstream-findings.md) | Engine behaviours this binding works around, with reproductions |
| [V1 progress](docs/v1-progress.md) | Phase-by-phase status against the work plan, and what to do next |

## Building from source

Needs a JDK 11+, Maven 3.9+, CMake 3.16+ and a C++17 compiler.

```bash
# 1. Compile the Java side. This also generates the JNI header the shim compiles against.
mvn -pl chdb-jdbc compile

# 2. Download the pinned engine, build the shim, stage the native package.
#    Downloads ~100 MB the first time and caches it under target/engine/download.
scripts/build-native.sh macos-aarch64        # or your platform id

# 3. Everything, including the integration tests.
mvn verify

# 4. Optionally, under sanitizers.
scripts/run-sanitizer-tests.sh macos-aarch64 address,undefined   # shim logic, no JVM
scripts/run-sanitizer-tests.sh macos-aarch64 undefined           # the whole JDBC suite
```

The two sanitizer passes cover different halves: ASan cannot run against the released engine,
which is not ASan-clean, so it is pointed at a JVM-free harness while UBSan covers the real
suite. `scripts/run-sanitizer-tests.sh` explains the split in its header, and
[docs/upstream-findings.md](docs/upstream-findings.md) has the evidence.

`scripts/build-native.sh` refuses a platform id that does not match the machine it runs on: a
shim linked for another architecture fails at `System.load()` in a user's JVM rather than at
build time. Each platform package is built on its own platform in CI.

To build against an engine you compiled yourself:

```bash
scripts/build-native.sh macos-aarch64 --local-engine /path/to/chdb-core/libchdb.so
```

The resulting package is stamped `engine.source=local` and must not be published.

### Repository layout

```
chdb-jdbc/                      pure-Java driver, loader and type mapping
chdb-jni/                       the JNI shim (C++17, ~1400 lines)
chdb-native-<platform>/         one Maven package per platform
chdb-bom/                       keeps them all on one version
chdb-integration-tests/         tests that need a real engine
chdb-examples/                  runnable examples
scripts/engine.properties       the pinned engine version and its checksums
scripts/fetch-libchdb.sh        downloads and verifies the pinned engine
scripts/build-native.sh         builds the shim and stages a platform package
```

## Reporting a problem

Native crashes and wrong results need the loaded runtime's identity, which the driver can
print:

```java
System.out.println(org.chdb.internal.NativeLibraryLoader.loadedRuntime());
System.out.println(org.chdb.internal.ChdbNative.shimBuildInfo());
System.out.println(org.chdb.internal.ChdbNative.signalDispositions());
```

Include that, plus `java -version`, your OS and architecture, the JDBC URL (with any secrets
removed) and the query.

## Licence

Apache-2.0. The native packages redistribute the chDB engine, also Apache-2.0; their
`META-INF/licenses/` directory carries the details.
