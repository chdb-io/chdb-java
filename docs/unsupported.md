# Unsupported JDBC

Everything here throws `SQLFeatureNotSupportedException` (SQLSTATE `0A000`) rather than
returning `null`, `0`, `false` or a fake success.

That is deliberate. A driver that answers "yes, transactions are supported" and then ignores
`commit()` gives you an application that appears to have a unit of work and does not. An
exception at the call site is worth more than wrong results later, and a framework probing for a
capability gets a truthful answer it can branch on.

`DatabaseMetaData` agrees with the behaviour throughout: whatever
`supportsTransactions()` and friends report is what the methods actually do.

## Transactions

| Method | |
|---|---|
| `setAutoCommit(false)` | throws — `setAutoCommit(true)` is accepted, being already the case |
| `commit()`, `rollback()` | throw |
| `setSavepoint()`, `releaseSavepoint()`, `rollback(Savepoint)` | throw |
| `setTransactionIsolation(level)` | throws for anything but `TRANSACTION_NONE` |
| `setHoldability(h)` | throws for anything but `CLOSE_CURSORS_AT_COMMIT` |

chDB is an embedded analytical engine with no transaction manager. `getAutoCommit()` returns
`true`, `getTransactionIsolation()` returns `TRANSACTION_NONE`, and `supportsTransactions()`
returns `false`.

**Instead:** each statement takes effect as it executes. For all-or-nothing loading, stage into
a temporary table and swap it in with `EXCHANGE TABLES` or `RENAME TABLE`.

## Result sets

| | |
|---|---|
| `previous()`, `first()`, `last()`, `absolute()`, `relative()`, `beforeFirst()`, `afterLast()` | throw |
| `isLast()` | throws |
| every `updateXxx()`, `insertRow()`, `updateRow()`, `deleteRow()`, `refreshRow()` | throw |
| `getCursorName()`, `setCursorName()` | throw |
| `getUnicodeStream()` | throws (deprecated since JDBC 2.0) |

Result sets are `TYPE_FORWARD_ONLY` and `CONCUR_READ_ONLY`, and asking `createStatement` for
anything else is refused at creation rather than silently downgraded.

Forward-only is what makes streaming work: one Arrow batch is live at a time, so a 100 GB
result reads in the footprint of a 100 MB one. Supporting `previous()` would mean buffering the
whole result, which defeats that.

`isLast()` throws rather than guessing, because answering needs a one-row lookahead — which for
a stream means fetching a batch the caller may never read, and would be wrong at every batch
boundary.

**Instead:** collect the rows you need into a Java collection, or re-run the query with
`ORDER BY` and `LIMIT`/`OFFSET`. For updates, use `INSERT`, `ALTER TABLE ... UPDATE` or
`ALTER TABLE ... DELETE`.

## Batch updates

`addBatch()`, `clearBatch()`, `executeBatch()`, `executeLargeBatch()` all throw, on both
`Statement` and `PreparedStatement`.

**Instead:** ClickHouse wants few large inserts, not many small ones, so a multi-row `VALUES`
or an `INSERT ... SELECT` from a file or table function is both simpler and much faster than a
batch:

```java
// One statement, many rows.
statement.executeUpdate("INSERT INTO t VALUES (1,'a'), (2,'b'), (3,'c')");

// Or let the engine read the data itself.
statement.executeUpdate("INSERT INTO t SELECT * FROM file('data.parquet', 'Parquet')");
```

A high-throughput streaming insert path is on the post-V1 list; the engine's C ABI has
`chdb_stream_insert`, which V1 does not expose.

## Stored procedures

`prepareCall()` and every `CallableStatement` method throw. ClickHouse has no stored
procedures.

**Instead:** a `CREATE FUNCTION` UDF called from `SELECT`, or a parameterized view.

## Generated keys

`getGeneratedKeys()` throws, and `prepareStatement(sql, RETURN_GENERATED_KEYS)` and the
column-list overloads throw. ClickHouse has no auto-increment or sequences.

**Instead:** generate the key yourself — `generateUUIDv4()` in SQL, or a `UUID` from Java.

## SQL types

| | |
|---|---|
| `getBlob()`, `setBlob()`, `createBlob()` | throw — use `getBytes`/`setBytes` |
| `getClob()`, `getNClob()`, `setClob()` | throw — use `getString`/`setString` |
| `getArray()`, `setArray()`, `createArrayOf()` | throw — see [type mapping](type-mapping.md) |
| `getRef()`, `setRef()` | throw |
| `getRowId()`, `setRowId()` | throw — chDB tables have no row identity |
| `getSQLXML()`, `setSQLXML()`, `createSQLXML()` | throw |
| `createStruct()` | throws |
| `getURL()` | throws — use `getString` |
| `setTypeMap()` with a non-empty map | throws |

## Columns V1 cannot read

`Array`, `Map`, `Tuple`, `Nested`, `Variant`, `Dynamic`, `JSON`, `AggregateFunction`, the
geometry types, `IPv4` and `IPv6`.

`ResultSetMetaData` reports these as `Unsupported(arrow=<format>)` with type `OTHER`, so a
framework can see the column. Reading one throws, naming the column and the workaround: cast in
SQL with `toString(col)`, `hex(col)` or a type-specific function. Full detail in
[type mapping](type-mapping.md).

The alternative — decoding buffers the driver does not understand — risks silently wrong values,
which is worse than an error.

## Other

| | |
|---|---|
| `setMaxFieldSize(n)` for `n != 0` | throws; the driver does not truncate values |
| `getMoreResults(KEEP_CURRENT_RESULT)` | throws; a statement has one result |
| `Driver.getParentLogger()` | throws; the driver does not use `java.util.logging` |
| `Connection.unwrap(x)` for an unrelated `x` | throws |

## Accepted but inert

Not refused, because throwing would break frameworks that set them as a matter of course, and
honouring them is not possible:

| | |
|---|---|
| `setFetchSize(n)` | recorded and reported by `getFetchSize()`. Batch size is the engine's block size, which the driver does not control. |
| `setReadOnly(true)` | recorded and reported by `isReadOnly()`. JDBC describes it as an optimizer hint; chDB has no session read-only mode, and rejecting writes in the driver would need it to classify every statement and would still miss what a table function can do. |
| `setNetworkTimeout(e, ms)` | recorded and reported. There is no network — the engine is in this process. `Statement.setQueryTimeout` is the timeout that does something. |
| `setEscapeProcessing(b)` | there is no escape processing; SQL is passed through verbatim. |
| `setClientInfo(...)` | stored and returned; not sent anywhere. |
| `setCatalog(name)` | stored and returned. ClickHouse has one namespace level and it is mapped to JDBC's *schema*, so use `setSchema`. |

## Constraints, not refusals

These are supported, with a rule you have to know:

**One storage path per JVM.** Many connections may share it; a second path is refused with a
diagnostic. See the [README](../README.md#one-storage-path-per-jvm).

**One statement at a time per connection**, including a result set's fetches. A second
statement waits for the first result set to close; a second one on the *same thread* raises
`SQLException` (SQLSTATE `25000`) rather than deadlocking. See the
[README](../README.md#one-statement-at-a-time-per-connection).

**Stop your query threads before the JVM exits.** The driver installs a shutdown hook that
closes connections the application forgot, which covers a leaked result set: without it, a JVM
exiting with a streaming `ResultSet` open aborts inside the engine (SIGABRT, exit 134) rather
than exiting. Having closed them it calls `chdb_shutdown()`, which joins the engine's threads
so the host's own native teardown does not race them. Disable both with
`-Dchdb.shutdownHook=false` if the host manages its own teardown.

One consequence worth knowing: `chdb_connect()` fails once the hook has run, because
`chdb_shutdown()` closes the engine for the rest of the process. The JVM runs shutdown hooks
concurrently and in no defined order, so a host that queries chDB from a shutdown hook of its
own is racing this one. Do that work before shutdown, or turn the hook off.

What the hook cannot cover is a thread still *executing* a query when the process halts, and
here it is worse than that: the hook's attempt to close that connection is itself enough to
abort. Four threads each running a long aggregate while `main` returns exits 134 with the hook
on and 0 with it off, on 26.7.0 and 26.7.2-rc.2 alike. `chdb_shutdown()` does not rescue it —
the engine declines to shut down while any connection is open, and a connection with a query
in flight is the one the hook cannot close. So **shut your executor down before returning from
`main`**. If you cannot, `-Dchdb.shutdownHook=false` is the lesser evil for that shape, at the
cost of the leaked-stream case above. See
[upstream findings §9](upstream-findings.md).

**A non-ASCII storage path needs the JVM to be under a UTF-8 locale.** The driver resolves the
path with `java.nio.file` to decide whether two URLs name the same directory, and that encodes
with `sun.jnu.encoding` — which follows the OS locale and is ASCII on a container started with
no `LANG`, the default for most base images. The connection is then refused with SQLSTATE
`08001` and a message naming the encoding.

This is the JVM's limit rather than chDB's: the driver hands the engine UTF-8 bytes and the
engine creates the directory correctly, so the same URL works in the same container under
`LANG=C.UTF-8`. Set a UTF-8 locale, or use an ASCII path. Spaces, and names up to the
filesystem's own length limit, work regardless.

**`PreparedStatement.getMetaData()` before execution** throws. chDB's C ABI cannot describe a
statement without running it, and running the caller's query as a side effect of asking about it
is not something a metadata call may do. Call it on the `ResultSet` after `executeQuery()`.

**`ParameterMetaData`** reports the parameter count exactly, and reports every parameter as
`VARCHAR`/`String` — which is what the driver actually binds, not a placeholder answer, though
it says nothing about the column a value is compared against.

**`getColumns()`** reports the ClickHouse type name and leaves `DATA_TYPE` as `OTHER`. Mapping a
type *name* to a JDBC type would be a second implementation of the Arrow mapping, and the two
would drift. For a column's JDBC type, run `SELECT * FROM t LIMIT 0` and read its
`ResultSetMetaData`, which goes through the one mapping that matters.

**`jdbcCompliant()` returns `false`**, and will keep doing so. Compliance requires full SQL-92
entry level and the whole API surface; chDB has no transactions, no scrollable cursors and
ClickHouse SQL. Claiming it would be a false statement about the driver rather than a
formality.
