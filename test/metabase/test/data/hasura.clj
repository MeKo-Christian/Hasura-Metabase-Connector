(ns metabase.test.data.hasura
  "Metabase driver test harness extension for the Hasura driver.

  Implements metabase.test.data.interface/IDriverDatasetLoader for the
  :hasura driver so that Metabase's own driver test suite can create, load,
  and destroy test datasets against the integration Docker stack.

  Implemented in Phase 5 (Task 5.1).

  References:
    https://www.metabase.com/docs/latest/developers-guide/drivers/driver-tests")

;; TODO Phase 5: implement IDriverDatasetLoader for :hasura.
;; The implementation will:
;;   - create-db!       → no-op (Hasura tracks an existing Postgres DB)
;;   - destroy-db!      → no-op
;;   - create-table!    → no-op (tables created by seed SQL)
;;   - load-data!       → insert rows via the seed Postgres connection
;;   - db-def           → return a DatabaseDefinition pointing at the local stack
