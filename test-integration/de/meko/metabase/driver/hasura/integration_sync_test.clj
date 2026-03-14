(ns de.meko.metabase.driver.hasura.integration-sync-test
  "Integration tests for sync model construction against the live Docker stack.

  Requires the stack to be running:
    make stack-up

  Run with:
    make test-integration
    clojure -M:test-integration"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [de.meko.metabase.driver.hasura.config :as config]
            [de.meko.metabase.driver.hasura.sync :as sync]))

;; ---------------------------------------------------------------------------
;; Stack coordinates
;; ---------------------------------------------------------------------------

(defn- read-dotenv [file]
  (try
    (when (.exists (io/file file))
      (->> (str/split-lines (slurp file))
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           (keep #(let [i (str/index-of % "=")]
                    (when (pos? (or i -1))
                      [(str/trim (subs % 0 i))
                       (str/trim (subs % (inc i)))])))
           (into {})))
    (catch Exception _ nil)))

(def ^:private dotenv (read-dotenv ".env"))
(defn- env [k] (or (System/getenv k) (get dotenv k)))

(def ^:private cfg
  (config/connection-config
   {:endpoint         (or (env "HASURA_URL") "http://localhost:6080")
    :admin-secret     (or (env "HASURA_SECRET")
                          (env "HASURA_GRAPHQL_ADMIN_SECRET")
                          "local-admin-secret")
    :use-metadata-api true}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest integration-describe-database
  (testing "describe-database returns a set of tables from the seeded schema"
    (let [result (sync/describe-database cfg)
          names  (set (map :name (:tables result)))]
      (is (set? (:tables result)))
      (is (contains? names "authors"))
      (is (contains? names "articles"))
      (is (contains? names "orders"))
      (is (contains? names "order_items"))
      (testing "table entries include :schema"
        (is (every? #(string? (:schema %)) (:tables result)))))))

(deftest integration-describe-table-authors
  (testing "describe-table returns fields for the authors table"
    (let [result (sync/describe-table cfg "authors")
          names  (set (map :name (:fields result)))]
      (is (set? (:fields result)))
      (is (contains? names "id"))
      (is (contains? names "name"))
      (is (contains? names "email"))
      (testing "fields have :base-type"
        (is (every? #(keyword? (:base-type %)) (:fields result))))
      (testing "fields have :database-type"
        (is (every? #(string? (:database-type %)) (:fields result)))))))

(deftest integration-describe-table-order-items
  (testing "describe-table returns numeric fields for the order_items table"
    (let [fields  (:fields (sync/describe-table cfg "order_items"))
          by-name (into {} (map (juxt :name :base-type) fields))]
      (is (= :type/Integer (get by-name "id")))
      (is (= :type/Decimal (get by-name "unit_price")))
      (is (= :type/Integer (get by-name "quantity"))))))

(deftest integration-sync-is-stable
  (testing "describe-database returns identical results on two consecutive calls"
    (is (= (sync/describe-database cfg)
           (sync/describe-database cfg)))))

;; ---------------------------------------------------------------------------
;; Task 2.5 — regression: introspection-only fallback
;; ---------------------------------------------------------------------------

(deftest integration-describe-database-introspection-only
  (testing "describe-database with use-metadata-api=false still returns the full table set"
    (let [no-meta-cfg (config/connection-config
                       {:endpoint         (or (env "HASURA_URL") "http://localhost:6080")
                        :admin-secret     (or (env "HASURA_SECRET")
                                              (env "HASURA_GRAPHQL_ADMIN_SECRET")
                                              "local-admin-secret")
                        :use-metadata-api false})
          result (sync/describe-database no-meta-cfg)
          names  (set (map :name (:tables result)))]
      (is (set? (:tables result)))
      (is (contains? names "authors"))
      (is (contains? names "articles"))
      (is (contains? names "order_items")))))

;; ---------------------------------------------------------------------------
;; Task 2.5 — negative: role-filtered schema (no select_permissions configured)
;; ---------------------------------------------------------------------------

(deftest integration-role-restricted-schema
  (testing "describe-database with a role that has no select_permissions returns empty tables"
    ;; The seed data has no select_permissions for any role.  Using
    ;; X-Hasura-Role: analyst with the admin secret applies analyst's
    ;; permissions (none), so query_root has no visible fields.
    (let [analyst-cfg (config/connection-config
                       {:endpoint         (or (env "HASURA_URL") "http://localhost:6080")
                        :admin-secret     (or (env "HASURA_SECRET")
                                              (env "HASURA_GRAPHQL_ADMIN_SECRET")
                                              "local-admin-secret")
                        :role             "analyst"
                        :use-metadata-api true})
          result (sync/describe-database analyst-cfg)]
      (is (set? (:tables result)))
      (is (empty? (:tables result))
          "analyst role has no permissions — no tables should be visible"))))

;; ---------------------------------------------------------------------------
;; Task 2.5 — negative: unknown table
;; ---------------------------------------------------------------------------

(deftest integration-describe-table-nonexistent
  (testing "describe-table for a table not in the schema returns {:fields #{}}"
    (is (= {:fields #{}} (sync/describe-table cfg "nonexistent_table")))))
