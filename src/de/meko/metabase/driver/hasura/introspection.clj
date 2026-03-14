(ns de.meko.metabase.driver.hasura.introspection
  "GraphQL introspection parsing and Metadata API enrichment.

  Responsibilities:
    1. Run the introspection query against /v1/graphql (via client.clj).
    2. Parse the __schema response into an internal :hasura/schema map.
    3. Optionally enrich the result with export_metadata from /v1/metadata.

  Internal schema map shape (produced by this namespace, consumed by sync.clj):

    {:sources
     [{:name   \"default\"          ; source name (from metadata or synthetic)
       :tables
       [{:id     \"public.authors\"  ; stable identifier
         :schema \"public\"
         :name   \"authors\"         ; root query field name
         :fields
         [{:name      \"id\"
           :gql-type  \"Int\"
           :nullable? false}
          ...]}]}]}

  Discovery modes (Phase 2 — Tasks 2.2, 2.3):
    - introspection-only  Always available.
    - metadata-enriched   Uses export_metadata to recover source/schema names
                          and relationship metadata.  Falls back automatically.

  All functions are pure (data-in, data-out) except for fetch-schema and
  fetch-metadata which perform HTTP calls via client.clj."
  (:require
   [clojure.string :as str]
   [de.meko.metabase.driver.hasura.client :as client]))

;; ---------------------------------------------------------------------------
;; GraphQL introspection query
;; ---------------------------------------------------------------------------

(def ^:private introspection-query
  "The introspection query sent to Hasura.
   We fetch the root query type fields and each referenced type's scalar fields.

   Three levels of ofType are required to fully unwrap the deepest real Hasura
   type shape: NON_NULL(LIST(NON_NULL(OBJECT))), i.e. [T!]! on root query fields.
   Two levels are insufficient — the leaf OBJECT name would be invisible."
  "{
    __schema {
      queryType { name }
      types {
        kind
        name
        fields(includeDeprecated: false) {
          name
          type {
            kind
            name
            ofType { kind name ofType { kind name ofType { kind name } } }
          }
        }
      }
    }
  }")

;; ---------------------------------------------------------------------------
;; Phase 2 — Task 2.2: pure parsing helpers
;; ---------------------------------------------------------------------------

(defn unwrap-type
  "Follow ofType chains to find the named type at the leaf.
  Handles NON_NULL and LIST wrappers."
  [t]
  (if (:ofType t) (recur (:ofType t)) t))

(defn table-like-field?
  "Return true if a root query field should be treated as a Metabase table.
  Excludes aggregate, pk-lookup, and streaming subscription fields emitted
  by Hasura alongside every table."
  [field]
  (let [n (:name field)]
    (and (not (str/ends-with? n "_aggregate"))
         (not (str/ends-with? n "_by_pk"))
         (not (str/ends-with? n "_stream")))))

(defn scalar-field?
  "Return true if a type field is a leaf scalar column (not a relationship).
  Unwraps NON_NULL / LIST wrappers before checking the kind."
  [field]
  (= "SCALAR" (:kind (unwrap-type (:type field)))))

(defn stable-table-id
  "Produce a stable string identifier for a table from schema and field name."
  [schema-name table-name]
  (str schema-name "." table-name))

(defn parse-schema
  "Convert a raw __schema map (as returned by Hasura introspection) into the
  internal :hasura/schema map consumed by sync.clj.

  `schema` is the value at [:data :__schema] in the introspection response."
  [schema]
  (let [type-index  (->> (:types schema)
                         (filter #(= (:kind %) "OBJECT"))
                         (into {} (map (juxt :name identity))))
        root-name   (get-in schema [:queryType :name])
        root-fields (->> (get-in type-index [root-name :fields])
                         (filter table-like-field?))]
    {:sources
     [{:name   "default"
       :tables (mapv (fn [f]
                       (let [type-name (-> f :type unwrap-type :name)
                             cols      (->> (get-in type-index [type-name :fields])
                                           (filter scalar-field?))]
                         {:id     (stable-table-id "public" (:name f))
                          :schema "public"
                          :name   (:name f)
                          :fields (mapv (fn [c]
                                          {:name      (:name c)
                                           :gql-type  (-> c :type unwrap-type :name)
                                           :nullable? (not= (get-in c [:type :kind]) "NON_NULL")})
                                        cols)}))
                     root-fields)}]}))

(defn fetch-schema
  "Fetch the GraphQL introspection schema from Hasura and parse it.
  `cfg` is a connection-config map.  Returns the internal :hasura/schema map."
  [cfg]
  (-> (client/graphql-post cfg {:query introspection-query})
      (get-in [:data :__schema])
      parse-schema))

;; ---------------------------------------------------------------------------
;; Phase 2 — Task 2.3: Metadata API enrichment.
;; ---------------------------------------------------------------------------

(defn fetch-metadata
  "Fetch Hasura metadata via export_metadata.
  Returns nil when the Metadata API is disabled in cfg or when the caller
  does not have permission (auth error — some Hasura deployments restrict
  the Metadata API to the admin role only).
  Re-throws all other errors (network, server, etc.).

  The Metadata API is admin-only: the role is stripped from cfg before the
  request so that X-Hasura-Role is never forwarded (Hasura 2.x returns
  HTTP 400 when a role header is present on /v1/metadata)."
  [cfg]
  (when (:use-metadata-api cfg)
    (try
      (client/metadata-post (dissoc cfg :role) {:type "export_metadata" :args {}})
      (catch clojure.lang.ExceptionInfo e
        (if (= :hasura.error/auth (-> e ex-data :hasura/error-type))
          nil
          (throw e))))))

(defn enrich-with-metadata
  "Overlay source and schema information from export_metadata onto a parsed
  schema map produced by parse-schema.

  If `metadata-resp` is nil the function returns `schema-map` unchanged,
  giving a clean introspection-only fallback.

  When metadata is present the output `:sources` mirrors the metadata source
  list.  For each metadata table the matching introspection entry is found by
  name, its `:schema` and `:id` are updated from the metadata, and its
  `:fields` (derived from introspection) are preserved.  Tables that appear in
  introspection but are not tracked in metadata are excluded from the result."
  [schema-map metadata-resp]
  (if-not metadata-resp
    schema-map
    (let [table-index (->> (:sources schema-map)
                           (mapcat :tables)
                           (into {} (map (juxt :name identity))))]
      {:sources
       (mapv (fn [meta-src]
               {:name   (:name meta-src)
                :tables (->> (:tables meta-src)
                             (keep (fn [mt]
                                     (let [tname   (get-in mt [:table :name])
                                           tschema (get-in mt [:table :schema])
                                           parsed  (get table-index tname)]
                                       (when parsed
                                         (assoc parsed
                                                :schema tschema
                                                :id     (stable-table-id tschema tname))))))
                             vec)})
             (:sources metadata-resp))})))
