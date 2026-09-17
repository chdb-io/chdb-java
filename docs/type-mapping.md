# Type mapping

## How types are decided

By the type the engine declares. Results arrive as `RowBinaryWithNamesAndTypes`, whose header
names every column's type exactly as ClickHouse would print it —
`Enum8('a' = 6, 'b' = 7)`, `Nullable(Int32)`, `DateTime64(3, 'UTC')` — and `ClickHouseType`
parses that into what decoding and `ResultSetMetaData` need.

This replaced reading over the Arrow C Data Interface, and the reason is worth recording because
the old design was defensible and still wrong. Arrow looked like the safer choice: the mapping
was made by the engine's own converter, so it could not drift from it. But that converter is a
*lossy* projection of ClickHouse's type system, and the loss is not recoverable downstream —
`CHColumnToArrowColumn.cpp` writes `Enum8` as a plain `Int8` with the labels nowhere in the
stream, `Int128`, `IPv6` and `UUID` all as sixteen bytes of fixed-size binary, `DateTime` as a
`UInt32`, and it attaches no ClickHouse type name to the field. So an `Int128` read back as a
`java.util.UUID`, an `IPv4` as a `Long`, and every temporal accessor on a `DateTime` threw. The
mapping could not drift from the engine, and was wrong anyway.

The answers below are matched to clickhouse-jdbc, captured by reading the same expressions
through it against the same engine version: a column's reported type should not change for an
application moving between the two drivers. `docs/type-parity-clickhouse-jdbc.md` records the
comparison, including the handful of places we differ on purpose and why.

## Reading

Precision and scale are what `ResultSetMetaData` reports.

| ClickHouse | `getColumnType()` | `getObject()` returns | Precision | Scale |
|---|---|---|---|---|
| `Bool` | `BOOLEAN` | `Boolean` | 1 | 0 |
| `Int8` | `TINYINT` | `Byte` | 3 | 0 |
| `Int16` | `SMALLINT` | `Short` | 5 | 0 |
| `Int32` | `INTEGER` | `Integer` | 10 | 0 |
| `Int64` | `BIGINT` | `Long` | 19 | 0 |
| `Int128` | `NUMERIC` | `BigInteger` | 39 | 0 |
| `Int256` | `NUMERIC` | `BigInteger` | 77 | 0 |
| `UInt8` | `SMALLINT` | `Short` | 3 | 0 |
| `UInt16` | `INTEGER` | `Integer` | 5 | 0 |
| `UInt32` | `BIGINT` | `Long` | 10 | 0 |
| `UInt64` | `NUMERIC` | `BigInteger` | 20 | 0 |
| `UInt128` | `NUMERIC` | `BigInteger` | 39 | 0 |
| `UInt256` | `NUMERIC` | `BigInteger` | 78 | 0 |
| `Float32` | `REAL` | `Float` | 12 | 0 |
| `Float64` | `DOUBLE` | `Double` | 22 | 0 |
| `BFloat16` | `REAL` | `Float` | 3 | 0 |
| `Decimal(18, 4)` | `DECIMAL` | `BigDecimal` | 18 | 4 |
| `String` | `VARCHAR` | `String` | 0 | 0 |
| `FixedString(6)` | `VARCHAR` | `String` | 6 | 0 |
| `Enum8('a' = 1)` | `VARCHAR` | `String` | 0 | 0 |
| `Enum16('a' = 1)` | `VARCHAR` | `String` | 0 | 0 |
| `UUID` | `OTHER` | `UUID` | 69 | 0 |
| `IPv4` | `OTHER` | `InetAddress` | 10 | 0 |
| `IPv6` | `OTHER` | `InetAddress` | 39 | 0 |
| `Date` | `DATE` | `Date` | 10 | 0 |
| `Date32` | `DATE` | `Date` | 10 | 0 |
| `DateTime` | `TIMESTAMP` | `Timestamp` | 29 | 0 |
| `DateTime('UTC')` | `TIMESTAMP` | `Timestamp` | 29 | 0 |
| `DateTime64(3)` | `TIMESTAMP` | `Timestamp` | 29 | 3 |
| `Time` | `TIME` | `Time` | 9 | 0 |
| `Time64(3)` | `TIME` | `Time` | 9 | 3 |
| `IntervalDay` | `BIGINT` | `Long` | 19 | 0 |
| `Nullable(Int32)` | `INTEGER` | `Integer` | 10 | 0 |
| `LowCardinality(String)` | `VARCHAR` | `String` | 0 | 0 |
| `Array(Int32)` | `ARRAY` | `Array` | 0 | 0 |
| `Tuple(a Int32)` | `OTHER` | `[Ljava.lang.Object;` | 0 | 0 |
| `Map(String, Int32)` | `OTHER` | `Map` | 0 | 0 |
| `Nested(a Int32)` | `OTHER` | `[Ljava.lang.Object;` | 0 | 0 |
| `JSON` | `OTHER` | `String` | 0 | 0 |
| `Dynamic` | `OTHER` | `Object` | 0 | 0 |
| `Variant(Int64, String)` | `OTHER` | `Object` | 0 | 0 |
| `Point` | `ARRAY` | `[D` | 0 | 0 |
| `Ring` | `ARRAY` | `[[D` | 0 | 0 |
| `LineString` | `ARRAY` | `[[D` | 0 | 0 |
| `Polygon` | `ARRAY` | `[[[D` | 0 | 0 |
| `MultiPolygon` | `ARRAY` | `[[[[D` | 0 | 0 |
| `SimpleAggregateFunction(sum, Int64)` | `OTHER` | `Object` | 0 | 0 |
| `AggregateFunction(quantile(0.5), UInt64)` | `OTHER` | `Object` | 0 | 0 |

`getString` is defined for every readable type. An `Enum` gives its label, an address gives its
text form, a composite gives ClickHouse's own rendering — `[1, 2, 3]`, `['a', 'b']` with string
elements quoted, `[1, NULL, 3]` with an absent element uppercase, `(1.0,2.0)` for a `Point` — and
a timestamp gives exactly as many fractional digits as its scale, or none when the value has no
fraction.

`getObject(column, Class)` reaches the `java.time` types, `BigInteger`, `InetAddress`, `UUID`
and `byte[]` where the plain `getObject` returns the `java.sql` type the specification requires.

An `AggregateFunction` state cannot be decoded: it is an opaque per-function blob. That fails
the whole row rather than the column, because a row-wise format offers no way to skip a value
whose length cannot be worked out. Cast it in the query.


## Numeric range: reads that refuse rather than truncate

An accessor too narrow for the value throws `SQLException` with SQLSTATE `22003` instead of
wrapping:

```java
// UInt32 column holding 4294967295
rs.getInt(1);    // SQLException 22003: does not fit a Java int
rs.getLong(1);   // 4294967295

// UInt64 column holding 18446744073709551615
rs.getLong(1);        // SQLException 22003
rs.getObject(1);      // BigInteger
rs.getBigDecimal(1);  // BigDecimal
rs.getString(1);      // "18446744073709551615"
```

Silently wrapping a `UInt32` of 4 billion to a negative `int` is the kind of corruption that
surfaces as a business bug months later, so the read fails and names the value and an accessor
that holds it.

## Dates and times

**`LocalDate` and `Instant` are the exact types.** `getObject` returns them, and they need no
timezone to be correct.

**`java.sql.Timestamp` needs a zone**, because it is a zoneless wall-clock type. The rules, in
order:

1. the `Calendar`'s zone, if you pass one to `getTimestamp(int, Calendar)`;
2. the zone the column is tagged with, for `DateTime64(p, 'tz')`;
3. **UTC**.

UTC rather than the JVM default is deliberate. A default-zone fallback makes the same query
return different instants on a developer's laptop and a UTC server, which is a bug that only
shows up in production.

```java
// DateTime64(0, 'UTC') holding 2026-09-08 12:00:00Z
rs.getObject("t");                    // 2026-09-08T12:00:00Z  (Instant, unambiguous)
rs.getTimestamp("t");                 // 2026-09-08 12:00:00.0 (the column's zone: UTC)
rs.getTimestamp("t", tokyoCalendar);  // 2026-09-08 21:00:00.0
```

For a `DateTime64` with no timezone the driver cannot know what the values mean; it reads them
as UTC. If that matters, put the zone in the SQL:
`SELECT toDateTime64(col, 3, 'Europe/Berlin')`.

Pre-epoch sub-second values are handled with floor division, so a microsecond timestamp of −1
is 1 µs before the epoch rather than landing in second 0.

## Writing: `PreparedStatement` parameters

Values are bound by the engine, never spliced into SQL. The placeholder is always
`String`-typed and the engine converts from text in the surrounding expression, so the SQL says
what the value should become:

```java
// Ordinary comparison: no cast needed, ClickHouse compares across types.
PreparedStatement p = c.prepareStatement("SELECT * FROM t WHERE name = ?");
p.setString(1, name);

// Where the target type matters, say so in the SQL.
PreparedStatement q = c.prepareStatement(
    "INSERT INTO t VALUES (toUInt32(?), ?, toDateTime64(?, 3, 'UTC'))");
q.setInt(1, id);
q.setString(2, name);
q.setTimestamp(3, timestamp);
```

| Java setter | Bound as | Notes |
|---|---|---|
| `setBoolean` | `1` / `0` | |
| `setByte`, `setShort`, `setInt`, `setLong` | decimal digits | |
| `setFloat`, `setDouble` | decimal, or `nan` / `inf` / `-inf` | ClickHouse's spelling, not Java's |
| `setBigDecimal` | plain notation | never scientific, which ClickHouse rejects |
| `setString`, `setNString` | the text, escaped for the wire | quotes, backslashes, newlines, NUL and astral characters all round-trip |
| `setBytes` | lowercase hex | wrap the placeholder: `unhex(?)` |
| `setDate` | `YYYY-MM-DD` | |
| `setTime` | `HH:MM:SS` | |
| `setTimestamp` | `YYYY-MM-DD HH:MM:SS[.fffffffff]` | UTC unless given a `Calendar` |
| `setNull` | SQL NULL | the declared `sqlType` is not needed and is ignored |
| `setObject` | per runtime type | `String`, boxed primitives, `BigDecimal`, `BigInteger`, `byte[]`, `java.sql` and `java.time` temporals, `UUID`, `Character` |

`setObject` with any other type raises `SQLFeatureNotSupportedException` naming the class,
rather than falling back to `toString()` and writing something you did not intend.

### Binary parameters

Bound as hex, because the value channel is text. Decode in SQL:

```java
PreparedStatement p = c.prepareStatement("INSERT INTO t VALUES (unhex(?))");
p.setBytes(1, payload);
```

### Arrays

Reading one gives a `java.sql.Array`, from `getObject` and from `getArray` alike;
`getBaseTypeName()` is the element's ClickHouse type name and `getArray()` its decoded elements.

There is no `setArray`. Build the array in SQL from a text parameter:

```java
PreparedStatement p = c.prepareStatement(
    "SELECT * FROM t WHERE id IN (SELECT toUInt32(x) FROM (SELECT splitByChar(',', ?) AS a)"
    + " ARRAY JOIN a AS x)");
p.setString(1, "1,2,3");
```

### Why values are escaped, and why that is not quoting

The engine reads a bound value with `deserializeTextEscaped` — TSV field syntax — so a
backslash starts an escape sequence and a raw newline ends the field. Without encoding, a value
containing a backslash would arrive altered and one containing a newline would fail the query.
The driver encodes accordingly.

This is a wire encoding for the value channel, not SQL quoting, and it is not what makes
parameter binding safe. What makes it safe is that the value never enters the statement text:
the engine has finished parsing before it sees one, so the worst a hostile value can do is be a
wrong value. See [upstream findings §4](upstream-findings.md).

## Driver properties affecting types

None any more. `stringAsString`, `unsupportedAsBinary` and `lowCardinalityAsDictionary`
configured the Arrow export and are accepted and ignored; `docs/unsupported.md` says why they
are still accepted rather than rejected.

One session setting is applied on your behalf and cannot be turned off:
`output_format_binary_write_json_as_string`. A `JSON` column is written either as a
length-prefixed string or in its own structured binary form depending on it, and the header says
`JSON` either way — so a decoder that guessed would read one as the other and return plausible
rubbish. Turning it off would make `JSON` columns unreadable, so it is not offered.

## `UUID` and `FixedString(16)`

No longer ambiguous. Over Arrow both were sixteen bytes of fixed-size binary, along with
`Int128`, `UInt128` and `IPv6`, and the driver had to guess — it guessed `UUID`, which was right
for one of the five. The stream's header says which it is.
