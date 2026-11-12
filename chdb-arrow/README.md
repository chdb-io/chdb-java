# chDB Arrow (in development)

This optional artifact currently contains experimental A1 batch/A2 reader registration,
A3 materialized output, A4 incremental output, and A5 synchronous insert. It is not an implementation of the complete
A1–A6 native Arrow contract.
It does not use Arrow IPC, and it does not promise zero copies inside ClickHouse.

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
`registerBatch(root, allocator)` adapts one current `VectorSchemaRoot` batch; the caller
retains ownership of the root and must not mutate or close it before unregistering.

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

The earlier core `26.11.0-rc.2` omitted a `get_schema` release during registration:
a value-correct Java scan left 148 bytes in the Arrow allocator. The rc.3 release
includes that fix, so **A1/A2/A5 tests are not skipped** on this baseline. One
separate regression for an unparseable String inserted into Int64 remains disabled
pending core investigation; it must not be reported as passing. CI exercises the
same suite on all four native platforms and Java 11/17/21/25 without a version
override. No production deployment should use a local `--local-engine` package or
`chdb.skipEngineVersionCheck`.
