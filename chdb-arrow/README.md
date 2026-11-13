# chDB Arrow (in development)

This optional artifact currently contains experimental A1 batch/A2 reader registration,
A3 materialized output, A4 incremental output, and A5 synchronous insert. It is not an implementation of the complete
A1–A6 native Arrow contract.
It does not use Arrow IPC, and it does not promise zero copies inside ClickHouse.

## Getting started

`chdb-arrow` is **not published yet**. The published Java `1.0.0-rc.2` and its
native packages cannot run this branch's Arrow API. To try it now, build the
matching `1.0.0-rc.3-SNAPSHOT` Java and native artifacts from this checkout (see
[Testing from source](#testing-from-source) below). Once a matching set of
artifacts is available, a Maven consumer needs the optional Arrow module and
**one** native package for its runtime platform, at the same binding version:

```xml
<dependencies>
  <dependency>
    <groupId>com.clickhouse.chdb</groupId>
    <artifactId>chdb-arrow</artifactId>
    <version>1.0.0-rc.3-SNAPSHOT</version>
  </dependency>
  <!-- Example for macOS arm64; choose the package for your runtime platform. -->
  <dependency>
    <groupId>com.clickhouse.chdb</groupId>
    <artifactId>chdb-native-macos-aarch64</artifactId>
    <version>1.0.0-rc.3-SNAPSHOT</version>
  </dependency>
</dependencies>
```

The other native artifact IDs are `chdb-native-macos-x86_64`,
`chdb-native-linux-x86_64-gnu`, and `chdb-native-linux-aarch64-gnu`.
The native package brings in `chdb-jdbc`; ordinary JDBC consumers do not need
`chdb-arrow`. Run the JVM with
`--add-opens=java.base/java.nio=ALL-UNNAMED` for Arrow Java 19.0.0.
This module uses Arrow's `arrow-memory-unsafe` allocator: the Arrow 19 Netty
allocator fails during initialization on JDK 25 and 26. JDK 26 warns that
some `sun.misc.Unsafe` methods may be removed in a future release, so this
choice is not a promise of compatibility with future JDKs.
Keep the allocator open until every Arrow reader, registered source and root
using it has closed.

The following examples use these imports and can be placed in a method that
declares `throws Exception`:

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.chdb.arrow.ArrowQuery;
import org.chdb.arrow.ArrowSession;
import org.chdb.arrow.RegisteredArrowSource;
```

### Read SQL results as Arrow batches

`queryArrow` fetches incrementally. Close it even if you stop before EOF; its
current `VectorSchemaRoot` is reused on the next `loadNextBatch()`, so copy or
transfer any data you need to retain. A concurrent `close()` (including closing
a registered source used by the query) also invalidates that root; coordinate
batch reads with closure. Use `queryArrowMaterialized` instead when
the complete query should finish before a reader is returned.

```java
try (RootAllocator allocator = new RootAllocator();
        ArrowSession session = ArrowSession.openInMemory();
        ArrowQuery rows = session.queryArrow(
                "SELECT toInt64(number) AS id FROM numbers(3)", allocator)) {
    while (rows.loadNextBatch()) {
        VectorSchemaRoot batch = rows.getVectorSchemaRoot();
        BigIntVector ids = (BigIntVector) batch.getVector("id");
        for (int i = 0; i < batch.getRowCount(); i++) {
            System.out.println(ids.get(i));
        }
    }
}
```

### Query a caller-owned Arrow batch

`registerBatch` exposes the root's **current batch** as a one-shot SQL source.
The source, query and root must use the same session/allocator lifetime; do not
mutate or close the caller-owned root before the registration closes. The SQL
fragment from `fromSql()` is only valid on that session.

```java
try (RootAllocator allocator = new RootAllocator();
        ArrowSession session = ArrowSession.openInMemory()) {
    BigIntVector ids = new BigIntVector("id", allocator);
    try (VectorSchemaRoot root = VectorSchemaRoot.of(ids)) {
        ids.allocateNew(2);
        ids.setSafe(0, 7);
        ids.setSafe(1, 9);
        ids.setValueCount(2);
        root.setRowCount(2);
        try (RegisteredArrowSource source = session.registerBatch(root, allocator);
                ArrowReader result = session.queryArrowMaterialized(
                        "SELECT sum(id) AS total FROM " + source.fromSql(), allocator)) {
            if (result.loadNextBatch()) {
                System.out.println(result.getVectorSchemaRoot().getVector("total").getObject(0));
            }
        }
    }
}
```

For a multi-batch input, use `registerOwnedReader(reader, allocator)` instead:
registration takes ownership of the reader, consumes it only when scanned, and
closes it on unregister. Its allocator remains caller-owned.

### Insert a batch into an existing table

`ArrowSession` owns a dedicated connection, so this example creates the table
through another JDBC connection to the same process-wide in-memory database.
The insert is synchronous and reports rows written; it does not create a table
or provide a transaction/rollback guarantee.

```java
try (RootAllocator allocator = new RootAllocator();
        Connection ddl = DriverManager.getConnection("jdbc:chdb::memory:");
        ArrowSession session = ArrowSession.openInMemory()) {
    try (Statement statement = ddl.createStatement()) {
        statement.execute("CREATE TABLE arrow_demo (id Int64) ENGINE=MergeTree ORDER BY id");
    }
    BigIntVector ids = new BigIntVector("id", allocator);
    try (VectorSchemaRoot root = VectorSchemaRoot.of(ids)) {
        ids.allocateNew(2);
        ids.setSafe(0, 7);
        ids.setSafe(1, 9);
        ids.setValueCount(2);
        root.setRowCount(2);
        long written = session.insertBatch("arrow_demo", root, allocator);
        System.out.println("Rows written: " + written);
    }
}
```

The input fields must have distinct names. Avoid relying on implicit type
coercion: a known core regression can silently insert `0` for an unparseable
Arrow String sent to an `Int64` column. Validate types and values upstream.

## API and ownership notes

`ArrowSession.openInMemory()` opens a dedicated JDBC-managed connection to the process-wide
in-memory database; `open(jdbcUrl)` also supports the JDBC driver's path rules.
`queryArrowMaterialized(sql, allocator)` executes the complete query before it
returns an Apache Arrow Java `ArrowReader`. The caller closes the reader and then its
allocator. The session does not share another JDBC connection. `registerOwnedReader(reader, allocator)`
transfers reader ownership and returns a closable scope with `fromSql()`. Use that SQL
fragment only on the owning session, for one scan. Keep the allocator alive until the scope
is closed. A close requested while a materialized query runs waits for it to complete,
then unregisters before releasing the C stream. `queryArrow(sql, allocator)` returns an
`ArrowQuery` reader that pulls one core batch at a time. Close it after partial consumption,
or call `cancel()` to stop future fetches; cancellation waits for a fetch already in progress.
Only one incremental query may be active on a session.
`insertBatch(table, root, allocator)` and `insertOwnedReader(table, reader, allocator)`
use core's synchronous C Stream insert. Only simple ASCII table names and distinctly named
source columns are accepted; this excludes core's unnamed-column positional fallback.
There is no transaction guarantee and a failed insert can leave partial writes.
`ArrowOptions.DEFAULT` rejects unsupported output types, materializes LowCardinality and
exports String as UTF-8. Both query forms accept an explicit `ArrowOptions` for binary
String output, dictionary LowCardinality and lossy unsupported-type output. DateTime
defaults to Arrow unsigned 32-bit seconds without timezone; use DateTime64 in SQL for
timestamp semantics.
Compose named choices from the immutable default, for example
`ArrowOptions.DEFAULT.withLowCardinalityAsDictionary().withStringAsBinary()`.
`withUnsupportedAsBinary()` is deliberately lossy; it does not preserve the
original ClickHouse type. The three-boolean constructor remains available for
existing callers, but the named choices are easier to review at call sites.
`registerBatch(root, allocator)` adapts one current `VectorSchemaRoot` batch; the caller
retains ownership of the root and must not mutate or close it before unregistering.
`insertOwnedReader` closes an accepted reader even when table/schema validation
or native insertion fails. If the session is closed or a query is active, it
rejects the call before taking ownership. `insertBatch` never owns the root.

### Current Java interface limits

There is no export method on an existing JDBC `ResultSet` or `PreparedStatement`.
An Arrow session opens a separate connection, so connection-local state and JDBC
bindings do not carry over. The core has a server-side named-parameter entry
point for *streaming* Arrow queries, but the Java/JNI path does not yet expose
it; the materialized Arrow C entry point has no matching parameter variant.
Do not interpolate untrusted values into SQL as a workaround. Incremental
output also has no caller-selected batch size: `queryArrow` returns core batches
at core's granularity. Adding a Java parameter that promises an exact batch
size would require a core facility or explicit Java-side rechunking and its
associated copy/allocation cost.

## Testing from source

The test `ArrowSessionTest` is opt-in (`-Dchdb.arrow.it=true`) and requires a matching
engine and JNI shim in the directory given by `-Dchdb.library.path`. The previously
published Java `1.0.0-rc.2` platform JAR has JNI ABI 2 and cannot run this work-in-progress
ABI 3 module. Arrow Java 19.0.0 on classpath also requires
`--add-opens=java.base/java.nio=ALL-UNNAMED` on supported JDKs.

The current source pins chDB core `26.11.0-rc.3`. For a macOS arm64 release-asset
smoke test, compile the Java side (`mvn -pl chdb-jdbc -am compile`), then run
`scripts/build-native.sh macos-aarch64`. That script downloads the exact rc.3 asset,
verifies its SHA-256 and stages the engine plus JNI shim in
`chdb-native-macos-aarch64/target/native/`. Run the opt-in test with the staged pair:

```sh
mvn -pl chdb-arrow -am test -Dchdb.arrow.it=true \
  -Dchdb.library.path="$PWD/chdb-native-macos-aarch64/target/native/META-INF/chdb/native/macos/aarch64" \
  -DargLine=--add-opens=java.base/java.nio=ALL-UNNAMED
```

To use the Maven dependency example above from another local project after
staging this platform's native pair, install the matching modules into your
local Maven repository (this does **not** publish them):

```sh
mvn -pl chdb-arrow,chdb-native-macos-aarch64 -am install -DskipTests
```

Choose the corresponding native module if building on another supported
platform, and run its `scripts/build-native.sh <platform-id>` first.

The earlier core `26.11.0-rc.2` omitted a `get_schema` release during registration:
a value-correct Java scan left 148 bytes in the Arrow allocator. The rc.3 release
includes that fix, so **A1/A2/A5 tests are not skipped** on this baseline. One
separate regression for an unparseable String inserted into Int64 remains disabled
pending core investigation; it must not be reported as passing. CI exercises the
same suite on all four native platforms and Java 11/17/21/25 without a version
override. No production deployment should use a local `--local-engine` package or
`chdb.skipEngineVersionCheck`.
