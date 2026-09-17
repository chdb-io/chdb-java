# Type parity with clickhouse-jdbc

A differential comparison against clickhouse-jdbc, what it found, and what was done about it.
`scripts/run-type-parity.sh` reproduces it; `tools/type-parity/` is the harness.

**Where it stands: 79 of 84 cases agree exactly** on both `getObject` and `getString`, and the
metadata agrees on all 65 types across all seven `ResultSetMetaData` fields bar thirteen
declared divergences. The five value cases that differ are listed at the end; each is the
reference driver being worse or a choice made deliberately.

---

## Method

Each case is a single expression — `SELECT toIPv4('192.168.1.1') AS v` — read through both
drivers, comparing `getColumnTypeName`, `getColumnType`, `getColumnClassName`, `getPrecision`,
`getScale`, `isSigned`, `isNullable`, then `getObject`, `getString`, `getBoolean`, `getInt`,
`getLong`, `getDouble`, `getBigDecimal`, `getBytes`, `getDate`, `getTime`, `getTimestamp` and
`wasNull`.

Three things make the comparison mean something.

**The server is `clickhouse/clickhouse-server:26.7.3`**, the version
`scripts/engine.properties` pins the engine to, so a difference is the driver's and not the
engine's.

**Both sides run in UTC.** chDB takes its timezone from the host and a container server takes
UTC; that alone produced five apparent `DateTime64` disagreements on the first run, which were
not driver differences at all.

**The cases are cross-checked against real table columns**, not only `SELECT` expressions,
because a `CAST` in a projection could in principle reach the wire format as something other
than a declared column of that type does — and the whole exercise would then be an artifact of
how the cases are written. It is not: 28 declared types, compared on all seven
`ResultSetMetaData` answers plus `getObject` and `getString`, read identically from a table
column and from the expression that produced the value. `TypeMatrixIT.tableColumnsAgreeWithExpressions` is that check, kept so it cannot quietly stop being true.

Exception *messages* are not compared — two drivers wording a refusal differently is not a
finding. What is compared is the shape of the answer: a value, a `SQLException`, or an unchecked
throw.

The case values come from clickhouse-jdbc's own `JdbcDataTypeTests` rather than from
imagination. Its test names are a list of things that have bitten them and were worth reading as
such: `testEnumZeroLikeValues`, `testDecimalTypesTruncateOnWriteAndRead`,
`testUnsignedIntegerTypes`, `testBinaryStringSupportGetObject`.

---

## What the first run found, and why

Four of 84 cases agreed. The cause was one thing in one place: the driver typed each column from
the Arrow schema, and ClickHouse's Arrow writer is a lossy projection of its own type system.
From `src/Processors/Formats/Impl/CHColumnToArrowColumn.cpp`:

| ClickHouse type | written as | collides with |
|---|---|---|
| `Enum8` / `Enum16` | plain `Int8` / `Int16` | `Int8` / `Int16` — **the labels are not in the stream at all** |
| `Int128`, `UInt128` | `FixedSizeBinary(16)` | `UUID`, `IPv6`, `FixedString(16)` |
| `Int256`, `UInt256` | `FixedSizeBinary(32)` | `FixedString(32)` |
| `IPv6` | `FixedSizeBinary(16)` | `UUID`, `Int128` |
| `IPv4` | `UInt32` | `UInt32` |
| `DateTime` | `UInt32` | `UInt32` |
| `Date` | `date32` | `Date32` |
| `FixedString` | fixed-size binary | `UUID` at width 16 |
| `Nullable(T)`, `LowCardinality(T)` | flag / dictionary on `T` | erased from the type name |
| `Interval*`, `SimpleAggregateFunction` | underlying `Int64` | `Int64` |
| `JSON`, `Dynamic` | `String` | `String` |

The only field metadata the writer emits is `PARQUET:field_id`, so nothing in the stream
distinguishes these. Reporting `UUID` for an `Int128` was the driver picking one of five types
that share a 16-byte representation.

The consequences were values that were silently wrong rather than refused: `Int128` read as a
`java.util.UUID`, `IPv4` as a `Long` of `3232235777`, `DateTime` as epoch seconds with every
temporal accessor throwing, `Enum` as its number where clickhouse-jdbc's own `testStringTypes`
asserts `getString` gives the label and `getInt` the number on the same column.

---

## What was done

The driver reads `RowBinaryWithNamesAndTypes` now, whose header names every type as the engine
declared it. `docs/type-mapping.md` describes the result; the short version is that every row of
the table above is fixed, and the answers are matched to clickhouse-jdbc rather than invented.

That was not the only option, and the others are worth recording because the choice was not
obvious:

1. **Read the header separately and label the Arrow columns with it.** `DESCRIBE (SELECT ...)`
   and `SELECT ... LIMIT 0` both return the exact types. Keeps the Arrow fast path; costs an
   extra analyze per statement and does not apply to every statement shape.
2. **Read a type-carrying format** — what was done. Full fidelity from one execution, at the
   cost of a per-type decoder to write and maintain and the loss of Arrow's zero-copy access.
3. **Have the engine carry the type in-band with the Arrow data** — the ClickHouse type name in
   each Arrow field's metadata, where `PARQUET:field_id` already goes, or a
   `chdb_result_column_type()` on the ABI. One execution, no extra analyze, fast path intact. A
   small upstream change, and still the best end state if the allocation cost of (2) ever
   matters more than it does.

Two things outside the value mapping came with it, because they had been justified by the Arrow
mapping's existence:

- **`getColumns()` reports a real `DATA_TYPE`.** It, `COLUMN_SIZE`, `DECIMAL_DIGITS` and
  `NULLABLE` were `OTHER` and zeroes, on the reasoning that mapping a type *name* here would be
  a second implementation of the Arrow mapping and the two would drift. There is one
  implementation now — `JdbcTypeMapping` — so this goes through it. `NULLABLE` comes from the
  parsed type rather than from `startsWith(type, 'Nullable(')`, which reported
  `LowCardinality(Nullable(String))` as NOT NULL.
- **`getArray()` returns the `java.sql.Array` that `getObject()` already returned.** It had
  refused outright, from when no Array column was readable, which left the driver contradicting
  its own `getColumnClassName`.

`RowBinaryWithNamesAndTypes` over `Native` for two reasons. It is what clickhouse-jdbc's own
jdbc-v2 reads, so behaviour parity is most directly achievable. And `Native` is a far larger
surface for an embedded reader: `NativeWriter` goes through
`serializeBinaryBulkWithMultipleStreams` with serialization-version settings negotiated from a
client revision, plus sparse-column handling, and there is no handshake here to negotiate with.

### What it cost

Live memory is unchanged. Measured on a four-million-row, ~430 MB result: live heap grows 10 KB,
so the streaming property holds. But RSS grows 683 MB against a 512 MB heap, because RowBinary
allocates — a `byte[]` per chunk and decoded objects per row — where Arrow handed out views onto
engine memory. RSS is therefore no longer a proxy for "streamed rather than materialized", which
one test had been using it as; it measures live heap instead.

Reusing the chunk buffer would cut the largest contributor without changing any answer.
Deliberately not done: it is an allocation-rate change with no behavioural evidence behind it
yet, and the point of this work was correctness.

---

## Where the two drivers still differ

### Values: five cases

| case | us | clickhouse-jdbc |
|---|---|---|
| `BFloat16` | `1.5` | the query fails in the driver |
| `IntervalDay` | `3` from `getObject`, `P3D` from `getString` | `getObject` throws; `getString` gives `P3D` |
| `Tuple` (×2) | `(1,'a')` from `getString` | `[Ljava.lang.Object;@1f2` — `String.valueOf` on an array |
| `JSON` | the text the engine sent | parsed into a `HashMap` |

The `JSON` one is the only real choice among them. Parsing to a `Map` loses the difference
between `1` and `"1"` and cannot be handed back to a query; the text can.

### Metadata: thirteen declared divergences

Two reasons only, and both are the reference being wrong by the specification rather than merely
different.

**`Float32` and `BFloat16` are `REAL`.** `REAL` is JDBC's single-precision code; `FLOAT` is
double precision.

**`getColumnClassName` names the class `getObject` actually returns.** The reference answers
`java.lang.Object` for `IPv4`, `IPv6`, `Map`, `Tuple` and `JSON`, and `java.sql.Array` for the
geometry types, while its own `getObject` hands back an `InetAddress`, a `Map`, an `Object[]` and
`double[]`. The specification defines the method as the class `getObject` manufactures.

`JdbcTypeMappingTest` carries the captured reference table and the divergence list together, so
an undeclared difference fails and a declared one that stopped happening fails too. Without the
second half a stale entry would quietly excuse the next accidental divergence.

### Where we are right and they are not

Recorded so that nobody later "fixes" us into matching them.

- **A SQL NULL through a temporal accessor is `null`.** `getDate`, `getTime` and `getTimestamp`
  on a NULL return null from us and throw from clickhouse-jdbc.
- **`getInt` on a `UInt64` above `Long.MAX_VALUE` throws.** They return `-1`. Silently wrapping
  is worse.
- **`getBytes` on a number is refused.** Neither driver's answer is useful, but `getBytes` is
  specified for binary columns and inventing a text encoding for it — which the old Arrow path
  did, returning the UTF-8 of the decimal text — makes an error look like data.
- **`BFloat16` and `Interval` have values.**

---

## What is still unreadable

`AggregateFunction` states: an opaque per-function blob with no documented layout. That fails the
row rather than the column, because a row-wise format offers no way to skip a value whose length
cannot be worked out. The error names the type and suggests `toString()`.

Everything else in the matrix reads, including `JSON`, `Dynamic`, `Variant`, the geometry types
and the composites. `Dynamic` needed ClickHouse's binary encoding of a data type, which
clickhouse-jdbc also implements — so accepting the gap would have left us behind the reference on
a type it handles.
