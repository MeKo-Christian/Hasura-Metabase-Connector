Here’s an implementation plan for a **Metabase community driver for Hasura 2.x**.

The core constraint is that Metabase drivers are **modules packaged as JAR plugins** with a top-level `metabase-plugin.yaml`, and driver logic is implemented by extending Metabase driver multimethods. Metabase’s docs and sample driver both assume a **Clojure/JVM plugin** workflow, and Metabase loads these plugins from the `plugins` directory or `MB_PLUGINS_DIR`. ([Metabase][1])

## 1) Decide the driver shape before coding

You have two possible product targets:

### Option A: “Native query only” Hasura driver

Metabase connects to Hasura and only supports **native GraphQL questions**, with little or no graphical query builder support.

This is the fastest path because you avoid most of the hard MBQL translation work. It is also the safest MVP because Hasura exposes a GraphQL API whose fields are auto-generated from the underlying tables/views, and GraphQL introspection can describe that schema. ([Hasura][2])

### Option B: “Full query-builder” Hasura driver

Metabase supports the notebook/query builder by translating Metabase’s internal query representation into Hasura GraphQL.

This is the ambitious path. It will require a proper query processor namespace and significant translation logic, which Metabase’s driver docs call out as the most complicated part of a driver. ([Metabase][3])

**Recommendation:** ship **Option A first**, then add a narrow subset of Option B later.

---

## 2) Target architecture

Build the plugin as a **non-JDBC Clojure driver** with these internal modules:

- `de.meko.metabase.driver.hasura`
  Main driver registration, features, connection properties, metadata methods.
- `de.meko.metabase.driver.hasura.client`
  HTTP client for `/v1/graphql` and `/v1/metadata`.
- `de.meko.metabase.driver.hasura.introspection`
  GraphQL introspection + schema normalization.
- `de.meko.metabase.driver.hasura.sync`
  Map Hasura schema/types into Metabase tables/fields.
- `de.meko.metabase.driver.hasura.query_processor`
  MBQL → GraphQL compiler, initially tiny or absent for MVP.
- `de.meko.metabase.driver.hasura.execute`
  Query execution and result-set normalization.
- `de.meko.metabase.driver.hasura.test-data`
  Test extensions for Metabase’s driver test harness.

That split matches Metabase’s guidance that bigger drivers usually separate the core namespace from a dedicated query processor namespace. ([Metabase][4])

---

## 3) Scope the Hasura surface area up front

Hasura 2.x exposes:

- GraphQL query/subscription operations whose fields are auto-generated from tracked tables/views. ([Hasura][2])
- Metadata API at `/v1/metadata`, including `export_metadata`, supported in v2.x. ([Hasura][5])
- Optional disabling of the Metadata API via `HASURA_GRAPHQL_ENABLED_APIS`, so you cannot rely on metadata access always being available. ([Hasura][6])

So the driver should support two discovery modes:

1. **Preferred discovery:** GraphQL introspection on `/v1/graphql`
2. **Enhanced discovery:** Metadata API on `/v1/metadata` when enabled and credentials allow it

That gives you a workable path even when admin metadata access is unavailable.

---

## 4) Define the MVP feature contract

For v0.1, implement only:

- Connect/test connection
- List “databases” / schemas / tables / fields as Metabase sees them
- Sync metadata
- Execute **native GraphQL queries**
- Flatten results into tabular rows
- Minimal type mapping
- Read-only behavior

Do **not** implement at first:

- Full notebook builder support
- Write operations
- Uploads
- DDL / schema mutation
- Mutations / subscriptions
- Deep nested result visualization beyond one flattening strategy

This is important because Metabase’s driver interface grows over time, and the changelog shows new multimethods get added across releases. Keeping the first version narrow limits maintenance burden. ([Metabase][7])

---

## 5) Define connection UX in `metabase-plugin.yaml`

Your plugin manifest should define:

- `driver.name: hasura`
- `display-name: Hasura`
- `lazy-load: true`
- connection properties like:
  - `endpoint` (required)
  - `admin_secret` or `access_token`
  - `role`
  - `headers_json`
  - `metadata_enabled` toggle
  - TLS / timeout options

Metabase’s plugin manifest drives lazy loading, display name, connection properties, and init steps. The manifest file must be top-level and named `metabase-plugin.yaml`. ([Metabase][8])

Connection model I would use:

- required: GraphQL endpoint URL
- optional:
  - `X-Hasura-Admin-Secret`
  - `Authorization: Bearer ...`
  - `X-Hasura-Role`
  - additional JSON headers
  - metadata endpoint override, default `${endpoint_base}/v1/metadata`

---

## 6) Create the repo skeleton

Use the sample driver as the scaffold. Metabase’s sample driver shows the expected `deps.edn`, `resources/metabase-plugin.yaml`, `src/...`, and `test/...` layout, and the sample build command is `clojure -X:dev:build`. ([Metabase][1])

Suggested structure:

```text
hasura-metabase-driver/
  deps.edn
  build.clj
  resources/
    metabase-plugin.yaml
  src/
    com/yourorg/metabase/driver/hasura.clj
    com/yourorg/metabase/driver/hasura/client.clj
    com/yourorg/metabase/driver/hasura/introspection.clj
    com/yourorg/metabase/driver/hasura/sync.clj
    com/yourorg/metabase/driver/hasura/query_processor.clj
    com/yourorg/metabase/driver/hasura/execute.clj
  test/
    metabase/test/data/hasura.clj
    com/yourorg/metabase/driver/hasura_test.clj
```

---

## 7) Implement the driver in 5 phases

## Phase 1: Bootstrap and load in Metabase

Goal: Metabase recognizes the plugin and shows “Hasura” in Add database.

Tasks:

- Create manifest
- Create main namespace
- Implement the minimum multimethods for:
  - display name
  - connection properties
  - driver registration
  - test connection

- Package JAR and confirm self-hosted Metabase loads it from `plugins` or `MB_PLUGINS_DIR` on restart. ([Metabase][1])

Definition of done:

- Metabase starts without plugin errors
- “Hasura” appears as a database type
- “Save” or “Test connection” works against a dev Hasura

### Technical note

Start with a hardcoded “ping” GraphQL query like `query { __typename }` or a minimal introspection request. That validates HTTP, auth, and JSON parsing before you touch sync.

---

## Phase 2: Metadata discovery and sync

Goal: Metabase can sync tables and columns.

Tasks:

- Build a small HTTP client for:
  - `POST /v1/graphql`
  - `POST /v1/metadata`

- Implement schema discovery via GraphQL introspection:
  - fetch root query type
  - enumerate top-level fields
  - detect “table-like” fields
  - extract scalar columns, nested object fields, aggregate fields

- Optionally enrich with `export_metadata` from Metadata API to recover:
  - tracked sources
  - tracked tables/views
  - relationships
  - source names

- Normalize discovered objects into Metabase’s concepts of:
  - database
  - schema
  - table
  - field

Hasura says its GraphQL schema is auto-generated from tracked tables and views, and the Metadata API’s `export_metadata` returns the metadata object for v2.x. ([Hasura][2])

### Key design choice

Treat a Hasura “object query field” like `authors` as the canonical Metabase table. For schema naming:

- If Metadata API is available, use Hasura source/schema/table metadata directly.
- If not, synthesize:
  - schema = source name or `public`
  - table = root field name

### Risk to manage

GraphQL introspection may expose role-filtered schema, while Metadata API is admin-oriented and may be disabled. Your sync logic must tolerate either source alone. ([Hasura][6])

Definition of done:

- Sync shows tables in Metabase Admin
- Fields have stable names and rough types
- Hidden/system fields are filtered

---

## Phase 3: Native GraphQL execution

Goal: users can run native queries and see rows.

Tasks:

- Introduce a “native query” language mode for GraphQL text
- Execute GraphQL request over HTTP
- Flatten JSON response into a tabular structure
- Surface GraphQL errors as Metabase driver errors
- Add request headers from saved connection settings

### Flattening strategy

Pick one deterministic rule and document it:

- If response root field is a list of objects, return one row per object.
- Flatten nested objects using dot paths, e.g. `author.name`.
- Flatten arrays either as JSON strings or reject them in MVP.
- For aggregate responses, expose scalar aggregate outputs as columns.

This matters because Hasura queries can be nested and GraphQL is not naturally tabular.

Definition of done:

- A native GraphQL query against Hasura returns a result table in Metabase
- Common error cases are readable:
  - auth failure
  - introspection disabled
  - validation error
  - timeout

---

## Phase 4: Limited query-builder support

Goal: support only a narrow, high-value MBQL subset.

Implement just:

- single table
- select columns
- simple filters:
  - equals
  - not equals
  - greater/less than for numerics/dates
  - is null / not null
  - contains / starts-with for strings if you can map them cleanly

- sort
- limit
- offset/page
- basic aggregates:
  - count
  - sum
  - avg
  - min
  - max

Hasura’s query API supports filtering, ordering, pagination, and aggregates over auto-generated fields, so this subset matches what Hasura already exposes well. ([Hasura][2])

### Translation model

Translate MBQL fragments to Hasura argument fragments:

- filters → `where`
- sort → `order_by`
- limit/offset → `limit` / `offset`
- aggregates → `<table>_aggregate`

Example mapping:

- MBQL: table `orders`, filter `status = "paid"`, limit 10
- GraphQL:

  ```graphql
  query {
    orders(where: { status: { _eq: "paid" } }, limit: 10) {
      id
      status
      total
    }
  }
  ```

### Keep this intentionally narrow

Do not implement joins first. Instead, allow only:

- direct columns on one object
- maybe one level of object relationship expansion later

Definition of done:

- Metabase query builder works for one-table browse/filter/sort/aggregate
- Unsupported patterns fail clearly with a driver error message

---

## Phase 5: Hardening, compatibility, and release

Goal: something other people can actually run.

Tasks:

- Add integration tests against local Hasura + Postgres in Docker
- Add Metabase driver test extensions
- Verify against your exact Metabase version
- Add CI to build the plugin JAR
- Publish versioned releases

Metabase’s test docs say proper driver testing requires test extensions and the ability to run the target backend locally with Docker. ([Metabase][9])

Definition of done:

- One-command local stack
- repeatable JAR build
- release notes with supported Metabase versions
- compatibility matrix:
  - Metabase version
  - Hasura 2.x versions tested
  - auth modes tested

---

## 8) The hardest parts you should design now

## A. Mapping Hasura schema to Metabase schema

This is the architectural core.

Recommended internal model:

- **Source** → Metabase database or schema grouping
- **Tracked table/view** → Metabase table
- **Scalar field** → Metabase field
- **Object relationship** → foreign-key-like field metadata
- **Array relationship** → related table, not inline column
- **Aggregate field** → synthetic metrics, not normal columns

Do not try to make GraphQL types look exactly like SQL tables. Use a pragmatic mapping.

## B. MBQL to GraphQL compilation

The query processor will be the most custom code. Metabase explicitly calls this area the complicated part of a driver. ([Metabase][4])

Recommended compiler pipeline:

- MBQL AST
- validate supported subset
- convert to internal “Hasura query AST”
- render GraphQL text + variables
- execute
- normalize result

That extra middle AST will save you later.

## C. Roles and permissions

Hasura schemas can vary by role. Decide whether:

- the saved Metabase connection uses one fixed role, or
- you expose role switching per database connection only

Do **not** try to map Metabase user permissions to per-query Hasura roles in v1. That becomes a security product, not just a driver.

## D. Nested data

Metabase wants rows and columns. GraphQL likes nested shapes.

MVP rule:

- one table at a time
- one row per object node
- nested objects flattened by path
- arrays unsupported or stringified

Be strict early.

---

## 9) Concrete milestone plan

## Milestone 0: Feasibility spike, 2–4 days

Deliverables:

- plugin loads
- connection form renders
- one hardcoded GraphQL query executes
- one introspection query parses

Exit criterion:

- you know auth and transport are fine

## Milestone 1: Sync MVP, 4–7 days

Deliverables:

- tables visible in Metabase
- fields visible with types
- stable IDs/names for repeat sync

Exit criterion:

- Metabase can browse metadata

## Milestone 2: Native GraphQL MVP, 3–5 days

Deliverables:

- native query editor support
- response flattening
- error handling

Exit criterion:

- analysts can run handwritten GraphQL

## Milestone 3: Query-builder alpha, 1–2 weeks

Deliverables:

- one-table select/filter/sort/limit
- basic aggregates
- clear unsupported-query messages

Exit criterion:

- simple questions work in notebook editor

## Milestone 4: Beta hardening, 1 week

Deliverables:

- tests
- Docker compose dev stack
- compatibility docs
- release JAR

---

## 10) Suggested development environment

Run locally with Docker:

- Postgres
- Hasura 2.x
- Metabase
- optional nREPL for driver development

You’ll need this anyway if you want to run Metabase’s driver tests later; their docs explicitly say local Dockerized backend support is part of the testing path. ([Metabase][9])

Suggested compose services:

- `postgres`
- `hasura`
- `metabase`
- optional `pgadmin`

Use a sample schema with:

- 3–5 normal tables
- one view
- one object relationship
- one array relationship
- one aggregate-heavy fact table

---

## 11) What multimethods to implement first

Metabase’s docs don’t boil this down to one tiny canonical list for non-JDBC drivers, and the exact surface evolves over versions, which is why their multimethod docs and driver changelog matter. ([Metabase][3])

In practical order, I’d implement:

1. display/config methods
2. connection validation
3. metadata discovery methods
4. native execution methods
5. query processor methods for a tiny MBQL subset
6. test extension methods

As you build, keep one eye on the driver-interface changelog for your target Metabase release, because new required methods appear over time. ([Metabase][7])

---

## 12) Risks that can kill the project

The main risk is not Clojure. It’s product mismatch.

### Risk 1: GraphQL is not naturally tabular

Metabase is row/column-first. Hasura returns nested object graphs. You’ll spend real effort flattening and deciding what “a table” means.

### Risk 2: role-dependent schemas

Two different Hasura roles may expose different fields entirely. That complicates sync and caching.

### Risk 3: Metadata API may be disabled

Hasura documents that the Metadata API can be disabled via enabled APIs config, so your driver cannot depend on it exclusively. ([Hasura][6])

### Risk 4: Metabase driver API drift

The driver changelog shows interface changes across versions. Plan to pin support to a specific Metabase minor version first. ([Metabase][7])

### Risk 5: query-builder completeness

The moment users ask for joins, complex nested filters, custom expressions, or deep relationship traversal, complexity rises sharply.

---

## 13) My recommendation on product strategy

Build it in this order:

1. **Community driver, native GraphQL only**
2. **Metadata sync for browsing**
3. **Limited query-builder subset**
4. **Only then** decide whether deeper MBQL coverage is worth it

That gives you a usable internal tool much sooner than trying to emulate a full SQL database from day one.

---

## 14) What I would put in the first README

- Supported Metabase version(s)
- Supported Hasura 2.x version(s)
- Auth methods supported
- Whether Metadata API is optional/recommended
- Native GraphQL examples
- Current query-builder limitations
- Flattening rules for nested results
- Known incompatibilities

---

## 15) First week task list

Day 1:

- fork sample driver
- rename namespaces
- create manifest
- load plugin in self-hosted Metabase

Day 2:

- add HTTP client and auth headers
- implement test connection
- add one introspection request

Day 3:

- parse introspection result into table/field model
- sync one tracked table

Day 4:

- implement native GraphQL execution
- flatten list-of-object responses

Day 5:

- add error handling and docs
- decide whether Metadata API enrichment is worth adding next

---

[1]: https://www.metabase.com/docs/latest/developers-guide/drivers/basics "https://www.metabase.com/docs/latest/developers-guide/drivers/basics"
[2]: https://hasura.io/docs/2.0/api-reference/graphql-api/query/ "https://hasura.io/docs/2.0/api-reference/graphql-api/query/"
[3]: https://www.metabase.com/docs/latest/developers-guide/drivers/multimethods "https://www.metabase.com/docs/latest/developers-guide/drivers/multimethods"
[4]: https://www.metabase.com/docs/latest/developers-guide/drivers/start "https://www.metabase.com/docs/latest/developers-guide/drivers/start"
[5]: https://hasura.io/docs/2.0/api-reference/metadata-api/manage-metadata/ "https://hasura.io/docs/2.0/api-reference/metadata-api/manage-metadata/"
[6]: https://hasura.io/docs/2.0/api-reference/metadata-api/index/ "https://hasura.io/docs/2.0/api-reference/metadata-api/index/"
[7]: https://www.metabase.com/docs/latest/developers-guide/driver-changelog "https://www.metabase.com/docs/latest/developers-guide/driver-changelog"
[8]: https://www.metabase.com/docs/latest/developers-guide/drivers/plugins "https://www.metabase.com/docs/latest/developers-guide/drivers/plugins"
[9]: https://www.metabase.com/docs/latest/developers-guide/drivers/driver-tests "https://www.metabase.com/docs/latest/developers-guide/drivers/driver-tests"
