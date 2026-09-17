# What clickhouse-jdbc does differently

A differential run of 84 type cases through both drivers against the same engine version.
`scripts/run-type-parity.sh` reproduces it; `tools/type-parity/` is the harness.

## Method

Each case is a single expression — `SELECT toIPv4('192.168.1.1') AS v` — read through
chdb-jdbc and through clickhouse-jdbc 0.10.0, and every accessor a caller might reach for is
compared: `getColumnTypeName`, `getColumnType`, `getColumnClassName`, `getPrecision`,
`getScale`, `isSigned`, `isNullable`, then `getObject`, `getString`, `getBoolean`, `getInt`,
`getLong`, `getDouble`, `getBigDecimal`, `getBytes`, `getDate`, `getTime`, `getTimestamp` and
`wasNull`.

Two things make the comparison mean something:

- **The server is `clickhouse/clickhouse-server:26.7.3`**, the same version
  `scripts/engine.properties` pins the engine to. A difference is therefore the driver's, not
  the engine's.
- **Both sides run in UTC.** chDB takes its timezone from the host and a container server takes
  UTC; that alone produced five apparent `DateTime64` disagreements on the first run, which
  were not driver differences at all.

Exception *messages* are not compared — two drivers wording a refusal differently is not a
finding. What is compared is the shape of the answer: a value, a `SQLException`, or an
unchecked throw.

The case values are the boundary and awkward ones clickhouse-jdbc's own `JdbcDataTypeTests`
encodes. Its test names are a list of things that have bitten them, and they were worth
reading as such: `testEnumZeroLikeValues`, `testDecimalTypesTruncateOnWriteAndRead`,
`testUnsignedIntegerTypes`, `testBinaryStringSupportGetObject`.

Result: **4 of 84 cases identical**, 241 differing accessor answers.

**Cross-checked against real table columns**, not only `SELECT` expressions, because a `CAST`
in a projection could in principle lose the type before the Arrow conversion and that would
have made the whole thing an artifact. It does not: a `MergeTree` table declared with
`Enum8('a'=6,'b'=7)`, `IPv4`, `IPv6`, `DateTime`, `Int128`, `FixedString(6)`,
`Nullable(Int32)` and `Date` reads back exactly as the expression cases do.

---

## Why so much differs: the Arrow path is lossy, in the engine

chdb-jdbc types a column from the Arrow schema. clickhouse-jdbc reads the native protocol,
whose header carries the ClickHouse type as declared. The Arrow schema does not, and the loss
happens in ClickHouse's own writer — `src/Processors/Formats/Impl/CHColumnToArrowColumn.cpp`:

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

The only field metadata the writer emits is `PARQUET:field_id`. So **nothing in the stream
distinguishes these, and no amount of driver cleverness recovers them.** Reporting `UUID` for
an `Int128` is the driver picking one of several types that share a 16-byte binary
representation; it is a guess, and it happens to be the wrong one more often than the right
one.

This matters for how the findings below should be read: most of them are one bug, in one
place, with one fix.

---

## S1 — silently wrong values

No exception, wrong answer. These are the ones that corrupt results rather than failing.

| case | chdb-jdbc | clickhouse-jdbc |
|---|---|---|
| `Int128` / `UInt128` | `getObject` → `java.util.UUID`, `getString` → `ffffffff-ffff-…` | `BigInteger`, `170141183460469231731687303715884105727` |
| `Int256` / `UInt256` | `getObject` → `byte[]` little-endian, `getString` → hex | `BigInteger`, the decimal digits |
| `IPv4` | `getObject` → `Long` `3232235777` | `Inet4Address`, `192.168.1.1` |
| `IPv6` | `getObject` → `UUID` `20010db8-0000-…` | `Inet6Address`, `2001:db8:0:0:0:0:0:1` |
| `DateTime` | `getObject` → `Long` `1789605296`; `getDate`/`getTime`/`getTimestamp` all throw | `Timestamp 2026-09-17 12:34:56.0` |
| `Enum8('a'=6,'b'=7)` | `getString` → `7` | `getString` → `b`, `getInt` → `7` |
| `FixedString(6)` | `getObject` → `byte[]`, `getString` → `666978656400` | `fixed\0` |
| `Dynamic` holding `'s'` | `getString` → `"s"` — with the JSON quotes | `s` |
| `Float32`/`Float64` via `getLong` | saturates to `9223372036854775807` | throws |

Two of these deserve singling out.

**`DateTime` is the most used temporal type in ClickHouse** and it reads as a `Long`. Every
temporal accessor on it throws, because the driver believes the column is `UInt32`. An
application that does `rs.getTimestamp("event_time")` fails outright; one that does
`getObject` gets epoch seconds and no indication anything is wrong.

**`Enum` is the trap clickhouse-jdbc's own tests are built around.** `testStringTypes` asserts
both halves on the same column:

```java
assertEquals(rs.getString("enum"), "a");
assertEquals(rs.getInt("enum"), 6);
```

That dual behaviour is what a JDBC caller expects of an enumerated column, and
`testEnumZeroLikeValues` then pins the members they got wrong once — `'' = 0` and `'neg' = -5`.
We return the number from both accessors, and for the same reason we cannot do better: the
labels are not in the Arrow stream.

**`getLong` saturating** is a separate, smaller bug and not caused by Arrow. Returning
`Long.MAX_VALUE` for `3.4e38` is a wrong number presented as a right one; clickhouse-jdbc
refuses, and refusing is correct.

## S2 — JDBC conformance

**`getObject` returns `java.time` types where the specification says `java.sql`.** Five
`Instant` where `Timestamp` is required, three `LocalDate` where `Date` is, and `LocalTime`
where `Time` is. JDBC 4.2's mapping table is explicit: the `java.time` types are what
`getObject(int, Class)` is for, not `getObject(int)`. This breaks framework code that switches
on the returned type — Spring's `JdbcUtils.getResultSetValue` and anything doing
`instanceof java.sql.Timestamp` — and it is the same defect class as the `getObject(int,
Class)` leak fixed earlier.

**Unchecked exceptions from accessors.** `getDate`, `getTime` and `getTimestamp` on a `UInt64`
throw `ArithmeticException`, not `SQLException`. A caller's `catch (SQLException)` does not see
it. Six instances here; the pattern is what matters.

**`getPrecision` and `getScale` are 0 for everything temporal**, and wrong for several numeric
types (34 cases). `DateTime64(3)`: we say precision 0 scale 0, clickhouse-jdbc says 29 and 3.
`Date`: 0 against 10. The scale of a `DateTime64` is not even in our type name —
`DateTime64(UTC)` rather than `DateTime64(3)`.

**`isSigned` disagrees in 13 cases**, in both directions.

**`getColumnTypeName` returns `Unsupported(arrow=+l)`** for arrays and tuples. That string is a
diagnostic, not a type name, and it is what a GUI will put in a column header.

## S3 — deliberate V1 gaps, with one thing worth reconsidering

`Array`, `Tuple`, `Map`, `Nested` and the geo types are documented as unsupported and they
refuse cleanly with `SQLFeatureNotSupportedException`. That is the right shape for a gap.

But clickhouse-jdbc renders them as text through `getString`: `[1, 2, 3]`, `['a', 'b']`,
`[1, NULL, 3]`. A driver that cannot give a caller a `java.sql.Array` can still let a GUI or a
`SELECT *` dump show the value. Worth considering, since the Arrow list data is right there and
14 of the 66 "chdb refuses what clickhouse answers" rows are exactly this.

## S4 — differences where we are right, or better

Not everything that differs is ours to fix.

- **`Float32` → `REAL`.** We report `REAL`, clickhouse-jdbc reports `FLOAT`. `REAL` is the
  JDBC code for single precision; `FLOAT` is double. We are correct.
- **NULL through a temporal accessor.** `getDate`/`getTime`/`getTimestamp` on a NULL return
  `null` from us and throw from clickhouse-jdbc. A SQL NULL is `null` for every accessor; we
  are correct and they have a bug.
- **`Time64(3)`.** We return `LocalTime 12:34:56.789`; they return `java.sql.Time 12:34:56` and
  drop the milliseconds, because `java.sql.Time` cannot hold them. Our value is better even
  though our *type* is the non-conformant one — which is the tension in S2 rather than an
  argument against it.
- **`getInt` on `UInt64` max.** We throw `SQLDataException`; they return `-1`. Silently
  wrapping is worse.
- **`getBytes` on a number.** We return the UTF-8 of the decimal text; they throw "Column is
  not of array type". Neither is useful, but `getBytes` is specified for binary columns and
  inventing a text encoding for it is the weaker choice — ours.

---

## What would fix S1

The information is missing from the stream, so the fix has to put it there or fetch it
separately.

1. **Have chDB emit the ClickHouse type name in the Arrow field metadata.** The writer already
   attaches `PARQUET:field_id`; adding the declared type alongside it is a small, contained
   change in `CHColumnToArrowColumn.cpp`, and it fixes every row of the table above at once.
   This is the change to propose upstream, and it is the only one that recovers `Enum` labels.
2. **Or expose the result header through the C ABI.** `chdb_result` carries metrics but not
   column types. A `chdb_result_column_type(result, i)` would serve the driver without
   touching the Arrow path.
3. **Settings help only at the margin.** `chdb_arrow_options` today has
   `low_cardinality_as_dictionary`, `string_as_string` and `unsupported_as_binary`. There is no
   knob for `output_fixed_string_as_fixed_byte_array`, which is what makes `FixedString` arrive
   as binary — adding one would fix that single row without any of the others.

S2 is ours and needs no upstream anything: the `java.sql` return types, the unchecked
exceptions, precision and scale, and `getLong` saturation are all driver-side.
