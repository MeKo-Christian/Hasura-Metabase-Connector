(ns de.meko.metabase.driver.hasura.query-processor-test
  "Unit tests for the MBQL → GraphQL compiler (Tasks 4.1–4.5)."
  (:require [clojure.test :refer [deftest testing is are]]
            [de.meko.metabase.driver.hasura.query-processor :as qp]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- f
  "Shorthand for an MBQL string field clause."
  [name] [:field name nil])

(defn- unsupported? [thunk]
  (= :hasura.error/unsupported
     (try (thunk) nil
          (catch clojure.lang.ExceptionInfo e
            (-> e ex-data :hasura/error-type)))))

;; ---------------------------------------------------------------------------
;; field-name (Task 4.2)
;; ---------------------------------------------------------------------------

(deftest field-name-string
  (testing "string field name is returned as-is"
    (is (= "id"   (qp/field-name [:field "id"   nil])))
    (is (= "name" (qp/field-name [:field "name" {:base-type :type/Text}])))))

(deftest field-name-integer-rejected
  (testing "integer field ID throws :hasura.error/unsupported"
    (is (unsupported? #(qp/field-name [:field 42 nil])))))

;; ---------------------------------------------------------------------------
;; validate-supported-subset (Task 4.4)
;; ---------------------------------------------------------------------------

(deftest validate-rejects-joins
  (is (unsupported? #(qp/validate-supported-subset {:joins [{:alias "authors"}]}))))

(deftest validate-rejects-expressions
  (is (unsupported? #(qp/validate-supported-subset {:expressions {:full_name [:concat (f "first") (f "last")]}}))))

(deftest validate-rejects-breakout
  (is (unsupported? #(qp/validate-supported-subset {:breakout [(f "status")]}))))

(deftest validate-accepts-supported-query
  (testing "a plain select query passes validation"
    (let [q {:source-table "authors" :fields [(f "id") (f "name")] :limit 10}]
      (is (= q (qp/validate-supported-subset q))))))

;; ---------------------------------------------------------------------------
;; render-filter (Task 4.3)
;; ---------------------------------------------------------------------------

(deftest render-filter-nil
  (is (nil? (qp/render-filter nil))))

(deftest render-filter-equality
  (are [clause expected] (= expected (qp/render-filter clause))
    [:=  (f "id") 1]   "{id: {_eq: 1}}"
    [:!= (f "id") 1]   "{id: {_neq: 1}}"
    [:<  (f "id") 1]   "{id: {_lt: 1}}"
    [:>  (f "id") 1]   "{id: {_gt: 1}}"
    [:<= (f "id") 1]   "{id: {_lte: 1}}"
    [:>= (f "id") 1]   "{id: {_gte: 1}}"))

(deftest render-filter-string-value
  (testing "string values are JSON-quoted"
    (is (= "{name: {_eq: \"Alice\"}}" (qp/render-filter [:= (f "name") "Alice"])))))

(deftest render-filter-null-checks
  (is (= "{id: {_is_null: true}}"  (qp/render-filter [:is-null  (f "id")])))
  (is (= "{id: {_is_null: false}}" (qp/render-filter [:not-null (f "id")]))))

(deftest render-filter-text-operators
  (is (= "{name: {_ilike: \"%alice%\"}}" (qp/render-filter [:contains    (f "name") "alice"])))
  (is (= "{name: {_ilike: \"alice%\"}}"  (qp/render-filter [:starts-with (f "name") "alice"]))))

(deftest render-filter-boolean-combinators
  (testing ":and wraps sub-filters"
    (is (= "{_and: [{id: {_eq: 1}}, {name: {_eq: \"Alice\"}}]}"
           (qp/render-filter [:and [:= (f "id") 1] [:= (f "name") "Alice"]]))))
  (testing ":or wraps sub-filters"
    (is (= "{_or: [{id: {_eq: 1}}, {id: {_eq: 2}}]}"
           (qp/render-filter [:or [:= (f "id") 1] [:= (f "id") 2]]))))
  (testing ":not negates a sub-filter"
    (is (= "{_not: {id: {_eq: 1}}}"
           (qp/render-filter [:not [:= (f "id") 1]])))))

(deftest render-filter-unsupported-operator
  (is (unsupported? #(qp/render-filter [:between (f "id") 1 10]))))

;; ---------------------------------------------------------------------------
;; render-aggregate-fields (Task 4.3)
;; ---------------------------------------------------------------------------

(deftest render-aggregate-count-only
  (is (= "count" (qp/render-aggregate-fields [[:count]]))))

(deftest render-aggregate-sum
  (is (= "sum { unit_price }"
         (qp/render-aggregate-fields [[:sum (f "unit_price")]]))))

(deftest render-aggregate-multiple-types
  (let [result (qp/render-aggregate-fields
                [[:count]
                 [:sum (f "unit_price")]
                 [:avg (f "unit_price")]
                 [:min (f "unit_price")]
                 [:max (f "unit_price")]])]
    (is (clojure.string/includes? result "count"))
    (is (clojure.string/includes? result "sum { unit_price }"))
    (is (clojure.string/includes? result "avg { unit_price }"))
    (is (clojure.string/includes? result "min { unit_price }"))
    (is (clojure.string/includes? result "max { unit_price }"))))

(deftest render-aggregate-multiple-sum-fields
  (let [result (qp/render-aggregate-fields
                [[:sum (f "unit_price")] [:sum (f "discount")]])]
    (is (clojure.string/includes? result "sum { unit_price discount }"))))

;; ---------------------------------------------------------------------------
;; render-select-query (Task 4.2)
;; ---------------------------------------------------------------------------

(deftest render-select-simple
  (is (= {:query "{ authors { id name } }"}
         (qp/render-select-query
          {:source-table "authors"
           :fields [(f "id") (f "name")]}))))

(deftest render-select-with-filter
  (is (= {:query "{ authors(where: {id: {_eq: 1}}) { id } }"}
         (qp/render-select-query
          {:source-table "authors"
           :fields [(f "id")]
           :filter [:= (f "id") 1]}))))

(deftest render-select-with-order-by
  (is (= {:query "{ authors(order_by: [{id: asc}]) { id } }"}
         (qp/render-select-query
          {:source-table "authors"
           :fields [(f "id")]
           :order-by [[:asc (f "id")]]}))))

(deftest render-select-multi-column-order-by
  (is (= {:query "{ authors(order_by: [{name: asc}, {id: desc}]) { id name } }"}
         (qp/render-select-query
          {:source-table "authors"
           :fields [(f "id") (f "name")]
           :order-by [[:asc (f "name")] [:desc (f "id")]]}))))

(deftest render-select-with-limit-and-offset
  (is (= {:query "{ authors(limit: 10, offset: 5) { id } }"}
         (qp/render-select-query
          {:source-table "authors"
           :fields [(f "id")]
           :limit 10 :offset 5}))))

(deftest render-select-all-args
  (let [{:keys [query]}
        (qp/render-select-query
         {:source-table "authors"
          :fields [(f "id") (f "name")]
          :filter   [:= (f "id") 1]
          :order-by [[:asc (f "name")]]
          :limit    5
          :offset   0})]
    (is (clojure.string/includes? query "authors("))
    (is (clojure.string/includes? query "where:"))
    (is (clojure.string/includes? query "order_by:"))
    (is (clojure.string/includes? query "limit: 5"))
    (is (clojure.string/includes? query "offset: 0"))
    (is (clojure.string/includes? query "{ id name }"))))

(deftest render-select-no-fields-uses-typename
  (testing "empty field list falls back to __typename"
    (let [{:keys [query]} (qp/render-select-query {:source-table "authors"})]
      (is (clojure.string/includes? query "__typename")))))

;; ---------------------------------------------------------------------------
;; render-aggregate-query (Task 4.2)
;; ---------------------------------------------------------------------------

(deftest render-aggregate-count-query
  (is (= {:query "{ order_items_aggregate { aggregate { count } } }"}
         (qp/render-aggregate-query
          {:source-table "order_items"
           :aggregation  [[:count]]}))))

(deftest render-aggregate-with-filter
  (is (= {:query "{ order_items_aggregate(where: {quantity: {_gt: 1}}) { aggregate { count } } }"}
         (qp/render-aggregate-query
          {:source-table "order_items"
           :aggregation  [[:count]]
           :filter       [:> (f "quantity") 1]}))))

(deftest render-aggregate-multi-type-query
  (let [{:keys [query]}
        (qp/render-aggregate-query
         {:source-table "order_items"
          :aggregation  [[:count] [:sum (f "unit_price")] [:avg (f "unit_price")]]})]
    (is (clojure.string/includes? query "order_items_aggregate"))
    (is (clojure.string/includes? query "count"))
    (is (clojure.string/includes? query "sum { unit_price }"))
    (is (clojure.string/includes? query "avg { unit_price }"))))

;; ---------------------------------------------------------------------------
;; mbql->native — composed (Task 4.2 / 4.5)
;; ---------------------------------------------------------------------------

(deftest mbql-native-select
  (is (= {:query "{ authors { id name } }"}
         (qp/mbql->native {:source-table "authors"
                           :fields [(f "id") (f "name")]}))))

(deftest mbql-native-aggregate
  (is (= {:query "{ order_items_aggregate { aggregate { count } } }"}
         (qp/mbql->native {:source-table "order_items"
                           :aggregation  [[:count]]}))))

(deftest mbql-native-rejects-joins
  (is (unsupported? #(qp/mbql->native {:source-table "authors"
                                       :joins [{:alias "articles"}]}))))

(deftest mbql-native-stable-output
  (testing "same MBQL input always produces identical GraphQL output"
    (let [q {:source-table "authors"
             :fields [(f "id") (f "name")]
             :filter [:= (f "id") 1]
             :limit 10}]
      (is (= (qp/mbql->native q) (qp/mbql->native q))))))
