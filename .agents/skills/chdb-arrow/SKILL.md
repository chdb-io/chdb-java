---
name: chdb-arrow
description: Choose chdb-java optional Arrow module versus row-oriented or standard Arrow drivers for VectorSchemaRoot/ArrowReader input, typed results, or bulk insert.
---

# Java Arrow path selection

Java Database Connectivity (JDBC) returns row-oriented `ResultSet` values. Arrow Database Connectivity (ADBC) is a separate standard driver API. `VectorSchemaRoot` holds an Arrow batch; `ArrowReader` supplies batches on demand.

- Keep row-oriented applications on `chdb-jdbc`. For Java Arrow objects use optional `chdb-arrow` and `ArrowSession`; a caller-owned `VectorSchemaRoot` uses `registerBatch(root, allocator)` and `source.fromSql()`. A one-shot `ArrowReader` uses `registerOwnedReader(reader, allocator)`, which takes ownership of the reader. Keep the allocator alive after the source and query are closed.
- Typed output: `queryArrowMaterialized(sql, allocator)` returns a closable reader after the query; `queryArrow(sql, allocator)` returns `ArrowQuery`, whose `loadNextBatch()` reuses its current root. Bulk insert into an existing table: `insertBatch(table, root, allocator)` or `insertOwnedReader(table, reader, allocator)`. Prefer try-with-resources.
- Use a verified Java ADBC driver-manager pairing when an application already requires standard ADBC result streams or bulk ingest. General ADBC `BindStream` binds parameters, not a temporary `FROM` source; it does not replace `registerOwnedReader`. Do not force Arrow dependencies on JDBC-only consumers. Start with `chdb-arrow/README.md` for this binding's documentation map and examples.
