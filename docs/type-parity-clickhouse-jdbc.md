# Type parity with clickhouse-jdbc

A differential comparison against clickhouse-jdbc, what it found, and what was done about it.
`scripts/run-type-parity.sh` reproduces it; `tools/type-parity/` is the harness.

**Where it stands**, from the run of 2026-09-18 against server 26.7.3.19 — the numbers depend on
which question you ask, so all three:

| measure | result |
|---|---|
| cases identical on **every** compared answer | 36 of 84 |
| cases identical on `getObject` **and** `getString` | 79 of 84 |
| differing answers, over ~19 accessors × 84 cases | 72 |

The first two are both here because neither alone is honest: the strict one counts a case as
differing when only `getColumnClassName` does, and the other treats two `java.sql.Array`s with
different `toString` output as agreeing, which they are.

Of the 72 differing answers, **28 are the reference refusing what we answer**, **14 are us
refusing what it answers**, **1 is a query that fails there**, and **29 are both answering
differently**. The matrix below has all of them, grouped by why.

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

## The behaviour matrix

Every difference, and why. "Us" is chdb-jdbc.

### Where the reference gives a wrong answer rather than an error

| case | accessor | us | reference | why we are right |
|---|---|---|---|---|
| `UInt64`, `Int128`, `Int256`, `UInt128`, `UInt256` at max | `getInt` | throws `22003` | `-1` | The value does not fit 32 bits. They let it overflow, and `-1` looks like data. JDBC says throw. |
| `Float64` `nan` | `getInt` | throws `22003` | `0` | NaN is not zero. |
| `Float32` smallest normal | `getBoolean` | `true` | `false` | JDBC is "zero is false, non-zero is true". `1.17e-38` is not zero. |
| `String` with invalid UTF-8 | `getBytes` | the 16 stored bytes | 34 bytes | A ClickHouse `String` is arbitrary bytes. They decode to a `String` and re-encode, so each invalid byte becomes `EF BF BD` and both length and content change. Silent data loss. |
| `Tuple`, `Tuple named` | `getString` | `(1,'a')` | `[Ljava.lang.Object;@10163d6` | They hand a Java array to `String.valueOf`. |
| `Nullable(*)` holding NULL | `getDate`, `getTime`, `getTimestamp` | `null` | throws | JDBC requires `null` for SQL NULL. |
| `Date`, `Date32` | `getTimestamp` | midnight that day | throws | JDBC's conversion table allows DATE to Timestamp. |
| `Decimal32`, `Decimal64`, `Float32` | `getLong` | truncates | throws | JDBC allows the conversion. Refusing a legal one is its own error. |
| `IntervalDay` | every accessor but `getString` | `3` | throws | It is a number of days. |
| `Enum8`, `Enum16` | `getBigDecimal` | the underlying number | throws | An Enum stores a number. |
| `BFloat16` | the query | reads | **fails in the driver** | They cannot read the type. |
| `Float32`, `BFloat16` | `getColumnType` | `REAL` | `FLOAT` | `REAL` is JDBC's single-precision code. |
| `IPv4`, `IPv6`, `Map`, `Tuple`, `JSON`, geometry | `getColumnClassName` | the class `getObject` returns | `Object`, or `java.sql.Array` for geometry | The specification defines the method as the class `getObject` manufactures. Their own `getObject` returns `InetAddress`, `Map`, `Object[]`, `double[]`. |

### Where we are stricter, and mean to be

| case | accessor | us | reference | why |
|---|---|---|---|---|
| `String`, `LowCardinality(String)`, `Dynamic` holding text | `getBoolean` | throws `22018` | `false` | Calling `"hello"` false is a guess. An empty string is arguable; `"hello"` is not. |
| `Array` | `getBytes` | throws | empty `byte[]` | An array has no byte form. |
| any number | `getBytes` | throws | throws | Agreed. Noted because the Arrow path used to return the UTF-8 of the decimal text. |

### Where both are defensible, and we follow the reference

| case | accessor | both | note |
|---|---|---|---|
| `Float64` `nan` | `getBoolean` | `false` | JDBC settles zero, not NaN. Followed the reference rather than reason from "not zero". |
| `IPv4`, `IPv6` | `getBytes` | the address bytes | 4, 16, or 4 for a v4-mapped IPv6. An address is naturally bytes, so refusing was over-strict. |
| `Enum8`, `Enum16` | `getObject`, `getInt` | the underlying number | `toString(col)` in SQL is how to ask for the label. |

### Where the difference is ours, deliberately

| case | accessor | us | reference | why |
|---|---|---|---|---|
| `Float32` | `getBigDecimal` | `3.4028234663852886E+38` | `3.4028235E+38` | Theirs is `new BigDecimal(Float.toString(f))`, which reads better and is **not stable across JDKs**: `Float.toString` changed algorithm in [JDK 19][jdk19], so `Float.MIN_NORMAL` renders differently on Java 11 and on Java 21. Matching them would inherit that. See below. |
| `JSON` | `getObject`, `getString` | the text the engine sent | a `HashMap` | Parsing to a `Map` loses the difference between `1` and `"1"` and cannot be handed back to a query; the text can. Also the only alignment that would cost a hand-written JSON parser in a driver with no dependencies. |

### Not differences

Seven answers the harness reports as differing are the same value printed differently: our
`java.sql.Array.toString()` gives `[1, 2, 3]` where theirs gives an identity hash, and an empty
`Map` is a `LinkedHashMap` here and an `EmptyMap` there. Both equal `{}`.

`getColumnDisplaySize` is `80` for every type in both drivers. JDBC asks for a maximum character
width, which for `Int32` would be 11, so both are uninformative — but it is a constant with no
environment dependence, and the captured reference table confirms all 65 types.

[jdk19]: https://bugs.openjdk.org/browse/JDK-4511638

`JdbcTypeMappingTest` holds the captured reference table and the declared divergences together:
an undeclared difference fails, and so does a declared one that stopped happening.

### The rule we apply

Align when the reference's answer is **more correct**, not when it is more attractive. Never
align if doing so introduces a dependence on the environment -- JDK version, time zone, locale,
platform.


## What the re-run found, after all of the above

The harness has a category for "both refuse, but chdb-jdbc throws an **unchecked** exception",
and on 2026-09-18 it had three entries: `getBigDecimal` on `Float64` `inf`, `-inf` and `nan`
threw `NumberFormatException` out of the accessor. `BigDecimal.valueOf(double)` formats the
double and then parses the text, so the throw came from inside the JDK and no amount of
`SQLException` discipline in this driver would have caught it — `SqlStateTypeTest` checks the
SQLSTATE of exceptions that are thrown as `SQLException`, and this one never was.

That is the exact defect class the move off Arrow existed to remove, found only because the
comparison asks a question no unit test here asked. It is now a `SQLDataException` with SQLSTATE
`22003`, and `JdbcValuesTest.bigDecimalHasNoInfinity` pins it.

### And one fix that had to be reverted

`getBigDecimal` on a `Float32` also looked wrong — seventeen digits for a value carrying seven
— so it was changed to the reference's `new BigDecimal(Float.toString(f))`. CI rejected that on
Java 11 and 17. Measured:

| | Java 11 | Java 21 |
|---|---|---|
| `Float.toString(MIN_NORMAL)` | `1.17549435E-38` | `1.1754944E-38` |
| `BigDecimal.valueOf((double) MIN_NORMAL)` | `1.1754943508222875E-38` | same |

Answering differently per JVM is worse than answering with extra digits, and invisible to anyone
on one JDK. Reverted; `JdbcValuesTest.float32BigDecimalIsTheSameOnEveryJdk` pins the stable value
and CI's five JDKs are what make it an assertion.

After the fix that stuck: category E is empty and the strict measure moved from 34 to 36.
Then following the reference on the two defensible cases took the differing answers 76 to 72.

---

## What is still unreadable

`AggregateFunction` states: an opaque per-function blob with no documented layout. That fails the
row rather than the column, because a row-wise format offers no way to skip a value whose length
cannot be worked out. The error names the type and suggests `toString()`.

Everything else in the matrix reads, including `JSON`, `Dynamic`, `Variant`, the geometry types
and the composites. `Dynamic` needed ClickHouse's binary encoding of a data type, which
clickhouse-jdbc also implements — so accepting the gap would have left us behind the reference on
a type it handles.
