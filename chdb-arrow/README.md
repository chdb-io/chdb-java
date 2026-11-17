# chDB Arrow (in development)

This optional Java module lets an application query an in-memory Arrow batch or one-shot reader, read typed query results all at once or in batches, and insert Arrow data into an existing table. It is experimental; the full ownership, error, compatibility and type tests described by the native Arrow contract remain a separate acceptance gate.
It exchanges Arrow data through the native C Data/C Stream interfaces rather than Arrow Inter-Process Communication (IPC) bytes. C Data shares a schema, buffers and release callbacks; C Stream supplies successive batches. Internal conversion may still copy data.

## Arrow documentation in this Java binding

| What you need | Where to look |
| --- | --- |
| Install the optional Arrow module and matching native package | [Getting started](#getting-started) and [Testing from source](#testing-from-source) below |
| Query an in-memory Java Arrow batch or reader | [Query a caller-owned Arrow batch](#query-a-caller-owned-arrow-batch) and [API and ownership notes](#api-and-ownership-notes) below |
| Read typed results or insert a batch | [Read SQL results as Arrow batches](#read-sql-results-as-arrow-batches), [Insert a batch](#insert-a-batch-into-an-existing-table), and [`ArrowSessionTest`](src/test/java/org/chdb/arrow/ArrowSessionTest.java) |
| Check output type options | [`ArrowOptions`](src/main/java/org/chdb/arrow/ArrowOptions.java) and [its tests](src/test/java/org/chdb/arrow/ArrowOptionsTest.java) |
| Decide on Arrow Database Connectivity (ADBC) instead | An existing, verified Java ADBC client can use standard Arrow results or bulk ingest. It does not replace `registerOwnedReader` for a temporary SQL `FROM` source; Java Database Connectivity (JDBC) `ResultSet` is a separate row-oriented API. |

This module README is the Java binding's Arrow entry point. See the [core ownership contract](https://github.com/chdb-io/chdb-core/blob/main/docs/NATIVE_ARROW_BINDING_CONTRACT.md) for native ABI details; the standard JDBC docs are not Arrow usage instructions.

## Getting started

`chdb-arrow` is published as `1.1.0-rc.1` at `https://maven.chdb.io`. The older
`1.0.0-rc.2` native packages cannot run this Arrow module: keep all binding
artifacts on the same version. Add the public repository (no credentials required):

```xml
<repository>
  <id>chdb-rc</id>
  <url>https://maven.chdb.io</url>
</repository>
```

A Maven consumer needs the optional Arrow module and
**one** native package for its runtime platform, at the same binding version:

```xml
<dependencies>
  <dependency>
    <groupId>com.clickhouse.chdb</groupId>
    <artifactId>chdb-arrow</artifactId>
    <version>1.1.0-rc.1</version>
  </dependency>
  <!-- Example for macOS arm64; choose the package for your runtime platform. -->
  <dependency>
    <groupId>com.clickhouse.chdb</groupId>
    <artifactId>chdb-native-macos-aarch64</artifactId>
    <version>1.1.0-rc.1</version>
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

The input fields must have distinct names. The optional
`ArrowInsertOptions.REJECT_NULL_AS_DEFAULT` passes `insert_null_as_default=0`
for this insert. With that choice, a NULL source value targeting a non-nullable
column fails instead of becoming the column default. Existing overloads retain
the engine default. For example,
`session.insertBatch("arrow_demo", root, allocator,
ArrowInsertOptions.REJECT_NULL_AS_DEFAULT)`. Validate source and destination
types before ingest.

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

Streaming queries can bind core's typed named parameters without SQL interpolation:

```java
try (RootAllocator allocator = new RootAllocator();
        ArrowSession session = ArrowSession.openInMemory();
        ArrowQuery rows = session.queryArrow("SELECT {id:Int64} AS id",
                java.util.Collections.singletonMap("id", "42"), allocator)) {
    while (rows.loadNextBatch()) {
        System.out.println(rows.getVectorSchemaRoot().getVector("id").getObject(0));
    }
}
```

The map's values are strings parsed according to each SQL placeholder's type,
using the JDBC driver's escaped-text encoding. A null value requires a
`Nullable(...)` placeholder. Names must be simple ASCII identifiers. Binding
errors may surface on the first batch fetch; values do not remain on the session.

### Current Java interface limits

There is no export method on an existing JDBC `ResultSet` or `PreparedStatement`.
An Arrow session opens a separate connection, so connection-local state and JDBC
bindings do not carry over. The separate streaming named-parameter overload
does not accept JDBC `?` placeholders or a `PreparedStatement`; the materialized
Arrow C entry point has no matching parameter variant. Do not interpolate
untrusted values into SQL as a workaround. Incremental
output also has no caller-selected batch size: `queryArrow` returns core batches
at core's granularity. Adding a Java parameter that promises an exact batch
size would require a core facility or explicit Java-side rechunking and its
associated copy/allocation cost.

## Testing from source

The test `ArrowSessionTest` is opt-in (`-Dchdb.arrow.it=true`) and requires a matching
engine and JNI shim in the directory given by `-Dchdb.library.path`. The published
`1.1.0-rc.1` platform JAR has JNI ABI 5; the older `1.0.0-rc.2` ABI 2 package is not
interchangeable. Arrow Java 19.0.0 on classpath also requires
`--add-opens=java.base/java.nio=ALL-UNNAMED` on supported JDKs.

The current source pins stable chDB core `26.11.1`. For a macOS arm64 release-asset
smoke test, compile the Java side (`mvn -pl chdb-jdbc -am compile`), then run
`scripts/build-native.sh macos-aarch64`. That script downloads the exact v26.11.1 asset,
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
a value-correct Java scan left 148 bytes in the Arrow allocator. The later rc.3 fix
is included in v26.11.1, so the Arrow input and insert tests run on this baseline. CI exercises the
same suite on all four native platforms and Java 11/17/21/25 without a version
override. No production deployment should use a local `--local-engine` package or
`chdb.skipEngineVersionCheck`.
