# Unsupported JDBC

Everything here throws `SQLFeatureNotSupportedException` (SQLSTATE `0A000`) rather than
returning `null`, `0`, `false` or a fake success.

That is deliberate. A driver that answers "yes, transactions are supported" and then ignores
`commit()` gives you an application that appears to have a unit of work and does not. An
exception at the call site is worth more than wrong results later, and a framework probing for a
capability gets a truthful answer it can branch on.

`DatabaseMetaData` agrees with the behaviour throughout: whatever
`supportsTransactions()` and friends report is what the methods actually do.

Two sections at the end are about frameworks rather than the API: what
[`DatabaseMetaData`](#databasemetadata-nothing-on-it-throws) does when a GUI sweeps it, and
[what each framework hits](#under-a-framework).

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

**A misspelled setting name in the URL is silent.** Properties after `?` that the driver does
not recognize are handed to the engine as `--key=value`, and the engine accepts a name it has
never heard of without complaint: `jdbc:chdb:/data?max_thread=4` connects, and nothing applies
`max_threads`. The driver cannot help — it has no list of setting names to check against, and
inventing one would refuse settings a newer engine added.

An invalid *value* for a name the engine does know is caught, since engine 26.7.2-rc.2:
`?max_threads=not-a-number`, `?max_threads=-5` and `?max_memory_usage=abc` raise `SQLException`
rather than connecting. Engine 26.7.0 accepted those too, so this is stricter than it was. The
exception names the arguments that were passed, because `chdb_connect()` itself reports nothing.

If a setting matters, assert it took effect:
`SELECT value FROM system.settings WHERE name = 'max_threads'`. See
[upstream findings §5](upstream-findings.md).

**A non-ASCII storage path works whatever the JVM's locale is**, including a container started
with no `LANG` — the default for most base images, which gives the JVM an ASCII
`sun.jnu.encoding` and makes `java.nio.file` refuse the name outright. The driver only needs
the path in order to decide whether two URLs name the same directory, so where `Paths.get`
cannot encode it the driver absolutises and normalises the path as text instead. `normalize()`
is a text operation anyway: it collapses `.` and `..` without touching the filesystem. The
engine is handed the path as UTF-8 bytes and creates the directory itself.

What still needs a UTF-8 locale is *your own* `java.nio.file` code: on such a JVM the
application cannot open, list or delete that directory through `File`/`Path`, even though the
driver and the engine are using it. If you inspect the storage directory from Java, set
`LANG=C.UTF-8` or `LC_ALL=C.UTF-8`. Note that `-Dfile.encoding=UTF-8` will not do it — JEP 400
changed `file.encoding` and deliberately left `sun.jnu.encoding` following the OS locale.

Spaces, and names up to the filesystem's own length limit, work regardless. A path containing a
NUL is refused (SQLSTATE `08001`): it would truncate the string handed to the engine's C ABI.

**`PreparedStatement.getMetaData()` before execution** throws. chDB's C ABI cannot describe a
statement without running it, and running the caller's query as a side effect of asking about it
is not something a metadata call may do. Call it on the `ResultSet` after `executeQuery()`.

**`ParameterMetaData`** reports the parameter count exactly, and reports every parameter as
`VARCHAR`/`String` — which is what the driver actually binds, not a placeholder answer, though
it says nothing about the column a value is compared against.

**A bound parameter is a `String`, and ClickHouse will not convert one everywhere.** This is the
consequence of the line above, and it is the one that shows up under a query builder. Parameters
go to the engine through `chdb_query_with_params_n`, whose values are text, so every `?` arrives
typed `String` whichever setter bound it. ClickHouse converts a string literal to the other
side's type in a comparison, and does not convert it where a numeric *constant* is required.
Measured on engine 26.7.0:

| Position | |
|---|---|
| `WHERE n = ?`, `< ?`, `> ?`, `IN (?)` | works — the literal is converted to the column's type |
| `SELECT ?`, `length(?)`, any string context | works |
| `LIMIT ?`, `OFFSET ?` | **fails** — Code 440, "LIMIT expression must be constant with numeric type. Actual: '3'" |
| `n + ?`, `? + 1`, arithmetic | **fails** — Code 43, "Illegal types UInt64 and String of arguments of function plus" |
| `numbers(?)` and other numeric table-function arguments | **fails** — Code 43, "Illegal type String expression, must be numeric type" |

**Instead:** cast the parameter in the SQL — `numbers(toUInt64(?))`, `LIMIT toUInt64(?)` — or
render the value into the statement and let the framework escape it. For jOOQ that means
`StatementType.STATIC_STATEMENT`; see [under a framework](#under-a-framework).

Splicing the value into the SQL string yourself is the one thing not to do: the escaping is what
`bind()` exists for, and `PreparedStatementIT` is the evidence it holds.

**`getColumns()`** reports the ClickHouse type name and leaves `DATA_TYPE` as `OTHER`. Mapping a
type *name* to a JDBC type would be a second implementation of the Arrow mapping, and the two
would drift. For a column's JDBC type, run `SELECT * FROM t LIMIT 0` and read its
`ResultSetMetaData`, which goes through the one mapping that matters.

**`jdbcCompliant()` returns `false`**, and will keep doing so. Compliance requires full SQL-92
entry level and the whole API surface; chDB has no transactions, no scrollable cursors and
ClickHouse SQL. Claiming it would be a false statement about the driver rather than a
formality.

## `DatabaseMetaData`: nothing on it throws

A JDBC GUI — DBeaver, DataGrip, SQuirreL — sweeps most of `DatabaseMetaData` while a connection
is being opened, before the user has typed anything, and it does so whether or not the answer is
useful to it. One method throwing `SQLFeatureNotSupportedException` inside that sweep is reported
as "could not connect", so the driver looks broken on first contact rather than merely limited.

`DatabaseMetaDataSurfaceIT` reproduces the sweep: every method the running JDK reports on
`DatabaseMetaData`, invoked reflectively with the arguments a tool would pass. On engine 26.7.0,
under JDK 11, 21 and 26 alike:

| | |
|---|---|
| Returned a value, or a result set with rows | 160 |
| Returned a result set with no rows | 19 |
| Threw `SQLFeatureNotSupportedException` | **0** |
| Threw anything else | **0** |
| **Total** | **179** |

How many methods that is belongs to the JDK rather than to this driver. `DatabaseMetaData`
declares 177 — of which `getSchemas` and `supportsConvert` are overload pairs — and inherits
`unwrap` and `isWrapperFor` from `Wrapper`, for 179 from `getMethods()`. Measured identical on
JDK 11, 21 and 26; Java 11 is this driver's floor and gets the same sweep as the newest JDK, so
the coverage above is not a claim that only holds on a recent runtime.

The test therefore asserts *coverage* rather than that number: one classified result per method
the running JDK reports, exactly once. A future JDBC revision may move the total without
touching this driver, whereas a method quietly falling out of the sweep is a real regression and
fails.

`DatabaseMetaData` is the one interface in this driver with no refusal anywhere in it, and that
is on purpose: it is the interface whose whole job is to be asked questions by software that
cannot handle being told no. The test asserts the zeros rather than reporting them, so the
property cannot be lost by accident.

The 19 with no rows are the ones ClickHouse has nothing to say about — no foreign keys, no
indexes in the JDBC sense, no procedures, no UDTs, no privileges table, no catalog level:

`getAttributes` · `getBestRowIdentifier` · `getCatalogs` · `getClientInfoProperties` ·
`getColumnPrivileges` · `getCrossReference` · `getExportedKeys` · `getFunctionColumns` ·
`getImportedKeys` · `getIndexInfo` · `getPrimaryKeys` · `getProcedureColumns` · `getProcedures` ·
`getPseudoColumns` · `getSuperTables` · `getSuperTypes` · `getTablePrivileges` · `getUDTs` ·
`getVersionColumns`

An empty result set is not a cheap substitute for an exception here — it is a different code
path, and the one jOOQ and MyBatis actually read. Both address the columns of these sets *by
label*, so a zero-row set spelling `PK_NAME` differently, or reporting no columns at all, is as
broken as a throw and far harder to notice. `DatabaseMetaDataSurfaceIT` therefore also checks
every result-set-returning method against the column names JDBC 4.3 specifies — transcribed from
the JDBC javadoc, not from this driver, so a rename here is a failure — and calls every
`ResultSetMetaData` accessor on every column of every one of them. All present, all answerable,
rows or not.

## Under a framework

What each framework does that the driver refuses, and what to do instead. `HikariCP` and Spring
`JdbcTemplate` are covered elsewhere and are not repeated here; these are the four from issue
&#35;11.

Versions are pinned to the last release of each that still runs on Java 11, which is this
driver's floor. jOOQ 3.17 and HikariCP 6 require Java 17, and testing those would quietly drop
Java 11 out of the supported matrix.

### MyBatis 3.5.19 — works, with one configuration change

`MyBatis`'s default `JdbcTransactionFactory` calls `setAutoCommit(false)` on the connection the
moment a session opens, because `openSession()` with no argument means "not auto-commit". The
driver refuses that, so the session fails on its first statement.

**Instead:** `openSession(true)`, or configure `ManagedTransactionFactory` — which makes
`commit()` and `rollback()` no-ops rather than calls the driver has to refuse, and is the honest
description of what a session against chDB is.

Everything else works. Automatic result mapping, which is MyBatis's heaviest use of
`ResultSetMetaData`, picks the right `TypeHandler` per column from the driver's reported JDBC
types; an empty result set gives an empty list; `insert()` returns the real affected-row count.
`#{}` parameters bind, subject to the
[String-typed parameter rule](#constraints-not-refusals) above — `numbers(#{n})` needs
`numbers(toUInt64(#{n}))`.

### jOOQ 3.16.23 — works, with two things to know

**`.limit()` fails by default.** jOOQ renders a limit as a bind value, so `LIMIT ?` reaches the
engine as `LIMIT '3'` and is refused. This is the most-used clause jOOQ parameterises, so it is
the first thing a jOOQ user hits.

**Instead:** `new Settings().withStatementType(StatementType.STATIC_STATEMENT)`, which has jOOQ
render its values inline. jOOQ escapes them itself, so this is not a return to concatenated SQL.

**`transaction()` is refused out of the box**, for the same reason as everything else in
[Transactions](#transactions): jOOQ's `DefaultTransactionProvider` calls `setAutoCommit(false)`
before running the block, so the block never runs. The connection survives the refusal and stays
usable.

`TransactionProvider` is a jOOQ SPI with three methods, so the block *can* be made to run — but
read the next paragraph before doing it, and note that jOOQ's own `NoTransactionProvider` is not
the way:

```java
// Honoured. Not NoTransactionProvider: DefaultConfiguration.transactionProvider() treats that
// class as a sentinel for "unset" and substitutes DefaultTransactionProvider, so configuring
// it changes nothing. Subclassing it does not help either — the check is an instanceof.
final class NoOpTransactionProvider implements TransactionProvider {
    public void begin(TransactionContext ctx) {}
    public void commit(TransactionContext ctx) {}
    public void rollback(TransactionContext ctx) {}
}

DSLContext ctx = DSL.using(new DefaultConfiguration()
        .set(connection)
        .set(SQLDialect.DEFAULT)
        .set(new NoOpTransactionProvider()));
```

**What that costs is the whole transaction.** The statements inside the block run one at a time
exactly as they would outside it, and a failure part-way through leaves the earlier ones
applied — `JooqIT` asserts precisely that, by throwing out of a block after an `INSERT` and
finding the row still there. So this buys nothing except the ability to keep `transaction()` in
the source; it is not a weak transaction, it is no transaction, and code that reads as though it
had a unit of work would not have one.

That is not a gap in the driver. chDB has no transaction manager, so a framework's transaction
abstraction can only degrade to a no-op on it — the choice is between a refusal at the call site
and a block that silently is not atomic. The driver refuses; if you install the no-op provider
you are choosing the second, and the honest thing is then to stop writing `transaction()` at all
and let each statement stand on its own, as [Transactions](#transactions) suggests.

One thing that is not a driver limitation but will look like one: **jOOQ types every chDB column
as `Object`.** jOOQ resolves a field's Java type from the type *name* against its dialect
registry, and no jOOQ dialect this driver is routed to knows `Int32` or `DateTime64`. The driver
itself reports the column correctly — `ResultSetMetaData` gives type `INTEGER`, type name
`Int32`, class name `java.lang.Integer`, on a populated and on a zero-row result alike, which
`JooqIT` asserts. So declare the type at the call site: `DSL.field("n", Integer.class)`, or
`record.get("n", Integer.class)`.

jOOQ's `meta()` walk — `getCatalogs`, `getSchemas`, `getTables`, `getColumns`, `getPrimaryKeys`,
`getIndexInfo`, the heaviest `DatabaseMetaData` use of anything tested here — completes, and
lands on an empty key list and an empty index list rather than an error.

jOOQ resolves this driver to `SQLDialect.DEFAULT`, because `getDatabaseProductName()` returns
`chDB` and no jOOQ version recognises it. Everything above was measured under `DEFAULT`.

### DBeaver and other JDBC GUIs — no blocker found

Not installed and driven, which is not practical in CI. Covered instead by the
[full metadata sweep](#databasemetadata-nothing-on-it-throws), which is the part of a GUI's
connect sequence that can fail: 179 methods on JDK 11 through 26, nothing throws, every result
set well-formed.

What that does not cover is a GUI's own SQL — the statements it runs to populate its navigator
tree beyond `DatabaseMetaData`, its data editor's assumption that a result set can be updated,
and its use of `Connection.setAutoCommit(false)` when the user turns off auto-commit in the
toolbar. The first is engine SQL rather than driver surface; the other two are refused, and
[Result sets](#result-sets) and [Transactions](#transactions) say so.

### Apache ShardingSphere 5.5.3 — cannot be wired up by configuration

Not a driver limitation: nothing the driver can change makes this work, and the fix belongs
upstream.

ShardingSphere already knows about chDB: its `ClickHouseDatabaseType` lists `jdbc:chdb` among
its JDBC URL prefixes alongside `jdbc:clickhouse:` and `jdbc:ch:`, so a chDB URL is routed to
its ClickHouse dialect without being asked. But every `DatabaseType` then parses the URL with
`StandardJdbcUrlParser`, which requires the `//authority` component of a client/server URL to be
present and refuses the URL outright when it is not. An embedded engine has no host and no port,
so no chDB URL gets through — `jdbc:chdb:`, `jdbc:chdb::memory:` and a filesystem storage path
all fail identically, with `UnrecognizedDatabaseURLException`.

Both entry points fail in the same place: the `jdbc:shardingsphere:` driver with a YAML
configuration, and `ShardingSphereDataSourceFactory` handed an already-built HikariCP pool and
no rules at all. So there is no *configuration* route around it — no YAML key and no factory
argument avoids the URL parse.

There is a code route, and it is a large one: `DatabaseType` and `ConnectionPropertiesParser`
are both `ServiceLoader` SPIs, so a chDB-specific `DatabaseType` registered ahead of
`ClickHouseDatabaseType`, with a parser that tolerates an authority-less URL, would get past
this. That means shipping and versioning a ShardingSphere plugin against internal-ish SPIs
whose signatures move between minor releases, and it would be testing that plugin rather than
this driver, so it is not done here and is not recommended over waiting for the upstream fix.

Inventing a `//localhost/` to satisfy the parser is not a workaround either: the driver reads
the text after the prefix as the storage path, so it would open a database in a directory named
after a host that is not there.

`ShardingSphereIT` pins this rather than skipping it, so that the test starts failing when
ShardingSphere learns to parse an embedded URL.

What is consequently untested is ShardingSphere's own connection management against the
one-storage-path-per-JVM rule. The nearest thing that is tested is `HikariPoolIT` —
ShardingSphere's pools are HikariCP, and pool churn against the storage-path registry is exactly
what that covers.
