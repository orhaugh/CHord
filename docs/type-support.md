# Type support

Statuses are `Yes` (implemented with byte level and integration tests against real servers),
`Planned (phase)`, or `No`. Reading covers uncompressed SELECT results; writing (INSERT) arrives
in Phase 3.

Columns: Parse (type name parsing), Read (decode from native blocks), Write (encode into native
blocks), JDBC (mapping in the JDBC adapter), Object (row object mapping).

| Type | Parse | Read | Write | JDBC | Object | Notes |
|---|---|---|---|---|---|---|
| UInt8, UInt16, UInt32 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | Widened lossless Java types |
| UInt64 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | `long` with unsigned semantics plus `BigInteger` path |
| UInt128, UInt256 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | `BigInteger` |
| Int8, Int16, Int32, Int64 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | |
| Int128, Int256 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | `BigInteger` |
| Float32, Float64 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | |
| BFloat16 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | `float` carrier |
| Bool | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | |
| String | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | `String` plus raw byte access |
| FixedString(N) | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | |
| Date, Date32 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | `LocalDate` |
| DateTime, DateTime64 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | `Instant`; timezone metadata retained separately |
| Time, Time64 | No (explicit rejection) | No | Planned (3) | Planned (7) | Planned (7) | Recently introduced upstream; scheduled with Phase 3 |
| Interval* | Yes | Yes | Planned (3) | No | Planned (7) | SELECT only upstream |
| UUID | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | `java.util.UUID` |
| IPv4, IPv6 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | Dedicated value types |
| Enum8, Enum16 | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | Quoted label parsing |
| Decimal(P,S) family | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | `BigDecimal` |
| Nothing | Yes | Yes | No | No | No | |
| Nullable(T) | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | |
| Array(T) | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | Offset validation |
| Tuple(...), named tuples | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | Immutable typed tuple |
| Map(K, V) | Yes | Yes | Planned (3) | Planned (7) | Planned (7) | Wire order preserved |
| Nested | Yes | No (flattened by the server in results) | Planned (3) | No | Planned (7) | |
| LowCardinality(T) | Yes | Planned (6) | Planned (6) | Planned (7) | Planned (7) | Dictionary index validation |
| SimpleAggregateFunction aliases | Yes | Yes (as the inner type) | Planned (6) | No | No | Alias to inner type |
| AggregateFunction states | Yes | No (explicit rejection) | No | No | No | Opaque representation or explicit rejection |
| Geometry aliases (Point, Ring, Polygon, MultiPolygon, LineString, MultiLineString) | Yes | Yes (as their native tuple and array shapes) | Planned (6) | No | No | Native representations of nested types |
| Variant | Yes | Planned (6) | Planned (6) | No | Planned (8) | Discriminator validation |
| Dynamic | Yes | Planned (6) | Planned (6) | No | Planned (8) | V2 serialisation |
| JSON | Yes | Planned (6) | Planned (6) | No | Planned (8) | Documented serialisation modes only |
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
- Columns using custom serialisation metadata (sparse and related forms, revision 54454) are
  rejected explicitly before any value bytes are consumed, until Phase 6.
