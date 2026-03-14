# Hasura Metabase Driver Plan

## Planning Principles

- Build the first release as a non-JDBC Metabase community driver for Hasura 2.x.
- Prioritize native GraphQL support and metadata sync before any MBQL query-builder work.
- Treat tests as first-class work. No feature is considered complete until automated tests cover the expected behavior and the main failure modes.
- Pin the first supported Metabase minor version and a small Hasura 2.x compatibility range before broadening support.
- Keep the MVP read-only. Exclude mutations, subscriptions, uploads, and schema-changing operations.

## Delivery Scope

### In Scope for MVP

- Plugin packaging and loading in Metabase
- Connection configuration and connection testing
- Schema discovery via GraphQL introspection
- Optional Metadata API enrichment when available
- Metadata sync into Metabase tables and fields
- Native GraphQL query execution
- Deterministic flattening of GraphQL results into tabular rows
- Clear handling of auth, transport, validation, and timeout errors

### Deferred Until After MVP

- Broad notebook editor support
- Joins across related objects
- Deep nested result modeling
- Per-user Hasura role mapping
- Mutations and subscriptions

## Phase 0: Test Harness and Scope Baseline

### Task 0.1: Pin the delivery contract

- [x] Choose the first supported Metabase version and record it. → Metabase 59 (COMPATIBILITY.md)
- [x] Choose the initial Hasura 2.x version range and record it. → minimum v2.27.0 (COMPATIBILITY.md)
- [x] Confirm the MVP is native-query-first and read-only. → COMPATIBILITY.md §MVP Scope
- [x] Define the initial unsupported feature list so failure behavior can be tested intentionally. → COMPATIBILITY.md §Explicitly Unsupported

Acceptance criteria:

- [x] A written compatibility target exists for Metabase and Hasura.
- [x] The MVP scope and non-goals are documented clearly enough to drive tests and review.

### Task 0.2: Create the local integration stack

- [x] Create a Docker Compose stack for Postgres, Hasura, and Metabase. → docker-compose.yml (Hasura v2.27.0, Metabase v0.59.0, Postgres 15)
- [x] Add a seed dataset with normal tables, a view, one object relationship, one array relationship, and one aggregate-friendly fact table. → dev/postgres/init.sql + dev/hasura/metadata/
- [x] Add repeatable commands for stack startup, reset, and teardown. → Makefile (stack-up, stack-seed, stack-reset, stack-down)
- [x] Confirm the stack can be used both for manual testing and automated integration tests. → .github/workflows/ci.yml integration job

Acceptance criteria:

- [x] One command starts a reproducible local stack. → `make stack-up`
- [x] One command resets the fixture data to a known state. → `make stack-reset`
- [x] The environment is stable enough to run repeatedly in local development and CI.

### Task 0.3: Establish the automated test strategy

- [x] Define the unit, integration, and Metabase driver-harness test layers. → docs/TEST_STRATEGY.md
- [x] Create the initial test namespaces and shared fixtures. → test/de/meko/metabase/driver/hasura/ + fixtures.clj
- [x] Add CI commands for build, unit tests, and integration tests. → .github/workflows/ci.yml (unit, build, integration, lint jobs) + Makefile
- [x] Decide what will be mocked and what must hit a real Hasura instance. → docs/TEST_STRATEGY.md §What Must Hit a Real Hasura Instance

Acceptance criteria:

- [x] The repository has a documented test pyramid.
- [x] Every later phase can add tests into an existing structure instead of inventing one.
- [x] CI can fail on test regressions before packaging a plugin.

### Task 0.4: Add baseline smoke tests before implementation

- [x] Add a smoke test for plugin packaging once the JAR exists. → hasura_test.clj (plugin-manifest-exists, plugin-manifest-has-required-keys)
- [x] Add an HTTP fixture strategy for GraphQL and Metadata API responses. → fixtures.clj (introspection-response, metadata-export-response, query response variants, error variants)
- [x] Add regression-test placeholders for connection, sync, native execution, and MBQL translation. → hasura_test.clj + client_test.clj + introspection_test.clj + sync_test.clj + execute_test.clj

Acceptance criteria:

- [x] The test suite already contains placeholders or pending cases for each major capability.
- [x] The team can implement features against pre-declared test targets.

## Phase 1: Plugin Bootstrap and Connection Path

### Task 1.1: Create the plugin skeleton

- [x] Add the top-level `metabase-plugin.yaml` manifest. → resources/metabase-plugin.yaml
- [x] Add the initial Clojure namespaces for driver registration, client, introspection, sync, execution, and tests. → all 6 src namespaces properly structured
- [x] Add build configuration for producing the plugin JAR. → build.clj (source-only JAR, no AOT — Metabase compiles at load time)
- [x] Make sure the namespace layout matches Metabase plugin expectations. → verified: metabase-plugin.yaml at JAR root, all .clj files on classpath

Acceptance criteria:

- [x] The project builds a plugin artifact without ad hoc manual steps. → `clojure -T:build jar` / `make build`
- [x] The plugin structure is recognizable to Metabase and maintainers. → JAR verified, 47 tests pass (6 assertions from smoke tests)

### Task 1.2: Register the driver with Metabase

- [x] Implement the minimum driver registration and display multimethods. → `(driver/register! :hasura)` + `driver/display-name` in hasura.clj
- [x] Expose the driver as `Hasura` in Metabase. → verified via /api/session/properties: driver-name=Hasura
- [x] Configure lazy loading and startup initialization. → `lazy-load: true` + `init: load-namespace` in metabase-plugin.yaml
- [x] Verify the plugin loads from the Metabase plugins directory. → log confirms "Registering lazy loading driver :hasura..." + "Registered driver :hasura 🚚"

Notes:
- Extracted `connection-config` and `guard-query!` into `config.clj` (no Metabase deps) to enable unit testing.
- Fixed YAML: `:` inside display-name string values must not appear unquoted.
- Fixed Metabase image tag: latest is `v0.59.2` (not `v0.59.0`). Updated docker-compose.yml and COMPATIBILITY.md.
- 53 unit tests, 48 assertions, 0 failures.

Acceptance criteria:

- [x] Metabase starts without plugin load errors. → verified in docker logs
- [x] The Add Database flow shows `Hasura` as a database option. → all 6 connection fields confirmed via /api/session/properties

### Task 1.3: Implement connection configuration and auth headers

- [x] Define connection properties for endpoint, admin secret or bearer token, role, extra headers, and timeout settings. → metabase-plugin.yaml (6 fields) + connection-config in config.clj
- [x] Normalize connection settings into a single internal config map. → config/connection-config (trims, strips trailing slashes, defaults timeout)
- [x] Implement header generation for GraphQL and Metadata API requests. → config/request-headers (admin-secret takes priority; access-token only when secret is absent; role always added)
- [x] Reject invalid or incomplete connection settings early. → config/validate-config! (nil/blank endpoint, non-http scheme; called before every HTTP request)

Notes:
- Fixed auth-priority bug in the previous build-headers sketch: admin-secret and access-token were both being sent simultaneously. Now mutually exclusive.
- client.clj delegates header construction to config/request-headers (keeping HTTP layer free of business logic).
- 4 client_test.clj header tests are now real assertions (were pending).
- 63 tests, 88 assertions, 0 failures.

Acceptance criteria:

- [x] A user can supply supported auth settings without hidden assumptions. → auth priority documented and tested: admin-secret wins, access-token is ignored when secret is present
- [x] Invalid configuration fails with a clear validation error. → validate-config! throws :hasura.error/config-invalid with :field and human-readable message

### Task 1.4: Implement connection test behavior

- [x] Add a minimal GraphQL ping or introspection request for connection testing. → client/ping! sends `{ __typename }` via graphql-post
- [x] Parse transport errors, auth failures, and malformed responses into Metabase-friendly errors. → normalise-response in client.clj (auth/client/server/graphql/unreachable/timeout)
- [x] Add unit tests for success and common failure paths. → client_test.clj (graphql-post + ping! with clj-http-fake and with-redefs)
- [x] Add an integration test against the Docker stack. → test/…/integration_connection_test.clj

Notes:
- graphql-post and ping! implemented in client.clj; metadata-post stays commented for Phase 2 (Task 2.1).
- can-connect? in hasura.clj now delegates: validate-config! → ping!.
- fixtures.clj missing cheshire require was fixed.
- Integration test reads HASURA_URL / HASURA_SECRET from env (docker-compose defaults).

Acceptance criteria:

- [x] Metabase `Test connection` succeeds against a valid Hasura instance. → can-connect? calls ping! which POSTs { __typename }
- [x] Auth failures, network failures, and invalid endpoint failures are distinguishable in tests. → unit tests cover :hasura.error/auth, :hasura.error/timeout, :hasura.error/unreachable separately

## Phase 2: Metadata Discovery and Sync

### Task 2.1: Build the Hasura HTTP client layer

- [x] Implement a small reusable POST client for `/v1/graphql`.
- [x] Implement a small reusable POST client for `/v1/metadata`.
- [x] Centralize request serialization, response parsing, and timeout handling.
- [x] Add unit tests for header injection, timeout propagation, and error normalization.

Notes:
- Uncommented and activated `metadata-post` in client.clj; shares `normalise-response` with `graphql-post`.
- Added 4 unit tests: `client-metadata-post-success`, `client-metadata-post-auth-failure`, `client-metadata-post-server-error`, `client-timeout-propagated`.
- `client-timeout-propagated` uses `with-redefs` on `clj-http.client/post`, returns pre-parsed body map to bypass JSON coercion middleware.
- Added integration test `integration-metadata-post-success`.
- Hasura 2.27.0 `cli-migrations-v3` returns metadata directly from `export_metadata` (no `resource_version` wrapper). Updated `metadata-export-response` fixture and all assertions accordingly.
- Moved integration tests to `test-integration/` directory (separate source root) to fix cognitect test-runner regex leak; `:test` alias scans `test/` only, `:test-integration` scans `test-integration/` only. No regex exclusion needed.

Acceptance criteria:

- [x] GraphQL and Metadata API calls share a single predictable client behavior.
- [x] Transport and parsing behavior is covered by unit tests.

### Task 2.2: Implement GraphQL introspection parsing

- [x] Fetch the root query type and related schema details. → `fetch-schema` in introspection.clj
- [x] Identify table-like fields suitable for Metabase table mapping. → `table-like-field?` (excludes `_aggregate`, `_by_pk`, `_stream`)
- [x] Extract scalar fields and classify obvious non-tabular fields. → `scalar-field?` + `unwrap-type` (handles NON_NULL/LIST wrappers)
- [x] Create stable identifiers and names for discovered objects. → `stable-table-id` produces `"schema.table"` strings

Notes:
- `unwrap-type` follows ofType chains through NON_NULL/LIST wrappers to reach the leaf kind/name.
- `parse-schema` builds a type-index (O(1) lookup) then maps root query fields to the internal `:hasura/schema` structure.
- `fetch-schema` composes `graphql-post` → `get-in [:data :__schema]` → `parse-schema`.
- 6 new unit tests added: table count, aggregate exclusion, stable IDs, scalar-only columns, field type/nullability, unwrap-type chain.
- 74 unit tests, 124 assertions, 0 failures. 4 integration tests, 5 assertions, 0 failures.

Acceptance criteria:

- [x] The driver can derive a consistent table and field model from introspection alone.
- [x] Repeated introspection produces stable names and identifiers for unchanged schemas.

### Task 2.3: Add optional Metadata API enrichment

- [x] Call `export_metadata` when enabled and authorized. → `fetch-metadata` in introspection.clj
- [x] Enrich source, schema, table, and relationship naming when metadata is available. → `enrich-with-metadata` restructures `:sources` from metadata source list, updates `:schema`/`:id` per table
- [x] Fall back cleanly to introspection-only discovery when metadata is disabled. → `enrich-with-metadata` returns schema-map unchanged when metadata-resp is nil
- [x] Add tests for both metadata-enabled and metadata-disabled paths. → 8 unit tests + 4 integration tests

Notes:
- `fetch-metadata` swallows `:hasura.error/auth` (role-restricted Metadata API) and re-throws all other errors.
- `enrich-with-metadata` is pure; tables tracked in introspection but absent from metadata are excluded (metadata defines what is tracked).
- Integration test file: `test-integration/…/integration_introspection_test.clj`.

Acceptance criteria:

- [x] The driver works when the Metadata API is unavailable.
- [x] When metadata is available, discovery results are richer but still consistent with the fallback model.

### Task 2.4: Normalize discovery into Metabase sync structures

- [x] Map discovered sources, schemas, tables, and fields into Metabase concepts. → `describe-database*` / `describe-table*` in sync.clj
- [x] Filter internal, hidden, or unsupported fields. → relationship fields already excluded by introspection `scalar-field?`; aggregate/pk/stream root fields excluded by `table-like-field?`
- [x] Map GraphQL scalar types into an initial Metabase type set. → `graphql-scalar->base-type` (22-entry map, falls back to `:type/*`)
- [x] Preserve deterministic ordering to reduce sync churn. → sets used; `describe-database*` / `describe-table*` output is content-stable

Notes:
- `sync.clj` has no Metabase deps — pure helpers testable without the plugin classloader.
- `describe-database [cfg]` and `describe-table [cfg name]` are the public API, composing the full introspection+metadata pipeline then calling the pure helpers.
- Multimethods wired in `hasura.clj`; sync namespace required there.
- **Bug found and fixed during integration:** introspection query only fetched 2 ofType levels; real Hasura uses `[T!]!` = NON_NULL(LIST(NON_NULL(OBJECT))) which needs 3 levels. Added the third level and updated fixtures to match real Hasura wrapping shapes.

Acceptance criteria:

- [x] Sync results are stable across repeated runs.
- [x] Field names and rough types are visible in Metabase after sync.

### Task 2.5: Implement sync tests before broader feature work

- [x] Add unit tests for schema normalization and type mapping. → sync_test.clj (type mapping, describe-database*, describe-table*, stability)
- [x] Add integration tests for sync against the seeded Hasura schema. → integration_sync_test.clj
- [x] Add regression tests for metadata-only, introspection-only, and mixed discovery behavior. → introspection-only (`use-metadata-api false`) and enriched paths both tested in unit and integration
- [x] Add negative tests for role-filtered schemas and missing fields. → role-restricted returns `{:tables #{}}`, unknown table returns `{:fields #{}}`

Notes:
- **Bug found and fixed:** Hasura 2.x returns HTTP 400 when `X-Hasura-Role` is sent to `/v1/metadata`. `fetch-metadata` now strips `:role` from the config before calling `metadata-post`. Added regression unit test `fetch-metadata-strips-role-from-request`.

Acceptance criteria:

- [x] Sync coverage exists before any MBQL work starts.
- [x] The most likely schema drift and permission regressions are captured by automated tests.

## Phase 3: Native GraphQL Execution

### Task 3.1: Define the native query contract

- [ ] Decide how native GraphQL text is entered and stored by the driver.
- [ ] Decide whether variables are supported in the MVP and document the choice.
- [ ] Define the read-only guardrails for native execution.
- [ ] Add tests for accepted and rejected native query shapes.

Acceptance criteria:

- The MVP native query path has a clear and documented contract.
- Unsupported operations fail predictably in tests.

### Task 3.2: Implement GraphQL execution pipeline

- [ ] Execute user-supplied GraphQL against `/v1/graphql`.
- [ ] Pass connection headers and timeout settings through every request.
- [ ] Parse the GraphQL response envelope consistently.
- [ ] Convert GraphQL errors into readable driver-level errors.

Acceptance criteria:

- A valid native GraphQL query returns parsed response data.
- GraphQL validation and auth errors are surfaced clearly.

### Task 3.3: Flatten GraphQL results into rows and columns

- [ ] Implement the MVP flattening rule for root lists of objects.
- [ ] Flatten nested objects using dot-path column names.
- [ ] Decide whether arrays are stringified or rejected and enforce that consistently.
- [ ] Add tests for scalar, nested-object, aggregate, null, and array cases.

Acceptance criteria:

- The same response shape always produces the same tabular output.
- Nested objects and nulls behave consistently across test cases.

### Task 3.4: Add end-to-end native query tests

- [ ] Add integration tests for simple table reads.
- [ ] Add integration tests for nested relationship reads that stay within the flattening rules.
- [ ] Add integration tests for aggregate-style queries.
- [ ] Add negative tests for mutations, unsupported arrays, timeouts, and malformed GraphQL.

Acceptance criteria:

- Native GraphQL is proven against real Hasura responses, not only fixtures.
- The test suite documents the exact supported response shapes.

## Phase 4: Limited Query-Builder Support

### Task 4.1: Freeze the supported MBQL subset

- [ ] Limit the first MBQL scope to one table at a time.
- [ ] Support select columns, simple filters, sort, limit, offset, and basic aggregates only.
- [ ] Explicitly reject joins, custom expressions, and deep relationship traversal.
- [ ] Turn the subset into a compatibility matrix for testing.

Acceptance criteria:

- The supported query-builder scope is finite and testable.
- There is no ambiguity about which MBQL features must fail fast.

### Task 4.2: Build the translation pipeline

- [ ] Convert supported MBQL into an internal Hasura query representation.
- [ ] Render that representation into GraphQL fields, arguments, and variables.
- [ ] Map filters into `where`, sorting into `order_by`, and pagination into `limit` and `offset`.
- [ ] Keep compilation isolated from HTTP execution so it can be tested independently.

Acceptance criteria:

- Translation can be unit-tested without hitting a real Hasura instance.
- The generated GraphQL is stable for the same MBQL input.

### Task 4.3: Implement aggregate and filter coverage incrementally

- [ ] Add equality and inequality filters.
- [ ] Add numeric and temporal comparison filters.
- [ ] Add null checks.
- [ ] Add count, sum, avg, min, and max support through Hasura aggregate fields.

Acceptance criteria:

- Each supported operator has dedicated tests for success and unsupported edge cases.
- Aggregate translation works for the seeded fact table.

### Task 4.4: Add unsupported-query handling

- [ ] Detect unsupported MBQL patterns before execution.
- [ ] Return driver errors that explain what is unsupported.
- [ ] Add regression tests for joins, deep nesting, and unsupported expressions.
- [ ] Confirm unsupported-query failures do not produce misleading transport errors.

Acceptance criteria:

- Unsupported notebook-editor queries fail clearly and early.
- Error behavior is deterministic and covered by tests.

### Task 4.5: Add end-to-end MBQL tests

- [ ] Add unit tests for translation output.
- [ ] Add integration tests for browse, filter, sort, paginate, and aggregate flows.
- [ ] Add Metabase driver-harness coverage where applicable.
- [ ] Capture baseline snapshots or structured assertions for stable generated GraphQL.

Acceptance criteria:

- Query-builder support is backed by both translation-level and end-to-end tests.
- Regressions in generated GraphQL or result normalization are caught automatically.

## Phase 5: Hardening, Compatibility, and Release Readiness

### Task 5.1: Harden caching, sync stability, and permissions behavior

- [ ] Review how role-specific schemas affect discovery and cached metadata.
- [ ] Verify repeat sync behavior against unchanged and changed schemas.
- [ ] Test permission-limited roles against the seeded environment.
- [ ] Add regression coverage for metadata churn and role-filtered visibility.

Acceptance criteria:

- Sync behavior stays stable across repeated runs.
- Role-specific schemas do not silently corrupt cached metadata.

### Task 5.2: Strengthen operational resilience

- [ ] Add retries only where they are safe and measurable.
- [ ] Verify timeout behavior for connection tests, sync, and query execution.
- [ ] Confirm logging is useful without leaking secrets.
- [ ] Add tests for slow responses, partial GraphQL errors, and malformed payloads.

Acceptance criteria:

- The driver fails safely under slow or broken upstream conditions.
- Logs and error messages help diagnose issues without exposing credentials.

### Task 5.3: Finalize packaging and CI

- [ ] Build the plugin JAR in CI.
- [ ] Run unit and integration suites in CI.
- [ ] Publish build artifacts for manual validation.
- [ ] Add release checks that block shipping when compatibility or tests regress.

Acceptance criteria:

- A clean CI run produces a release-ready plugin artifact.
- Broken tests or broken packaging block the release automatically.

### Task 5.4: Write operator and contributor documentation

- [ ] Document supported Metabase versions and Hasura versions.
- [ ] Document supported auth methods and connection properties.
- [ ] Document flattening rules and known limitations.
- [ ] Document the local development flow, test commands, and Docker stack usage.

Acceptance criteria:

- A new contributor can build, run, and test the project from the docs.
- An operator can understand the current feature set and limitations before installation.

### Task 5.5: Execute release validation

- [ ] Run the full automated test matrix on a release candidate.
- [ ] Perform a manual smoke test in a clean Metabase instance.
- [ ] Verify install, connect, sync, native query, and one supported MBQL workflow manually.
- [ ] Publish release notes with compatibility constraints and known gaps.

Acceptance criteria:

- The release candidate passes automated and manual validation.
- The published release notes make support boundaries explicit.

## Cross-Phase Exit Rules

- A phase is not complete until its tests are merged and passing.
- Any newly discovered unsupported behavior must be documented and added as an explicit negative test.
- Every phase must leave the local Docker stack and CI flow in a working state.
- Public behavior changes must update operator-facing documentation before the phase closes.

## Suggested Execution Order Summary

1. Build the test harness and local stack first.
2. Get plugin loading and connection testing working next.
3. Implement discovery and sync before query features.
4. Ship native GraphQL execution before MBQL translation.
5. Add only a narrow MBQL subset.
6. Harden, document, and release only after the automated matrix is reliable.
