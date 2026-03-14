(ns de.meko.metabase.driver.hasura.query-processor
  "MBQL → GraphQL compiler (Phase 4 — deferred until after native-query MVP).

  Supported MBQL subset (Phase 4 — Task 4.1):
    - Single table queries only
    - Column selection
    - Filters: = != < > <= >= is-null not-null contains starts-with
    - Order by (one or more columns, asc/desc)
    - Limit and offset
    - Aggregates: count sum avg min max via Hasura _aggregate fields

  Explicitly rejected (Task 4.4):
    - Joins
    - Custom expressions
    - Deep relationship traversal
    - Any MBQL feature outside the list above

  Compiler pipeline:
    MBQL inner-query map
      → validate-supported-subset    (fast-fail on unsupported patterns)
      → to-hasura-query              (intermediate Clojure data AST)
      → render-graphql               (produce {:query String :variables Map})
      → execute/execute-reducible-query

  Keeping render-graphql as a pure function (no HTTP) means the compiler can
  be unit-tested without a real Hasura instance.")

;; ---------------------------------------------------------------------------
;; TODO Phase 4 — Task 4.2: implement the compiler pipeline.
;; ---------------------------------------------------------------------------

;; The validate-supported-subset function is the most important — it must
;; detect and reject unsupported MBQL patterns BEFORE the HTTP call, with a
;; clear error message that names the unsupported feature.
;;
;; (defn validate-supported-subset [inner-query]
;;   (when (seq (:joins inner-query))
;;     (throw (ex-info "Hasura driver does not support joins in the query builder."
;;                     {:hasura/error-type :hasura.error/unsupported
;;                      :feature :joins})))
;;   ...)

;; (defn to-hasura-query [inner-query])     ; MBQL → internal Clojure AST
;; (defn render-graphql  [hasura-query])    ; internal AST → {:query ...}
