# Hasura Metabase Connector

Hasura Metabase Connector is a community Metabase driver for Hasura 2.x.
It is implemented as a non-JDBC Clojure plugin that talks to Hasura over HTTP,
discovers schema via GraphQL introspection and the Metadata API, and exposes
Hasura data to Metabase as a read-only, native-query-first data source.

The repository contains a complete v0.1.0 driver implementation: connection
testing, schema discovery and sync, native GraphQL execution, and a limited
MBQL query-builder subset. See [PLAN.md](PLAN.md) for the full roadmap.

## Project Status

### Current state of the codebase

- Plugin packaging as a Metabase community driver JAR
- Driver registration and display in Metabase as `Hasura`
- Connection configuration and `Test connection` behavior
- GraphQL HTTP client for `/v1/graphql`
- Metadata API client for `/v1/metadata`
- Schema discovery via GraphQL introspection
- Optional metadata enrichment via `export_metadata`
- Sync mapping from Hasura schema to Metabase tables and fields
- Native GraphQL execution pipeline
- Deterministic flattening of GraphQL objects into tabular rows
- Limited MBQL query-builder support (select, filter, sort, paginate, aggregate)
- Unit, integration, build, and lint automation

Not in scope for now:

- Joins across related objects in the query builder
- Variables for native GraphQL queries
- Mutations and subscriptions
- Per-user role mapping

## Scope

This project targets the first useful slice of Hasura support in Metabase:
native GraphQL questions and reliable metadata sync, before any broader query
builder work.

### MVP principles

- Read-only driver
- Native-query-first
- Minimal assumptions about Hasura deployment shape
- Works with introspection alone and improves when Metadata API access exists
- Tests are part of the feature contract, not an afterthought

### Supported in the current MVP target

- Metabase plugin load and registration
- Endpoint, admin secret, bearer token, role, and timeout configuration
- Connection testing against Hasura
- Discovery through `/v1/graphql`
- Optional enrichment through `/v1/metadata`
- Sync of sources, tables, and scalar fields into Metabase
- Native GraphQL query execution
- Flattening nested objects into dot-path columns
- JSON-string serialization for array values
- Clear auth, transport, validation, and timeout errors

### Explicitly out of scope for the MVP

- Mutations
- Subscriptions
- Joins across related objects (in the query builder)
- Deep result modeling beyond the current flattening rules
- Write operations of any kind
- Uploads and schema changes

## Compatibility

- Metabase: minimum 59, pinned locally to v0.59.2
- Hasura: minimum 2.27.0, pinned locally to 2.27.0
- PostgreSQL: minimum 13, local stack uses 15
- Java: minimum 11, CI and recommended local development use 21

See [COMPATIBILITY.md](COMPATIBILITY.md) for the full compatibility contract.

## How It Works

The driver does not use JDBC. Instead, it packages Clojure source into a
Metabase plugin JAR and relies on Metabase to load and compile the driver at
runtime.

### Discovery pipeline

1. Metabase stores Hasura connection details.
2. The driver normalizes and validates the connection config.
3. The driver calls Hasura GraphQL introspection on `/v1/graphql`.
4. If enabled and authorized, the driver also calls `export_metadata` on
   `/v1/metadata`.
5. Introspection-derived fields are merged with metadata-derived source and
   schema naming.
6. The merged model is converted into Metabase `describe-database` and
   `describe-table` results.

### Native execution pipeline

1. Metabase passes a native query map containing GraphQL text.
2. The driver rejects forbidden operation types before making an HTTP call.
3. The GraphQL query is posted to Hasura.
4. The first root result value is converted into rows and columns.
5. Nested objects become dot-path columns such as `author.name`.
6. Arrays are serialized as JSON strings.

## Native Query Contract

The current native-query path is intentionally narrow.

- Query text lives in `{:native {:query "..."}}`
- Variables are not supported yet
- `mutation` and `subscription` operations are rejected up front
- The driver is read-only by design
- The root result must be either a list of objects or a single object
- Scalars or other unsupported root shapes return a driver error

### Flattening rules

1. A root list of objects becomes one result row per object.
2. A root object is treated as a single-row result.
3. Nested objects flatten to dot-path column names.
4. Arrays become JSON strings in a text-like column.
5. Null values are preserved.
6. Column order is fixed by the first result object.

Example native GraphQL query:

```graphql
{
  articles {
    id
    title
    published
    author {
      id
      name
    }
  }
}
```

Expected tabular shape:

| id  | title                   | published | author.id | author.name |
| --- | ----------------------- | --------- | --------- | ----------- |
| 1   | Introduction to GraphQL | true      | 1         | Alice Smith |

## Query Builder (MBQL) Support

The driver supports a limited subset of the Metabase notebook query builder.

### Supported MBQL features

- Single source table per query (referenced by string name)
- Column selection with string field names
- Filters: `=`, `!=`, `<`, `>`, `<=`, `>=`, `is-null`, `not-null`, `contains`, `starts-with`, `and`, `or`, `not`
- Sorting (`order_by`) on one or more columns
- Limit and offset pagination
- Aggregates: `count`, `sum`, `avg`, `min`, `max`

### Unsupported MBQL features (fail fast with a clear error)

| Feature             | Error type                                                   |
| ------------------- | ------------------------------------------------------------ |
| Joins               | `:hasura.error/unsupported` with `:feature :joins`           |
| Custom expressions  | `:hasura.error/unsupported` with `:feature :expressions`     |
| Breakout / group-by | `:hasura.error/unsupported` with `:feature :breakout`        |
| Integer field IDs   | `:hasura.error/unsupported` (require Metabase ID resolution) |

All unsupported patterns are detected before any HTTP call is made.

## Repository Layout

```text
.
|- src/de/meko/metabase/driver/
|  |- hasura.clj
|  \- hasura/
|     |- client.clj
|     |- config.clj
|     |- execute.clj
|     |- introspection.clj
|     |- query_processor.clj
|     \- sync.clj
|- test/
|- test-integration/
|- dev/
|  |- hasura/metadata/
|  \- postgres/init.sql
|- docs/
|- resources/metabase-plugin.yaml
|- COMPATIBILITY.md
|- PLAN.md
\- Makefile
```

### Core namespaces

- `src/de/meko/metabase/driver/hasura.clj`: driver registration and Metabase multimethods
- `src/de/meko/metabase/driver/hasura/config.clj`: connection normalization, validation, headers, read-only guard
- `src/de/meko/metabase/driver/hasura/client.clj`: HTTP client for GraphQL and Metadata API calls
- `src/de/meko/metabase/driver/hasura/introspection.clj`: introspection parsing and metadata enrichment
- `src/de/meko/metabase/driver/hasura/sync.clj`: conversion to Metabase sync structures
- `src/de/meko/metabase/driver/hasura/execute.clj`: native GraphQL execution and result flattening
- `src/de/meko/metabase/driver/hasura/query_processor.clj`: MBQL → GraphQL compiler (select, filter, sort, paginate, aggregate)

## Local Development Stack

The repository ships a full Docker Compose stack for local work:

- PostgreSQL 15
- Hasura GraphQL Engine 2.27.0
- Metabase 0.59.2

The seed schema includes:

- Standard tables: `authors`, `articles`, `tags`, `article_tags`, `orders`, `order_items`
- A view: `article_stats`
- An object relationship: `articles.author_id -> authors.id`
- An array relationship: `authors -> articles`
- Aggregate-friendly numeric data in `order_items`

This stack exists both for manual validation and for integration tests.

## Quick Start

### Prerequisites

- Java 11 or newer, with Java 21 recommended
- Clojure CLI
- Docker with Compose support

### 1. Install dependencies

```bash
make deps
```

### 2. Create local environment file

```bash
cp .env.example .env
```

Default local values already work for the development stack.

### 3. Start the stack

```bash
make stack-up
make stack-seed
```

Services:

- Hasura: `http://localhost:6080`
- Hasura console: `http://localhost:6080/console`
- Metabase: `http://localhost:6300`

### 4. Build the plugin

```bash
make build
```

This produces a plugin JAR in `target/`.

### 5. Restart Metabase after rebuilding

```bash
docker compose restart metabase
```

The stack mounts `target/` into Metabase as `/plugins`, so rebuilding the JAR
followed by a Metabase restart is the normal local plugin workflow.

## Connecting Metabase to Hasura

When you add the `Hasura` database in the local Docker stack, use values that
Metabase can resolve from inside the container network.

| Field                | Value for local stack |
| -------------------- | --------------------- |
| GraphQL Endpoint URL | `http://hasura:8080`  |
| Admin Secret         | `local-admin-secret`  |
| Bearer Token         | leave empty           |
| Hasura Role          | optional              |
| Use Metadata API     | enabled               |
| Request Timeout (ms) | `30000`               |

### Connection properties

The plugin manifest currently defines these fields:

- `endpoint`: Hasura base URL without `/v1/graphql`
- `admin-secret`: sent as `X-Hasura-Admin-Secret`
- `access-token`: sent as `Authorization: Bearer ...` when no admin secret is set
- `role`: sent as `X-Hasura-Role`
- `use-metadata-api`: toggles metadata enrichment
- `request-timeout-ms`: request timeout in milliseconds

### Auth behavior

- Admin secret takes priority over bearer token
- Bearer token is only sent when admin secret is absent
- Role header is added independently when configured
- Metadata API requests strip the role header because Hasura rejects it on
  `/v1/metadata`

## Build, Test, and Lint

### Build the plugin JAR

```bash
make build
```

Or directly:

```bash
clojure -T:build jar
```

### Run unit tests

```bash
make test-unit
```

### Run integration tests

```bash
make stack-up
make stack-seed
make test-integration
```

### Run everything

```bash
make test-all
```

### Run lint

```bash
make lint
```

See [docs/TEST_STRATEGY.md](docs/TEST_STRATEGY.md) for the full test pyramid.

## CI

GitHub Actions currently runs:

- Unit tests
- Plugin JAR build and artifact upload
- Integration tests against the Docker Compose stack
- `clj-kondo` linting

The workflow lives in `.github/workflows/ci.yml`.

## Development Workflow

### Recommended loop

1. Start the local stack with `make stack-up`.
2. Verify metadata with `make stack-seed`.
3. Make code changes.
4. Run `make test-unit`.
5. Run `make test-integration` when the change affects Hasura behavior.
6. Rebuild with `make build`.
7. Restart Metabase with `docker compose restart metabase`.
8. Validate in the Metabase UI.

### REPL development

For interactive driver work against a local Metabase checkout, see
[docs/DEV_SETUP.md](docs/DEV_SETUP.md). The build itself does not require a
Metabase checkout because the plugin ships source and relies on Metabase to
compile it at load time.

## Discovery and Sync Model

The driver models Hasura data in two layers:

- Introspection provides table-like root fields and scalar columns.
- Metadata enrichment provides tracked source and schema naming when available.

### Introspection-only mode

When Metadata API access is disabled or unavailable, the driver still works by
deriving a stable internal schema model from GraphQL introspection alone.

### Metadata-enriched mode

When `export_metadata` is available, the driver uses it to:

- Preserve source names
- Preserve schema-qualified table identity
- Filter discovery to tracked tables

### Type mapping

Current scalar mappings include common Hasura and Postgres-backed GraphQL
scalars such as:

- `Int`, `int2`, `int4` -> `:type/Integer`
- `int8`, `bigint` -> `:type/BigInteger`
- `Float`, `float4`, `float8` -> `:type/Float`
- `numeric` -> `:type/Decimal`
- `String`, `text`, `varchar`, `bpchar` -> `:type/Text`
- `Boolean` -> `:type/Boolean`
- `uuid` -> `:type/UUID`
- `json`, `jsonb` -> `:type/JSON`
- `timestamp`, `timestamptz`, `date`, `time` -> temporal Metabase base types

Unknown scalars fall back to `:type/*`.

## Known Limitations

- Native queries do not support variables.
- MBQL support covers a limited subset only; joins, expressions, and breakout are rejected.
- Arrays in GraphQL responses are serialized to JSON strings rather than expanded into rows.
- Relationship traversal in native queries is limited to what the flattening rules can represent.
- Discovery is shaped by the configured Hasura role and permissions.
- Metadata enrichment requires admin-capable access and an enabled Metadata API.
- Partial GraphQL responses (simultaneous `:data` and `:errors`) are treated as failures.

## Documentation Map

- [PLAN.md](PLAN.md): implementation roadmap and phase status
- [COMPATIBILITY.md](COMPATIBILITY.md): supported versions, scope, and behavior contract
- [docs/DEV_SETUP.md](docs/DEV_SETUP.md): development setup and local plugin workflow
- [docs/TEST_STRATEGY.md](docs/TEST_STRATEGY.md): unit and integration test strategy

## Roadmap

The v0.1.0 scope is complete. Possible next steps:

- Broader MBQL filter coverage (temporal, between, ends-with)
- Native query variables support
- Deeper MBQL aggregate support (breakout / group-by via Hasura `_aggregate`)
- Per-user Hasura role mapping
- Retry logic for transient network errors

## Contributing

Contributions should preserve the project constraints:

- Keep the driver read-only unless the scope changes explicitly.
- Prefer pure helpers and fixture-backed unit tests for parsing and transforms.
- Add integration coverage for anything that depends on real Hasura behavior.
- Update operator-facing docs when public behavior changes.
- Treat unsupported behavior as something to document and test, not hide.

If you change connection behavior, sync output, or native result flattening,
update [COMPATIBILITY.md](COMPATIBILITY.md) and the relevant tests in the same
change.
