(ns de.meko.metabase.driver.hasura.execute-test
  "Unit tests for result flattening and native query execution (Tasks 3.2 / 3.3)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.fake :refer [with-fake-routes]]
            [de.meko.metabase.driver.hasura.execute :as execute]
            [de.meko.metabase.driver.hasura.client :as client]
            [de.meko.metabase.driver.hasura.config :as config]
            [de.meko.metabase.driver.hasura.fixtures :as f]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- col-names [result]
  (mapv :name (:cols result)))

(defn- fake-graphql-post [body-map]
  {:post (fn [_] {:status  200
                  :headers {"content-type" "application/json"}
                  :body    (json/generate-string body-map)})})

(def ^:private test-query
  {:database {:details {:endpoint     f/base-url
                        :admin-secret f/admin-secret}}
   :native   {:query "{ authors { id name } }"}})

;; ---------------------------------------------------------------------------
;; dot-path
;; ---------------------------------------------------------------------------

(deftest dot-path-single-segment
  (testing "single path segment returns the segment as-is"
    (is (= "id" (execute/dot-path ["id"])))))

(deftest dot-path-nested
  (testing "nested path segments are joined with dots"
    (is (= "author.name" (execute/dot-path ["author" "name"])))
    (is (= "a.b.c"       (execute/dot-path ["a" "b" "c"])))))

;; ---------------------------------------------------------------------------
;; flatten-object
;; ---------------------------------------------------------------------------

(deftest flatten-object-flat
  (testing "flat object produces one [path value] pair per key"
    (let [pairs (execute/flatten-object {:id 1 :name "Alice"})]
      (is (= #{[["id"] 1] [["name"] "Alice"]}
             (set pairs))))))

(deftest flatten-object-nested-map
  (testing "nested map is recursively flattened with dot-path"
    (let [pairs (execute/flatten-object {:author {:id 1 :name "Alice"}})]
      (is (= #{[["author" "id"] 1] [["author" "name"] "Alice"]}
             (set pairs))))))

(deftest flatten-object-array-field-serialised
  (testing "sequential field is JSON-serialised to a string"
    (let [arr   [{:id 1 :title "A"} {:id 2 :title "B"}]
          pairs (execute/flatten-object {:articles arr})
          val   (second (first pairs))]
      (is (string? val))
      (is (= arr (json/parse-string val true))))))

(deftest flatten-object-nil-field
  (testing "nil field value is preserved as nil"
    (let [pairs (execute/flatten-object {:body nil})]
      (is (= [[["body"] nil]] pairs)))))

;; ---------------------------------------------------------------------------
;; flatten-rows — Task 3.3
;; ---------------------------------------------------------------------------

(deftest flatten-list-response
  (testing "flatten-rows converts a list-of-objects response to {:cols [...] :rows [...]}"
    (let [objects (get-in f/query-response-list [:data :authors])
          result  (execute/flatten-rows objects)]
      (is (vector? (:cols result)))
      (is (vector? (:rows result)))
      (is (= 3 (count (:rows result))) "three author rows")
      (is (= #{"id" "name" "email" "created_at"} (set (col-names result))))
      (testing "first row contains correct id"
        (let [id-idx (.indexOf (col-names result) "id")]
          (is (= 1 (get (first (:rows result)) id-idx))))))))

(deftest flatten-nested-objects-as-dot-paths
  (testing "nested object fields appear as dot-path column names"
    (let [objects (get-in f/query-response-nested [:data :articles])
          result  (execute/flatten-rows objects)
          names   (set (col-names result))]
      (is (contains? names "author.id"))
      (is (contains? names "author.name"))
      (is (contains? names "id"))
      (is (contains? names "title")))))

(deftest flatten-aggregate-response
  (testing "aggregate map root is wrapped and flattened as a single row"
    (let [root-val (get-in f/query-response-aggregate [:data :order_items_aggregate])
          result   (execute/flatten-rows [root-val])
          names    (set (col-names result))]
      (is (= 1 (count (:rows result))) "one aggregate row")
      (is (contains? names "aggregate.count"))
      (is (contains? names "aggregate.sum.quantity"))
      (is (contains? names "aggregate.avg.unit_price")))))

(deftest flatten-array-field-serialised
  (testing "array field in a result row is serialised to a JSON string column"
    (let [objects (get-in f/query-response-with-array [:data :authors])
          result  (execute/flatten-rows objects)
          names   (col-names result)
          art-idx (.indexOf names "articles")
          art-val (get (first (:rows result)) art-idx)]
      (is (string? art-val) "articles field should be a JSON string")
      (is (sequential? (json/parse-string art-val true))
          "JSON string should parse back to a sequence"))))

(deftest flatten-null-values-preserved
  (testing "null values in a result object are preserved as nil"
    (let [result (execute/flatten-rows [{:id 1 :body nil}])
          names  (col-names result)
          idx    (.indexOf names "body")]
      (is (nil? (get (first (:rows result)) idx))))))

(deftest flatten-empty-list
  (testing "flatten-rows returns {:cols [] :rows []} for an empty list"
    (is (= {:cols [] :rows []} (execute/flatten-rows [])))))

(deftest flatten-rows-column-order-stable
  (testing "column order is consistent across repeated calls with the same input"
    (let [objects (get-in f/query-response-list [:data :authors])]
      (is (= (col-names (execute/flatten-rows objects))
             (col-names (execute/flatten-rows objects)))))))

;; ---------------------------------------------------------------------------
;; execute-reducible-query — Task 3.2
;; ---------------------------------------------------------------------------

(deftest execute-reducible-query-success
  (testing "execute-reducible-query calls respond with flattened rows"
    (with-fake-routes
      {(str f/base-url "/v1/graphql")
       (fake-graphql-post f/query-response-list)}
      (let [result (atom nil)]
        (execute/execute-reducible-query
         test-query
         (fn [r] (reset! result r)))
        (is (= 3 (count (:rows @result))))
        (is (= #{"id" "name" "email" "created_at"}
               (set (col-names @result))))))))

(deftest execute-reducible-query-rejects-mutation
  (testing "execute-reducible-query throws :hasura.error/forbidden for mutations"
    (let [mutation-query (assoc-in test-query [:native :query]
                                   "mutation { insert_authors(objects: {name: \"x\"}) { affected_rows } }")]
      (let [err (try (execute/execute-reducible-query mutation-query identity)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/forbidden (:hasura/error-type err)))))))

(deftest execute-reducible-query-rejects-subscription
  (testing "execute-reducible-query throws :hasura.error/forbidden for subscriptions"
    (let [sub-query (assoc-in test-query [:native :query]
                               "subscription { authors { id } }")]
      (let [err (try (execute/execute-reducible-query sub-query identity)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/forbidden (:hasura/error-type err)))))))

(deftest execute-reducible-query-bad-root-throws
  (testing "execute-reducible-query throws :hasura.error/bad-response when root is a scalar"
    (with-fake-routes
      {(str f/base-url "/v1/graphql")
       (fake-graphql-post {:data {:scalar_result 42}})}
      (let [err (try (execute/execute-reducible-query test-query identity)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/bad-response (:hasura/error-type err)))))))

(deftest execute-reducible-query-aggregate-root
  (testing "execute-reducible-query accepts a map root value (aggregate query)"
    (with-fake-routes
      {(str f/base-url "/v1/graphql")
       (fake-graphql-post f/query-response-aggregate)}
      (let [result (atom nil)]
        (execute/execute-reducible-query
         test-query
         (fn [r] (reset! result r)))
        (is (= 1 (count (:rows @result))) "one aggregate row")))))

(deftest execute-reducible-query-propagates-graphql-error
  (testing "execute-reducible-query propagates :hasura.error/graphql from client"
    (with-fake-routes
      {(str f/base-url "/v1/graphql")
       (fake-graphql-post f/error-graphql-validation)}
      (let [err (try (execute/execute-reducible-query test-query identity)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/graphql (:hasura/error-type err)))))))
