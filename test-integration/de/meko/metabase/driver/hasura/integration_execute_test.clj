(ns de.meko.metabase.driver.hasura.integration-execute-test
  "Integration tests for native GraphQL query execution against the live Docker stack.

  Requires the stack to be running:
    make stack-up

  Run with:
    make test-integration
    clojure -M:test-integration"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [de.meko.metabase.driver.hasura.execute :as execute]))

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

(def ^:private hasura-url
  (or (env "HASURA_URL") "http://localhost:6080"))
(def ^:private hasura-secret
  (or (env "HASURA_SECRET") (env "HASURA_GRAPHQL_ADMIN_SECRET") "local-admin-secret"))

(defn- make-query [gql-text]
  {:database {:details {:endpoint     hasura-url
                        :admin-secret hasura-secret}}
   :native   {:query gql-text}})

(defn- run-query [gql-text]
  (let [result (atom nil)]
    (execute/execute-reducible-query
     (make-query gql-text)
     (fn [r] (reset! result r)))
    @result))

;; ---------------------------------------------------------------------------
;; Task 3.4 — simple table reads
;; ---------------------------------------------------------------------------

(deftest integration-simple-table-read
  (testing "simple list query returns rows and columns"
    (let [result (run-query "{ authors { id name email } }")]
      (is (= #{"id" "name" "email"} (set (map :name (:cols result)))))
      (is (pos? (count (:rows result))))
      (is (every? vector? (:rows result))))))

(deftest integration-all-authors-seeded
  (testing "seeded authors table returns three rows"
    (let [result (run-query "{ authors { id name } }")]
      (is (= 3 (count (:rows result)))))))

;; ---------------------------------------------------------------------------
;; Task 3.4 — nested relationship reads
;; ---------------------------------------------------------------------------

(deftest integration-nested-object-relationship
  (testing "nested object relationship is flattened with dot-path column names"
    (let [result (run-query "{ articles { id title author { id name } } }")
          names  (set (map :name (:cols result)))]
      (is (contains? names "author.id"))
      (is (contains? names "author.name"))
      (is (contains? names "id"))
      (is (contains? names "title")))))

;; ---------------------------------------------------------------------------
;; Task 3.4 — aggregate-style queries
;; ---------------------------------------------------------------------------

(deftest integration-aggregate-query
  (testing "aggregate query root map is treated as a single-row result"
    (let [result (run-query "{ order_items_aggregate { aggregate { count } } }")]
      (is (= 1 (count (:rows result))) "aggregate query produces exactly one row")
      (is (some #(= "aggregate.count" (:name %)) (:cols result))))))

;; ---------------------------------------------------------------------------
;; Task 3.4 — negative tests
;; ---------------------------------------------------------------------------

(deftest integration-mutation-rejected
  (testing "mutation is rejected before reaching Hasura"
    (let [err (try (run-query "mutation { insert_authors(objects: {name: \"x\"}) { affected_rows } }")
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :hasura.error/forbidden (:hasura/error-type err))))))

(deftest integration-subscription-rejected
  (testing "subscription is rejected before reaching Hasura"
    (let [err (try (run-query "subscription { authors { id } }")
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :hasura.error/forbidden (:hasura/error-type err))))))

(deftest integration-malformed-graphql
  (testing "malformed GraphQL returns :hasura.error/graphql from Hasura"
    (let [err (try (run-query "{ nonexistent_field_xyz { id } }")
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :hasura.error/graphql (:hasura/error-type err))))))
