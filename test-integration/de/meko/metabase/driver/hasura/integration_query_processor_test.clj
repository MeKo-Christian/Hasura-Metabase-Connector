(ns de.meko.metabase.driver.hasura.integration-query-processor-test
  "Integration tests for the MBQL → GraphQL → Hasura execution pipeline
  against the live Docker stack (Task 4.5).

  These tests compile MBQL with string field names to GraphQL and then
  execute it through execute-reducible-query, proving the full pipeline
  end-to-end without Metabase's field-ID resolution layer.

  Requires the stack to be running:
    make stack-up

  Run with:
    make test-integration"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [de.meko.metabase.driver.hasura.query-processor :as qp]
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

(defn- f [name] [:field name nil])

(defn- run-mbql [inner-query]
  (let [native (:query (qp/mbql->native inner-query))
        result (atom nil)]
    (execute/execute-reducible-query
     {:database {:details {:endpoint hasura-url :admin-secret hasura-secret}}
      :native   {:query native}}
     (fn [r] (reset! result r)))
    @result))

(defn- col-names [result]
  (mapv :name (:cols result)))

;; ---------------------------------------------------------------------------
;; Task 4.5 — Browse and filter flows
;; ---------------------------------------------------------------------------

(deftest integration-mbql-simple-browse
  (testing "simple table select returns correct columns and all rows"
    (let [result (run-mbql {:source-table "authors"
                            :fields [(f "id") (f "name") (f "email")]})]
      (is (= ["id" "name" "email"] (col-names result)))
      (is (= 3 (count (:rows result)))))))

(deftest integration-mbql-equality-filter
  (testing "= filter returns only matching rows"
    (let [result (run-mbql {:source-table "authors"
                            :fields [(f "id") (f "name")]
                            :filter [:= (f "name") "Alice Smith"]})]
      (is (= 1 (count (:rows result)))))))

(deftest integration-mbql-comparison-filter
  (testing "> filter on numeric field returns matching rows"
    (let [result (run-mbql {:source-table "order_items"
                            :fields [(f "id") (f "unit_price")]
                            :filter [:> (f "unit_price") 50]})]
      (is (pos? (count (:rows result))))
      (let [price-idx (.indexOf (col-names result) "unit_price")]
        (is (every? #(> (Double/parseDouble (str (get % price-idx))) 50)
                    (:rows result)))))))

(deftest integration-mbql-null-filter
  (testing "is-null filter works on nullable field"
    ;; authors.email is nullable per seed schema; all seeded rows have values,
    ;; so is-null should return 0 rows.
    (let [result (run-mbql {:source-table "authors"
                            :fields [(f "id")]
                            :filter [:is-null (f "email")]})]
      (is (= 0 (count (:rows result)))))))

(deftest integration-mbql-and-filter
  (testing ":and filter combines conditions"
    (let [result (run-mbql {:source-table "articles"
                            :fields [(f "id") (f "published")]
                            :filter [:and
                                     [:= (f "published") true]
                                     [:> (f "word_count") 300]]})]
      (is (pos? (count (:rows result)))))))

;; ---------------------------------------------------------------------------
;; Task 4.5 — Sort and pagination
;; ---------------------------------------------------------------------------

(deftest integration-mbql-order-by
  (testing "order_by returns results in sorted order"
    (let [result (run-mbql {:source-table "authors"
                            :fields [(f "id") (f "name")]
                            :order-by [[:asc (f "id")]]})]
      (is (= 3 (count (:rows result))))
      (let [id-idx (.indexOf (col-names result) "id")
            ids    (map #(get % id-idx) (:rows result))]
        (is (= ids (sort ids)))))))

(deftest integration-mbql-limit
  (testing "limit restricts the number of returned rows"
    (let [result (run-mbql {:source-table "authors"
                            :fields [(f "id")]
                            :limit 2})]
      (is (= 2 (count (:rows result)))))))

(deftest integration-mbql-offset
  (testing "offset skips rows (total authors is 3, offset 2 returns 1)"
    (let [result (run-mbql {:source-table "authors"
                            :fields [(f "id")]
                            :order-by [[:asc (f "id")]]
                            :limit 10 :offset 2})]
      (is (= 1 (count (:rows result)))))))

;; ---------------------------------------------------------------------------
;; Task 4.5 — Aggregate flows
;; ---------------------------------------------------------------------------

(deftest integration-mbql-count
  (testing "count aggregate returns one row with the total count"
    (let [result (run-mbql {:source-table "authors"
                            :aggregation  [[:count]]})]
      (is (= 1 (count (:rows result))))
      (is (some #(= "aggregate.count" (:name %)) (:cols result))))))

(deftest integration-mbql-sum-avg
  (testing "sum and avg aggregates return expected numeric columns"
    (let [result (run-mbql {:source-table "order_items"
                            :aggregation  [[:count]
                                           [:sum (f "unit_price")]
                                           [:avg (f "unit_price")]]})]
      (is (= 1 (count (:rows result))))
      (let [names (set (col-names result))]
        (is (contains? names "aggregate.count"))
        (is (contains? names "aggregate.sum.unit_price"))
        (is (contains? names "aggregate.avg.unit_price"))))))

;; ---------------------------------------------------------------------------
;; Task 4.5 — Unsupported query rejection
;; ---------------------------------------------------------------------------

(deftest integration-mbql-join-rejected
  (testing "MBQL with joins throws :hasura.error/unsupported before any HTTP call"
    (let [err (try (run-mbql {:source-table "authors"
                              :fields [(f "id")]
                              :joins [{:alias "articles"}]})
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :hasura.error/unsupported (:hasura/error-type err)))
      (is (= :joins (:feature err))))))

(deftest integration-mbql-integer-field-id-rejected
  (testing "MBQL with integer field IDs throws :hasura.error/unsupported"
    (let [err (try (run-mbql {:source-table "authors"
                              :fields [[:field 42 nil]]})
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :hasura.error/unsupported (:hasura/error-type err))))))
