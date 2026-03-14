# Compatibility Contract

## Supported Versions

| Component | Minimum | Pinned in dev stack | Notes |
|-----------|---------|---------------------|-------|
| Metabase  | 59      | v0.59.2             | OSS; Enterprise builds same plugin ABI |
| Hasura    | 2.27.0  | 2.27.0              | Tested against the minimum to prove floor |
| PostgreSQL (backing Hasura) | 13 | 15 | Not user-facing; affects seed schema only |
| Java (plugin host) | 11 | 21 | Metabase embeds its own JVM |

## MVP Scope

The v0.1.0 release is **native-query-first and read-only**.

### Supported

- Plugin load and display in Metabase
- Connection form: endpoint, admin secret, bearer token, role, timeout
- Connection test (`Test connection` button)
- Schema discovery via GraphQL introspection (`/v1/graphql`)
- Optional enriched discovery via Metadata API (`/v1/metadata`) when accessible
- Metadata sync: sources, tables (tracked query fields), scalar fields
- Native GraphQL query execution
- Result flattening: root list-of-objects to tabular rows, nested objects as dot-path columns
- Stable identifiers across repeated syncs
- Readable error messages for auth, transport, validation, and timeout failures

### Explicitly Unsupported in MVP

| Feature | Failure behaviour | Test class |
|---------|-------------------|------------|
| Mutations | Reject at parse time with driver error | `execute-unsupported` |
| Subscriptions | Reject at parse time with driver error | `execute-unsupported` |
| Notebook query builder (MBQL) | Return `::driver/unsupported` | `mbql-unsupported` |
| Joins across objects | Return `::driver/unsupported` | `mbql-unsupported` |
| Per-user Hasura role mapping | Not supported; one role per connection | n/a |
| Schema-change operations (DDL) | Not applicable (read-only driver) | n/a |
| Uploads | Not applicable (read-only driver) | n/a |
| Arrays in response | Serialised as JSON string; not expanded | `execute-arrays` |

## Discovery Modes

The driver supports two discovery paths and must work with either:

1. **Introspection-only** — GraphQL introspection on `/v1/graphql`. Always available if the endpoint is reachable.
2. **Metadata-enriched** — `export_metadata` via `/v1/metadata`. Requires admin credentials and enabled Metadata API. Optional; falls back to introspection-only automatically.

## Auth Modes Tested

- Admin secret (`X-Hasura-Admin-Secret`)
- Bearer token (`Authorization: Bearer ...`)
- Unauthenticated (open Hasura, test only)
- Role header (`X-Hasura-Role`)

## Flattening Rules (Native Query)

1. Response root field must be a list of objects; otherwise an error is returned.
2. Each top-level object becomes one row.
3. Nested objects are flattened to dot-path column names: `author.name`.
4. Arrays inside objects are serialised to a JSON string and stored as a text column.
5. Null values are preserved as SQL NULL.
6. Aggregate responses (`_aggregate` shapes) expose scalar fields as columns.

## Versioning

This driver uses `major.minor.patch` where:

- `major` increments on breaking changes to connection properties or discovery model.
- `minor` increments on new capabilities within the compatibility range.
- `patch` increments on bug fixes and dependency updates.
