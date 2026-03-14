(ns de.meko.metabase.driver.hasura.query-processor
  "MBQL → GraphQL compiler (Phase 4 — Tasks 4.1–4.5).

  Supported MBQL subset (Task 4.1):
    - Single source table, referenced by string name.
    - Column selection via [:field \"name\" opts] with string field names.
    - Filters: = != < > <= >= is-null not-null contains starts-with and or not
    - Order-by (one or more columns, asc / desc).
    - Limit and offset.
    - Aggregates: count, sum, avg, min, max via Hasura _aggregate fields.

  Explicitly NOT supported — Task 4.4 rejects these with :hasura.error/unsupported:
    - Joins (:joins key present and non-empty).
    - Custom expressions (:expressions key).
    - Breakout / group-by (:breakout key).
    - Integer field IDs ([:field 123 ...]) — require Metabase resolution.

  Compiler pipeline (Task 4.2):
    MBQL inner-query map
      → validate-supported-subset    fast-fail on unsupported patterns
      → render-select-query          or render-aggregate-query
      → {:query \"...GraphQL...\"}    consumed by execute-reducible-query

  All functions are pure (no HTTP calls) so they can be unit-tested without
  a live Hasura instance."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]))

;; ---------------------------------------------------------------------------
;; Task 4.4 — Unsupported query detection
;; ---------------------------------------------------------------------------

(defn validate-supported-subset
  "Throw :hasura.error/unsupported for any MBQL patterns this driver cannot handle.
  Returns `inner-query` unchanged on success so callers can thread."
  [inner-query]
  (when (seq (:joins inner-query))
    (throw (ex-info "Hasura driver does not support joins in the query builder."
                    {:hasura/error-type :hasura.error/unsupported :feature :joins})))
  (when (seq (:expressions inner-query))
    (throw (ex-info "Hasura driver does not support custom expressions."
                    {:hasura/error-type :hasura.error/unsupported :feature :expressions})))
  (when (seq (:breakout inner-query))
    (throw (ex-info "Hasura driver does not support breakout / group-by."
                    {:hasura/error-type :hasura.error/unsupported :feature :breakout})))
  inner-query)

;; ---------------------------------------------------------------------------
;; Task 4.2 — Field name extraction
;; ---------------------------------------------------------------------------

(defn field-name
  "Extract the string field name from an MBQL [:field name opts] clause.
  Throws :hasura.error/unsupported when the field is referenced by integer ID
  (requires Metabase infrastructure that is not available in this compiler)."
  [[_ n _]]
  (if (string? n)
    n
    (throw (ex-info "Field references by integer ID are not supported. Use string field name literals."
                    {:hasura/error-type :hasura.error/unsupported :field-ref n}))))

;; ---------------------------------------------------------------------------
;; Task 4.3 — Filter rendering
;; ---------------------------------------------------------------------------

(defn- render-value
  "Render a Clojure scalar as a GraphQL argument literal using JSON encoding."
  [v]
  (json/generate-string v))

(defn render-filter
  "Render an MBQL filter clause as a Hasura where-argument string.
  Returns nil when `clause` is nil (no filter)."
  [clause]
  (when clause
    (let [[op & args] clause]
      (case op
        :=           (let [[f v] args] (str "{" (field-name f) ": {_eq: "  (render-value v) "}}"))
        :!=          (let [[f v] args] (str "{" (field-name f) ": {_neq: " (render-value v) "}}"))
        :<           (let [[f v] args] (str "{" (field-name f) ": {_lt: "  (render-value v) "}}"))
        :>           (let [[f v] args] (str "{" (field-name f) ": {_gt: "  (render-value v) "}}"))
        :<=          (let [[f v] args] (str "{" (field-name f) ": {_lte: " (render-value v) "}}"))
        :>=          (let [[f v] args] (str "{" (field-name f) ": {_gte: " (render-value v) "}}"))
        :is-null     (let [[f]   args] (str "{" (field-name f) ": {_is_null: true}}"))
        :not-null    (let [[f]   args] (str "{" (field-name f) ": {_is_null: false}}"))
        :contains    (let [[f v] args] (str "{" (field-name f) ": {_ilike: " (render-value (str "%" v "%")) "}}"))
        :starts-with (let [[f v] args] (str "{" (field-name f) ": {_ilike: " (render-value (str v "%")) "}}"))
        :and         (str "{_and: [" (str/join ", " (map render-filter args)) "]}")
        :or          (str "{_or: ["  (str/join ", " (map render-filter args)) "]}")
        :not         (str "{_not: "  (render-filter (first args)) "}")
        (throw (ex-info (str "Unsupported MBQL filter operator: " op)
                        {:hasura/error-type :hasura.error/unsupported :operator op}))))))

;; ---------------------------------------------------------------------------
;; Task 4.3 — Aggregate field rendering
;; ---------------------------------------------------------------------------

(defn render-aggregate-fields
  "Render a vector of MBQL aggregation clauses into the body of Hasura's
  aggregate { ... } block.  Aggregations of the same type are grouped so
  each operator appears once with all its field arguments."
  [aggregations]
  (let [groups    (group-by first aggregations)
        field-for (fn [agg-type]
                    (->> (get groups agg-type)
                         (keep #(get % 1))
                         (map field-name)))]
    (str/join " "
              (keep identity
                    [(when (get groups :count) "count")
                     (let [fs (field-for :sum)] (when (seq fs) (str "sum { " (str/join " " fs) " }")))
                     (let [fs (field-for :avg)] (when (seq fs) (str "avg { " (str/join " " fs) " }")))
                     (let [fs (field-for :min)] (when (seq fs) (str "min { " (str/join " " fs) " }")))
                     (let [fs (field-for :max)] (when (seq fs) (str "max { " (str/join " " fs) " }")))]))))

;; ---------------------------------------------------------------------------
;; Task 4.2 — Query rendering
;; ---------------------------------------------------------------------------

(defn- render-order-by [order-by]
  (when (seq order-by)
    (str "order_by: ["
         (str/join ", " (map (fn [[direction field]]
                               (str "{" (field-name field) ": " (name direction) "}"))
                             order-by))
         "]")))

(defn render-select-query
  "Render a select-style MBQL inner-query to {:query \"...GraphQL...\"}."
  [{:keys [source-table fields filter order-by limit offset]}]
  (let [field-list (if (seq fields)
                     (str/join " " (map field-name fields))
                     "__typename")
        args       (keep identity
                         [(when filter (str "where: " (render-filter filter)))
                          (render-order-by order-by)
                          (when limit  (str "limit: "  limit))
                          (when offset (str "offset: " offset))])
        args-str   (when (seq args) (str "(" (str/join ", " args) ")"))]
    {:query (str "{ " source-table args-str " { " field-list " } }")}))

(defn render-aggregate-query
  "Render an aggregate MBQL inner-query to {:query \"...GraphQL...\"}."
  [{:keys [source-table aggregation filter]}]
  (let [agg-body  (render-aggregate-fields aggregation)
        where-str (when filter (str "(where: " (render-filter filter) ")"))
        table-agg (str source-table "_aggregate")]
    {:query (str "{ " table-agg where-str " { aggregate { " agg-body " } } }")}))

;; ---------------------------------------------------------------------------
;; Task 4.2 — Main entry point
;; ---------------------------------------------------------------------------

(defn mbql->native
  "Compile a supported MBQL inner-query map to {:query \"...GraphQL...\"}."
  [inner-query]
  (validate-supported-subset inner-query)
  (if (seq (:aggregation inner-query))
    (render-aggregate-query inner-query)
    (render-select-query inner-query)))
