# Release Notes

## v0.1.0

First public release of the Hasura Metabase community driver.

### Compatibility

| Component  | Minimum | Tested against |
|------------|---------|----------------|
| Metabase   | 59      | v0.59.2        |
| Hasura     | 2.27.0  | 2.27.0         |
| PostgreSQL | 13      | 15             |
| Java       | 11      | 21             |

### What's included

**Connection**
- Connection form with endpoint, admin secret, bearer token, role, and timeout fields
- `Test connection` support via a minimal GraphQL introspection ping
- Clear error messages for auth failures, unreachable endpoints, and timeouts

**Schema discovery and sync**
- Schema discovery via GraphQL introspection (`/v1/graphql`)
- Optional metadata enrichment via Hasura Metadata API (`export_metadata`)
- Automatic fallback to introspection-only when Metadata API is unavailable or unauthorized
- Stable table and field identifiers across repeated syncs
- Type mapping for common Hasura / Postgres-backed GraphQL scalars

**Native GraphQL execution**
- Execute native GraphQL queries directly against Hasura
- Reject mutations and subscriptions before any HTTP call
- Flatten root list-of-objects or aggregate objects into tabular rows
- Nested objects become dot-path columns (`author.name`)
- Arrays serialized as JSON strings

**Query builder (MBQL)**
- Single-table select with column selection
- Filters: `=`, `!=`, `<`, `>`, `<=`, `>=`, `is-null`, `not-null`, `contains`, `starts-with`, `and`, `or`, `not`
- Order-by (one or more columns, `asc` / `desc`)
- `limit` and `offset` pagination
- Aggregates: `count`, `sum`, `avg`, `min`, `max`
- Immediate rejection of joins, expressions, and breakout with clear error messages

### Known limitations

- Native queries do not support GraphQL variables
- Query builder does not support joins, custom expressions, or breakout / group-by
- Arrays in GraphQL responses are serialized as strings, not expanded into rows
- One role per connection; per-user role mapping is not supported
- Partial GraphQL responses (data and errors coexisting) are treated as failures

### Installation

1. Download `hasura-metabase-driver.jar` from the release assets.
2. Copy the JAR into your Metabase plugins directory.
3. Restart Metabase.
4. Add a new database, select `Hasura`, and fill in your connection details.

See [README.md](README.md) for full setup instructions and
[COMPATIBILITY.md](COMPATIBILITY.md) for the detailed compatibility contract.
