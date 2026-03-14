(ns de.meko.metabase.driver.hasura.sync
  "Metabase sync model construction.

  Converts the internal :hasura/schema map (from introspection.clj) into the
  Clojure maps that Metabase's sync multimethods must return:

    describe-database  →  {:tables #{{:name \"authors\" :schema \"public\"}}}
    describe-table     →  {:fields #{{:name \"id\" :base-type :type/Integer}}}

  GraphQL scalar → Metabase base-type mapping (Task 2.4):

    Int              → :type/Integer
    Float            → :type/Float
    String           → :type/Text
    Boolean          → :type/Boolean
    ID               → :type/Integer
    timestamptz      → :type/DateTimeWithLocalTZ
    timestamp        → :type/DateTime
    date             → :type/Date
    time             → :type/Time
    numeric          → :type/Decimal
    float8           → :type/Float
    jsonb            → :type/JSON
    uuid             → :type/UUID
    bigint           → :type/BigInteger
    (unknown)        → :type/*

  Design rule: sync results must be identical across repeated runs for the
  same unchanged Hasura schema (no randomness, no timestamp-based ordering)."
  (:require
   [de.meko.metabase.driver.hasura.introspection :as introspection]))

;; ---------------------------------------------------------------------------
;; GraphQL scalar → Metabase base-type
;; ---------------------------------------------------------------------------

(def ^:private scalar-type-map
  {"Int"         :type/Integer
   "Float"       :type/Float
   "String"      :type/Text
   "Boolean"     :type/Boolean
   "ID"          :type/Integer
   "timestamptz" :type/DateTimeWithLocalTZ
   "timestamp"   :type/DateTime
   "date"        :type/Date
   "time"        :type/Time
   "numeric"     :type/Decimal
   "float8"      :type/Float
   "float4"      :type/Float
   "int2"        :type/Integer
   "int4"        :type/Integer
   "int8"        :type/BigInteger
   "bigint"      :type/BigInteger
   "text"        :type/Text
   "varchar"     :type/Text
   "bpchar"      :type/Text
   "jsonb"       :type/JSON
   "json"        :type/JSON
   "uuid"        :type/UUID
   "bytea"       :type/SerializedBytes})

(defn graphql-scalar->base-type
  "Map a GraphQL scalar type name to a Metabase base type keyword.
  Returns :type/* for unknown scalars."
  [scalar-name]
  (get scalar-type-map scalar-name :type/*))

;; ---------------------------------------------------------------------------
;; Phase 2 — Task 2.4: pure sync model builders
;; ---------------------------------------------------------------------------

(defn- all-tables [schema-map]
  (mapcat :tables (:sources schema-map)))

(defn describe-database*
  "Return the {:tables ...} map for driver/describe-database.
  Takes the internal schema map from introspection/fetch-schema.
  Pure function — no HTTP calls."
  [schema-map]
  {:tables (->> (all-tables schema-map)
                (map (fn [t] {:name   (:name t)
                              :schema (:schema t)}))
                set)})

(defn describe-table*
  "Return the {:fields ...} map for driver/describe-table.
  `table-name` is the string name of the root query field.
  Pure function — no HTTP calls."
  [schema-map table-name]
  (let [table (->> (all-tables schema-map)
                   (filter #(= (:name %) table-name))
                   first)]
    {:fields (->> (:fields table)
                  (map (fn [f]
                         {:name          (:name f)
                          :base-type     (graphql-scalar->base-type (:gql-type f))
                          :database-type (:gql-type f)
                          :nullable?     (:nullable? f true)}))
                  set)}))

;; ---------------------------------------------------------------------------
;; Public API — composes introspection pipeline with sync model builders
;; ---------------------------------------------------------------------------

(defn- fetch-schema-map [cfg]
  (let [schema-map (introspection/fetch-schema cfg)
        metadata   (introspection/fetch-metadata cfg)]
    (introspection/enrich-with-metadata schema-map metadata)))

(defn describe-database
  "Fetch schema from Hasura and return the {:tables ...} map for
  driver/describe-database.  `cfg` is a connection-config map."
  [cfg]
  (describe-database* (fetch-schema-map cfg)))

(defn describe-table
  "Fetch schema from Hasura and return the {:fields ...} map for
  driver/describe-table.  `cfg` is a connection-config map."
  [cfg table-name]
  (describe-table* (fetch-schema-map cfg) table-name))
