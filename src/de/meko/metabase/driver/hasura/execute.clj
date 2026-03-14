(ns de.meko.metabase.driver.hasura.execute
  "Native GraphQL query execution and result-set construction.

  Entry point: execute-reducible-query (Phase 3 — Task 3.2)

  Pipeline:
    1. config/guard-query! — reject mutations/subscriptions before any HTTP call
    2. client/graphql-post — execute the GraphQL text against Hasura
    3. extract-root-list   — find the first list field in the response :data map
    4. flatten-rows        — convert list-of-objects to {:cols [...] :rows [...]}

  Flattening rules (documented in COMPATIBILITY.md):
    - Root field must be a list of objects; otherwise an error is thrown.
    - Each top-level object becomes one row.
    - Nested objects are flattened to dot-path column names: author.name
    - Arrays inside objects are JSON-serialised to a text column.
    - Null values are preserved as nil.
    - _aggregate shapes: scalar aggregate fields become columns.

  The flatten result is fed into Metabase's reducible-query callback using
  the standard {:cols [...] :rows [[v v v] ...]} shape."
  (:require
   [de.meko.metabase.driver.hasura.config :as config]))

;; guard-query! lives in config.clj (no Metabase deps) so it can be
;; unit-tested without a full Metabase checkout.
;; Use config/guard-query! directly wherever needed in this namespace.

;; ---------------------------------------------------------------------------
;; Result flattening helpers (Phase 3 — Task 3.3)
;; ---------------------------------------------------------------------------

;; (defn dot-path
;;   "Join a path sequence into a dot-separated column name.
;;   (dot-path [\"author\" \"name\"]) => \"author.name\""
;;   [path]
;;   (clojure.string/join "." (map name path)))

;; (defn flatten-object
;;   "Recursively flatten a single result object into a seq of [col-path value]
;;   pairs, using dot notation for nested objects and JSON strings for arrays."
;;   ([obj] (flatten-object obj []))
;;   ([obj path]
;;    (mapcat (fn [[k v]]
;;              (let [sub-path (conj path (name k))]
;;                (cond
;;                  (map? v)        (flatten-object v sub-path)
;;                  (sequential? v) [[sub-path (cheshire.core/generate-string v)]]
;;                  :else           [[sub-path v]])))
;;            obj)))

;; (defn flatten-rows
;;   "Convert a list of GraphQL result objects into {:cols [...] :rows [...]}.
;;   Column order is derived from the first object and held stable across rows."
;;   [objects]
;;   (let [pairs-seq  (map flatten-object objects)
;;         col-order  (->> pairs-seq first (map first) (map dot-path))
;;         col-index  (into {} (map-indexed (fn [i p] [(dot-path p) i]) (map first (first pairs-seq))))]
;;     {:cols (mapv (fn [n] {:name n :base_type :type/*}) col-order)
;;      :rows (mapv (fn [pairs]
;;                    (let [row (make-array Object (count col-order))]
;;                      (doseq [[path v] pairs]
;;                        (aset row (col-index (dot-path path)) v))
;;                      (vec row)))
;;                  objects)}))

;; ---------------------------------------------------------------------------
;; TODO Phase 3 — Task 3.2: implement execute-reducible-query.
;; ---------------------------------------------------------------------------

;; (defn execute-reducible-query
;;   "Execute a native GraphQL query and call `respond` with the result set.
;;   `query` is the Metabase native query map: {:native {:query \"...\"}
;;                                               :database {:details {...}}}."
;;   [query respond]
;;   (let [details   (get-in query [:database :details])
;;         cfg       (de.meko.metabase.driver.hasura/connection-config details)
;;         gql-text  (get-in query [:native :query])]
;;     (config/guard-query! gql-text)
;;     (let [resp     (client/graphql-post cfg {:query gql-text})
;;           root-val (-> resp :data vals first)]
;;       (when-not (sequential? root-val)
;;         (throw (ex-info "Native query root field must return a list of objects."
;;                         {:hasura/error-type :hasura.error/bad-response
;;                          :root-value        root-val})))
;;       (respond (flatten-rows root-val)))))
