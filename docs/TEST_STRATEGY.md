# Test Strategy

## Test Pyramid

```
                     ┌──────────────┐
                     │   Metabase   │  ← driver-harness tests
                     │   Harness    │    (future: Phase 5)
                     └──────┬───────┘
                   ┌────────┴────────┐
                   │  Integration   │  ← real Hasura + Postgres
                   │  Tests         │    (Docker Compose stack)
                   └────────┬───────┘
              ┌─────────────┴─────────────┐
              │        Unit Tests         │  ← fixtures, pure logic
              │  (HTTP fixtures / mocks)  │
              └───────────────────────────┘
```

## Layer Definitions

### Unit Tests (`test/de/meko/metabase/driver/hasura/`)

- **What they test:** Pure functions — JSON parsing, schema normalisation, type mapping, MBQL translation, result flattening, header generation.
- **What is mocked:** HTTP responses via `clj-http` stubbing or in-memory fixture maps.
- **What is NOT mocked:** JSON parsing, schema logic, data transformation.
- **Run with:** `make test-unit`
- **Required for CI:** Yes. All unit tests must pass before any packaging step.

### Integration Tests (`test/de/meko/metabase/driver/hasura/integration/`)

- **What they test:** End-to-end flows against the Docker Compose stack — connection test, full sync cycle, native query execution, error paths.
- **What is mocked:** Nothing. All calls hit a real Hasura and real Postgres.
- **Prerequisite:** `make stack-up && make stack-seed`
- **Run with:** `make test-integration`
- **Required for CI:** Yes, on the integration job only (separate from unit job).

### Metabase Driver Harness (future, Phase 5)

- **What they test:** Metabase's own driver contract — sync correctness, query pipeline interop.
- **Tooling:** `metabase.test.data` extensions, Metabase driver test harness.
- **Run with:** `make test-harness` (not wired until Phase 5)
- **Required for CI:** Yes, before any release candidate is built.

## What Must Hit a Real Hasura Instance

| Test scenario                    | Real Hasura required?            |
| -------------------------------- | -------------------------------- |
| Connection test (success)        | Yes                              |
| Connection test (auth failure)   | Yes (use wrong secret)           |
| Connection test (unreachable)    | No (fixture: connection refused) |
| Schema sync (introspection-only) | Yes                              |
| Schema sync (metadata-enriched)  | Yes                              |
| Schema sync (metadata disabled)  | Yes                              |
| Native query execution           | Yes                              |
| Native query timeout             | No (fixture: slow server)        |
| Native query mutation rejection  | No (pure parse check)            |
| Result flattening                | No (fixture: response JSON)      |
| Type mapping                     | No (pure function)               |
| MBQL translation output          | No (pure function)               |

## Fixture Strategy

GraphQL and Metadata API responses are stored as Clojure data in
`test/de/meko/metabase/driver/hasura/fixtures.clj`. This namespace provides:

- `introspection-response` — a representative `__schema` response for the seed database.
- `metadata-export-response` — a representative `export_metadata` v3 response.
- `query-response-list` — a list-of-objects response for a simple table read.
- `query-response-nested` — a response with nested object fields.
- `query-response-aggregate` — a `_aggregate` response shape.
- `query-response-with-array` — a response containing an array field.
- `error-auth` — a 401 / auth-error GraphQL error response.
- `error-validation` — a GraphQL validation error envelope.

HTTP-level fixture injection uses `clj-http.fake` for unit tests.

## Namespace Layout

```
src/
  de/meko/metabase/driver/
    hasura.clj                     Main driver registration and multimethods
    hasura/
      client.clj                   HTTP client for /v1/graphql and /v1/metadata
      introspection.clj            GraphQL introspection parsing
      sync.clj                     Metabase sync model construction
      execute.clj                  Native query execution and result flattening
      query_processor.clj          MBQL→GraphQL compiler (Phase 4)

test/
  de/meko/metabase/driver/
    hasura_test.clj                Driver-level smoke and regression tests
    hasura/
      fixtures.clj                 Static HTTP response fixtures
      client_test.clj
      introspection_test.clj
      sync_test.clj
      execute_test.clj
      query_processor_test.clj     (Phase 4)
      integration/
        connection_test.clj        Integration: connection test paths
        sync_test.clj              Integration: full sync cycle
        execute_test.clj           Integration: native query execution
  metabase/test/data/
    hasura.clj                     Metabase driver test harness extension (Phase 5)
```

## CI Commands

| Command                 | Description                                     |
| ----------------------- | ----------------------------------------------- |
| `make deps`             | Download all Clojure deps                       |
| `make build`            | Compile and produce plugin JAR                  |
| `make test-unit`        | Run unit tests (no Docker required)             |
| `make test-integration` | Run integration tests (Docker stack must be up) |
| `make test-all`         | Run unit + integration                          |
| `make stack-up`         | Start Docker Compose stack                      |
| `make stack-seed`       | Apply seed data and Hasura tracking             |
| `make stack-reset`      | Tear down and recreate stack with fresh seed    |
| `make stack-down`       | Stop and remove all containers                  |
| `make lint`             | Run clj-kondo static analysis                   |

## Regression Test Coverage Targets

Each phase must leave the following stubs green or explicitly pending:

- `connection/test-success` — connect to a healthy Hasura
- `connection/test-auth-failure` — wrong admin secret
- `connection/test-unreachable` — endpoint does not respond
- `sync/introspection-only` — schema tables and fields without metadata API
- `sync/metadata-enriched` — schema with metadata API
- `sync/metadata-fallback` — metadata disabled, falls back to introspection
- `execute/simple-read` — select all from one table
- `execute/nested-read` — object relationship included
- `execute/aggregate-read` — aggregate-style query
- `execute/mutation-rejected` — mutation keyword in query text
- `execute/timeout` — request exceeds timeout setting
- `execute/arrays-serialised` — array field becomes JSON string column
- `mbql/unsupported` — any MBQL query returns unsupported error (Phase 4 placeholder)
