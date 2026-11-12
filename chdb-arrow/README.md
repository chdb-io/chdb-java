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
locally built `libchdb.so` and `libchdb_java_jni.dylib` in the directory given by
`-Dchdb.library.path`. A published platform JAR with the older JNI ABI cannot run this
work-in-progress API. Arrow Java 19.0.0 on classpath also requires
`--add-opens=java.base/java.nio=ALL-UNNAMED` on supported JDKs.

For a local-only build on macOS arm64, after the core build has completed, verify that
`/Users/shawn/Downloads/code/chdb-core/libchdb.so` is the intended fixed version and run
`scripts/build-native.sh macos-aarch64 --local-engine /Users/shawn/Downloads/code/chdb-core/libchdb.so`.
The script stages the engine and new shim in `chdb-native-macos-aarch64/target/native/`.
Its local-source manifest is **not publishable**. Then run the opt-in test with the
staged library directory and JDK 11, for example:

```sh
mvn -pl chdb-arrow -am test -Dchdb.arrow.it=true \
  -Dchdb.library.path="$PWD/chdb-native-macos-aarch64/target/native/META-INF/chdb/native/macos/aarch64" \
  -Dchdb.skipEngineVersionCheck=true \
  -DargLine=--add-opens=java.base/java.nio=ALL-UNNAMED
```

`chdb.skipEngineVersionCheck` is diagnostic-only: this local core reports
`26.11.0-dev` whereas the Java release baseline is `26.11.0-rc.2`. Do not use
that override or the `--local-engine` package for a release.

On published core `26.11.0-rc.2`, the registration path omits the release of a
schema returned by `get_schema`. A Java C Stream scan returned the expected value
but left 148 bytes charged to its Arrow allocator. The test suite skips only the
registration-dependent A1/A2/A5 cases on this exact core version; A3/A4 and
unaffected cases still run and fail normally. This is a temporary PR validation
boundary, not a claim that repeated input registrations are safe to ship. Once
a fixed release with all four platform assets is pinned, remove that version gate
and run the entire suite without `chdb.skipEngineVersionCheck`.
