(ns de.meko.metabase.driver.hasura.execute
  "Native GraphQL query execution and result-set construction.

  Entry point: execute-reducible-query (Phase 3 — Task 3.2)

  Native query contract (Task 3.1):
    - The query text is stored in {:native {:query \"...\"}} by Metabase.
    - Variables are NOT supported in the MVP; all parameterisation must be
      embedded in the query text.
    - Mutations and subscriptions are rejected before any HTTP call via
      config/guard-query!.
    - The driver is strictly read-only.

  Pipeline:
    1. config/guard-query! — reject mutations/subscriptions before any HTTP call
    2. client/graphql-post — execute the GraphQL text against Hasura
    3. root value extraction — first value in the :data map
    4. flatten-rows        — convert result to {:cols [...] :rows [...]}

  Flattening rules (documented in COMPATIBILITY.md):
    - Root list field: each element becomes one row (standard case).
    - Root map field: treated as a single-row result (aggregate query case).
    - Any other root value (scalar, nil): throws :hasura.error/bad-response.
    - Each top-level object becomes one row.
    - Nested objects are flattened to dot-path column names: author.name
    - Arrays inside objects are JSON-serialised to a text column.
    - Null values are preserved as nil.
    - Column order is fixed by the first row; subsequent rows use the same order.

  The flatten result is fed into Metabase's reducible-query callback using
  the standard {:cols [...] :rows [[v v v] ...]} shape."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [de.meko.metabase.driver.hasura.config :as config]
   [de.meko.metabase.driver.hasura.client :as client]))

;; ---------------------------------------------------------------------------
;; Task 3.3: Result flattening helpers
;; ---------------------------------------------------------------------------

(defn dot-path
  "Join a path vector into a dot-separated column name.
  (dot-path [\"author\" \"name\"]) => \"author.name\""
  [path]
  (str/join "." (map name path)))

(defn flatten-object
  "Recursively flatten a single result object into a seq of [col-path value]
  pairs, using dot notation for nested objects and JSON strings for arrays.
  `path` is the accumulated vector of string key segments."
  ([obj] (flatten-object obj []))
  ([obj path]
   (mapcat (fn [[k v]]
              (let [sub-path (conj path (name k))]
                (cond
                  (map? v)        (flatten-object v sub-path)
                  (sequential? v) [[sub-path (json/generate-string v)]]
                  :else           [[sub-path v]])))
            obj)))

(defn flatten-rows
  "Convert a list of GraphQL result objects into {:cols [...] :rows [...]}.

  Column names and order are fixed by the first object.  Subsequent rows use
  the same column order; missing keys become nil, extra keys are ignored.

  Returns {:cols [] :rows []} for an empty list."
  [objects]
  (if (empty? objects)
    {:cols [] :rows []}
    (let [pairs-seq  (mapv flatten-object objects)
          first-pairs (first pairs-seq)
          col-paths   (mapv first first-pairs)
          col-names   (mapv dot-path col-paths)
          col-index   (into {} (map-indexed (fn [i p] [(dot-path p) i]) col-paths))]
      {:cols (mapv (fn [n] {:name n :base_type :type/*}) col-names)
       :rows (mapv (fn [pairs]
                     ;; object-array initialises to null (nil) — missing
                     ;; columns in later rows stay nil automatically.
                     (let [row (object-array (count col-names))]
                       (doseq [[path v] pairs]
                         (when-let [i (get col-index (dot-path path))]
                           (aset row i v)))
                       (vec row)))
                   pairs-seq)})))

;; ---------------------------------------------------------------------------
;; Task 3.2: Execution pipeline
;; ---------------------------------------------------------------------------

(defn execute-reducible-query
  "Execute a native GraphQL query and call `respond` with the result set.

  `query` is the Metabase native query map:
    {:native   {:query \"{ authors { id name } }\"}
     :database {:details {…connection details…}}}

  `respond` is called with {:cols [...] :rows [...]} on success."
  [query respond]
  (let [details  (get-in query [:database :details])
        cfg      (config/connection-config details)
        gql-text (get-in query [:native :query])]
    (config/guard-query! gql-text)
    (let [resp     (client/graphql-post cfg {:query gql-text})
          root-val (-> resp :data vals first)
          objects  (cond
                     (sequential? root-val) root-val
                     (map? root-val)        [root-val]
                     :else
                     (throw (ex-info "Native query root field must return a list or aggregate object."
                                     {:hasura/error-type :hasura.error/bad-response
                                      :root-value        root-val})))]
      (respond (flatten-rows objects)))))
