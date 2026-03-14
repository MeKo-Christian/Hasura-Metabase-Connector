(ns de.meko.metabase.driver.hasura.sync-test
  "Unit tests for sync model construction and type mapping (Tasks 2.4 / 2.5)."
  (:require [clojure.test :refer [deftest testing is are]]
            [de.meko.metabase.driver.hasura.sync :as sync]
            [de.meko.metabase.driver.hasura.introspection :as introspection]
            [de.meko.metabase.driver.hasura.fixtures :as f]))

;; ---------------------------------------------------------------------------
;; Shared fixtures — parsed and enriched schema maps (no HTTP)
;; ---------------------------------------------------------------------------

(def ^:private schema-map
  (introspection/parse-schema
   (get-in f/introspection-response [:data :__schema])))

(def ^:private enriched-schema-map
  (introspection/enrich-with-metadata schema-map f/metadata-export-response))

;; ---------------------------------------------------------------------------
;; Type mapping
;; ---------------------------------------------------------------------------

(deftest type-mapping-scalars
  (testing "graphql-scalar->base-type maps all documented scalar types"
    (are [gql expected] (= expected (sync/graphql-scalar->base-type gql))
      "Int"         :type/Integer
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
      "bytea"       :type/SerializedBytes)))

(deftest type-mapping-unknown-falls-back
  (testing "unknown scalar type maps to :type/*"
    (is (= :type/* (sync/graphql-scalar->base-type "SomeCustomScalar")))
    (is (= :type/* (sync/graphql-scalar->base-type nil)))
    (is (= :type/* (sync/graphql-scalar->base-type "")))))

;; ---------------------------------------------------------------------------
;; describe-database*
;; ---------------------------------------------------------------------------

(deftest describe-database-returns-table-names
  (testing "describe-database* returns a set containing all expected table names"
    (let [result (sync/describe-database* schema-map)
          names  (set (map :name (:tables result)))]
      (is (= #{"authors" "articles" "tags" "article_tags"
               "orders" "order_items" "article_stats"}
             names)))))

(deftest describe-database-returns-set
  (testing "describe-database* wraps tables in a set"
    (let [result (sync/describe-database* schema-map)]
      (is (set? (:tables result))))))

(deftest describe-database-table-entries-have-schema
  (testing "describe-database* table entries include :schema"
    (let [tables (:tables (sync/describe-database* schema-map))]
      (is (every? #(= "public" (:schema %)) tables)))))

;; ---------------------------------------------------------------------------
;; describe-table*
;; ---------------------------------------------------------------------------

(deftest describe-table-returns-fields
  (testing "describe-table* returns column descriptors for the authors table"
    (let [result (sync/describe-table* schema-map "authors")
          names  (set (map :name (:fields result)))]
      (is (= #{"id" "name" "email" "created_at"} names)))))

(deftest describe-table-field-base-types
  (testing "describe-table* produces correct :base-type for authors columns"
    (let [fields  (:fields (sync/describe-table* schema-map "authors"))
          by-name (into {} (map (juxt :name :base-type) fields))]
      (is (= :type/Integer            (get by-name "id")))
      (is (= :type/Text               (get by-name "name")))
      (is (= :type/Text               (get by-name "email")))
      (is (= :type/DateTimeWithLocalTZ (get by-name "created_at"))))))

(deftest describe-table-field-database-type
  (testing "describe-table* preserves raw GraphQL type name in :database-type"
    (let [fields  (:fields (sync/describe-table* schema-map "authors"))
          by-name (into {} (map (juxt :name :database-type) fields))]
      (is (= "Int"         (get by-name "id")))
      (is (= "timestamptz" (get by-name "created_at"))))))

(deftest describe-table-excludes-relationship-fields
  (testing "describe-table* excludes relationship (non-scalar) fields"
    (let [names (set (map :name (:fields (sync/describe-table* schema-map "authors"))))]
      ;; 'articles' is a LIST relationship on authors — must not appear
      (is (not (contains? names "articles"))))
    (let [names (set (map :name (:fields (sync/describe-table* schema-map "articles"))))]
      ;; 'author' is an OBJECT relationship on articles — must not appear
      (is (not (contains? names "author"))))))

(deftest describe-table-returns-set
  (testing "describe-table* wraps fields in a set"
    (is (set? (:fields (sync/describe-table* schema-map "authors"))))))

;; ---------------------------------------------------------------------------
;; Stability / determinism
;; ---------------------------------------------------------------------------

(deftest sync-ordering-is-deterministic
  (testing "describe-database* returns the same table set on repeated calls"
    (is (= (sync/describe-database* schema-map)
           (sync/describe-database* schema-map))))
  (testing "describe-table* returns the same field set on repeated calls"
    (is (= (sync/describe-table* schema-map "authors")
           (sync/describe-table* schema-map "authors")))))

;; ---------------------------------------------------------------------------
;; Task 2.5 — regression: introspection-only vs metadata-enriched paths
;; ---------------------------------------------------------------------------

(deftest describe-database*-introspection-only-and-enriched-agree-on-table-names
  (testing "introspection-only and metadata-enriched describe-database* return the same table names"
    (let [plain-names   (set (map :name (:tables (sync/describe-database* schema-map))))
          enriched-names (set (map :name (:tables (sync/describe-database* enriched-schema-map))))]
      (is (= plain-names enriched-names)))))

(deftest describe-database*-enriched-uses-metadata-schema
  (testing "metadata-enriched schema has :schema values from metadata, not the synthetic default"
    ;; Both happen to be 'public' in the fixture, but the value must be present and correct.
    (let [tables (:tables (sync/describe-database* enriched-schema-map))]
      (is (every? #(= "public" (:schema %)) tables)))))

;; ---------------------------------------------------------------------------
;; Task 2.5 — negative: unknown table and role-filtered (empty) schema
;; ---------------------------------------------------------------------------

(deftest describe-table*-unknown-table-returns-empty-fields
  (testing "describe-table* returns {:fields #{}} for a table name not in the schema-map"
    (is (= {:fields #{}} (sync/describe-table* schema-map "nonexistent_table")))))

(deftest describe-database*-empty-schema-returns-empty-set
  (testing "describe-database* on an empty schema (e.g. role-filtered) returns {:tables #{}}"
    (let [empty-schema {:sources [{:name "default" :tables []}]}]
      (is (= {:tables #{}} (sync/describe-database* empty-schema))))))
