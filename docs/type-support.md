# Type support

Statuses are `Yes` (implemented with byte level and integration tests against real servers),
`Planned (phase)`, or `No`. Reading covers uncompressed SELECT results and writing covers native
INSERT blocks; both directions are round trip tested.

Columns: Parse (type name parsing), Read (decode from native blocks), Write (encode into native
blocks), JDBC (mapping in the JDBC adapter), Object (row object mapping).

| Type | Parse | Read | Write | JDBC | Object | Notes |
|---|---|---|---|---|---|---|
| UInt8, UInt16, UInt32 | Yes | Yes | Yes | Planned (7) | Planned (7) | Widened lossless Java types |
| UInt64 | Yes | Yes | Yes | Planned (7) | Planned (7) | `long` with unsigned semantics plus `BigInteger` path |
| UInt128, UInt256 | Yes | Yes | Yes | Planned (7) | Planned (7) | `BigInteger` |
| Int8, Int16, Int32, Int64 | Yes | Yes | Yes | Planned (7) | Planned (7) | |
| Int128, Int256 | Yes | Yes | Yes | Planned (7) | Planned (7) | `BigInteger` |
| Float32, Float64 | Yes | Yes | Yes | Planned (7) | Planned (7) | |
| BFloat16 | Yes | Yes | Yes | Planned (7) | Planned (7) | `float` carrier |
| Bool | Yes | Yes | Yes | Planned (7) | Planned (7) | |
| String | Yes | Yes | Yes | Planned (7) | Planned (7) | `String` plus raw byte access |
| FixedString(N) | Yes | Yes | Yes | Planned (7) | Planned (7) | |
| Date, Date32 | Yes | Yes | Yes | Planned (7) | Planned (7) | `LocalDate` |
| DateTime, DateTime64 | Yes | Yes | Yes | Planned (7) | Planned (7) | `Instant`; timezone metadata retained separately |
| Time, Time64 | No (explicit rejection) | No | Planned (4) | Planned (7) | Planned (7) | Recently introduced upstream; scheduled alongside Phase 4 |
| Interval* | Yes | Yes | Yes | No | Planned (7) | SELECT only upstream |
| UUID | Yes | Yes | Yes | Planned (7) | Planned (7) | `java.util.UUID` |
| IPv4, IPv6 | Yes | Yes | Yes | Planned (7) | Planned (7) | Dedicated value types |
| Enum8, Enum16 | Yes | Yes | Yes | Planned (7) | Planned (7) | Quoted label parsing |
| Decimal(P,S) family | Yes | Yes | Yes | Planned (7) | Planned (7) | `BigDecimal` |
| Nothing | Yes | Yes | No | No | No | |
| Nullable(T) | Yes | Yes | Yes | Planned (7) | Planned (7) | |
| Array(T) | Yes | Yes | Yes | Planned (7) | Planned (7) | Offset validation |
| Tuple(...), named tuples | Yes | Yes | Yes | Planned (7) | Planned (7) | Immutable typed tuple |
| Map(K, V) | Yes | Yes | Yes | Planned (7) | Planned (7) | Wire order preserved |
| Nested | Yes | No (flattened by the server in results) | No (insert via the flattened Array subcolumns) | No | Planned (7) | |
| LowCardinality(T) | Yes | Yes | Yes | Planned (7) | Planned (7) | Dictionary and index validation; NULL in slot zero for nullable inner types |
| SimpleAggregateFunction aliases | Yes | Yes (as the inner type) | Yes (as the inner type) | No | No | Alias to inner type |
| AggregateFunction states | Yes | No (explicit rejection) | No | No | No | Opaque representation or explicit rejection |
| Geometry aliases (Point, Ring, Polygon, MultiPolygon, LineString, MultiLineString) | Yes | Yes (as their native tuple and array shapes) | Yes (as their native shapes) | No | No | Native representations of nested types |
| Variant | Yes | Yes | No (write the concrete type) | No | Planned (8) | Basic and compact discriminator modes; discriminator validation |
| Dynamic | Yes | Yes | No (write a concrete type) | No | Planned (8) | V1 and V2 structure; shared variant rows fail explicitly on access, raw bytes exposed |
| JSON | Yes | Yes | No (explicit rejection) | No | Planned (8) | V1 and V2 object serialisation; typed and dynamic paths decoded, shared data as raw values |
| QBit and newly introduced types | No (explicit rejection) | No | No | No | No | Explicit `UnsupportedClickHouseTypeException` planned rather than guessing |

Rules that govern this table:

- A cell only becomes `Yes` together with encode and decode round trip tests and integration
  coverage against real servers.
- CHord never guesses the size of an unsupported value and keeps reading; unsupported types fail
  explicitly at the type parsing layer.
- The type name parser handles nested parentheses, quoted enum labels, escaped strings, named
  and back quoted tuple elements, timezone parameters, precision and scale, whitespace variants,
  geometry aliases and malformed input, with configurable nesting and size limits, and is
  round trip property tested.
- Columns using custom serialisation metadata (revision 54454) decode for the default and
  sparse kinds, sparse columns materialising into full columns; the detached, replicated and
  combination kind stacks are inter server forms that fail explicitly before any value bytes are
  consumed.
