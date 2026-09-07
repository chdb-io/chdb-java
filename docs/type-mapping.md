# Type mapping

## How types are decided

Not by parsing ClickHouse type names. Results arrive over the Arrow C Data Interface, and each
column's Arrow format string is what the driver maps. That means the mapping is decided by the
engine's own converter and cannot drift from it: `LowCardinality(String)` arrives as Arrow
`utf8` and is a `String` here without the driver knowing what LowCardinality is, `Nullable(T)`
arrives as `T` with the nullable flag set, and `Enum8` arrives as its underlying integer.

## Reading

| ClickHouse | Arrow | `getColumnType()` | `getObject()` returns | Also readable as |
|---|---|---|---|---|
| `Bool` | `b` | `BOOLEAN` | `Boolean` | `getInt` (0/1), `getString` (`"true"`/`"false"`) |
| `Int8` | `c` | `TINYINT` | `Byte` | any wider integer accessor |
| `Int16` | `s` | `SMALLINT` | `Short` | |
| `Int32` | `i` | `INTEGER` | `Integer` | |
| `Int64` | `l` | `BIGINT` | `Long` | |
| `UInt8` | `C` | `SMALLINT` | `Short` | `getInt`, `getLong` |
| `UInt16` | `S` | `INTEGER` | `Integer` | `getLong` |
| `UInt32` | `I` | `BIGINT` | `Long` | `getLong`; **not** `getInt` — see below |
| `UInt64` | `L` | `NUMERIC` | `BigInteger` | `getBigDecimal`, `getString`; `getLong` only below 2⁶³ |
| `Float32` | `f` | `REAL` | `Float` | `getDouble` |
| `Float64` | `g` | `DOUBLE` | `Double` | |
| `Decimal(P,S)` | `d:P,S[,bits]` | `NUMERIC` | `BigDecimal` | `getString` (plain notation) |
| `String` | `u` | `VARCHAR` | `String` | `getBytes` (UTF-8), `getCharacterStream` |
| `FixedString(N)` | `w:N` | `BINARY` | `byte[]` | `getString` (lowercase hex) |
| `UUID` | `w:16` | `BINARY` | `UUID` | `getString`, `getBytes` |
| `Date`, `Date32` | `tdD` | `DATE` | `LocalDate` | `getDate`, `getString` (ISO-8601) |
| `DateTime64(p)` | `tsX:` | `TIMESTAMP` | `Instant` | `getTimestamp`, `getLocalDateTime` via `getObject` |
| `DateTime64(p,'tz')` | `tsX:tz` | `TIMESTAMP_WITH_TIMEZONE` | `Instant` | as above |
| `Nullable(T)` | T's format | T's type | `null`, or T's value | every accessor; `wasNull()` |
| `LowCardinality(T)` | T's format | T's type | T's value | materialized, not dictionary-encoded |
| `Enum8`, `Enum16` | `c` / `s` | `TINYINT` / `SMALLINT` | `Byte` / `Short` | the underlying value; use `toString(col)` for the name |

`Bool` reads as `Boolean` for `getObject`; `getInt` gives 0 or 1, which is JDBC's numeric view
of a boolean.

### Types V1 will not read

`Array`, `Map`, `Tuple`, `Nested`, `Variant`, `Dynamic`, `JSON`, `AggregateFunction`, `Point`,
`Ring`, `Polygon`, `IPv4`, `IPv6` and anything else with no flat Arrow mapping.

`ResultSetMetaData.getColumnTypeName()` reports these as
`Unsupported(arrow=<format>)` and `getColumnType()` as `OTHER`, so a framework can see the
column exists. Reading one raises `SQLFeatureNotSupportedException` naming the column and the
workaround. It never returns a wrong value and never mis-slices the buffers.

The workaround is a cast in SQL:

```sql
SELECT toString(tags) AS tags,      -- Array(String)  -> "['a','b']"
       toString(attrs) AS attrs,    -- Map(String,..) -> "{'k':1}"
       toString(addr) AS addr       -- IPv6           -> "::1"
FROM events
```

`IPv4` and `IPv6` are worth calling out: they are common, and `toString()` gives exactly the
textual form you want.

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

| Property | Default | Effect |
|---|---|---|
| `stringAsString` | `true` | `String` columns as Arrow `utf8`. Set `false` for columns holding bytes that are not valid UTF-8; they then read as `byte[]`. |
| `unsupportedAsBinary` | `false` | Degrade `JSON`, `Dynamic` and `AggregateFunction` to binary instead of failing the query. The bytes are an engine-internal representation — `getBytes()` only. |
| `lowCardinalityAsDictionary` | `false` | Emit `LowCardinality` as an Arrow dictionary. V1 cannot read dictionary-encoded columns, so this makes them unreadable; it exists for diagnosis. |

```
jdbc:chdb:/data?unsupportedAsBinary=true
```

## `UUID` and `FixedString(16)`

The engine exports both as Arrow `w:16`, with nothing to tell them apart, and the public Arrow
options offer no way to change that. The driver reports `UUID`, because a UUID column is far
more common than a 16-byte `FixedString`.

If you have a `FixedString(16)`, nothing is lost: `getBytes()` returns the 16 raw bytes.
`getString()` will render them in UUID notation rather than hex, so use `getBytes()`, or
`hex(col)` in SQL.
