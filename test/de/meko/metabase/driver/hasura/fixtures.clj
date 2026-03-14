(ns de.meko.metabase.driver.hasura.fixtures
  "Static HTTP response fixtures for unit tests.

  All fixtures represent realistic Hasura responses for the seed schema defined
  in dev/postgres/init.sql. Unit tests use these to avoid hitting a live
  Hasura instance.

  For HTTP-level injection use clj-http-fake:
    (with-fake-routes
      {\"https://test.local/v1/graphql\" {:post (fn [_] (json-response introspection-response))}}
      (your-test-code))"
  (:require [cheshire.core]))

(def base-url "https://test.local")
(def admin-secret "test-secret")

(defn json-response
  "Wrap a Clojure map into the shape clj-http returns for a JSON response."
  [body]
  {:status 200
   :headers {"content-type" "application/json"}
   :body (cheshire.core/generate-string body)})

;; ---------------------------------------------------------------------------
;; Introspection fixture
;; Represents the __schema response for the seed database (abbreviated).
;; ---------------------------------------------------------------------------

;; Root query fields use the real Hasura shape: [T!]! = NON_NULL(LIST(NON_NULL(OBJECT))).
;; This requires 3 levels of ofType to reach the leaf OBJECT name — matching what
;; the introspection query now fetches.  Column fields use NON_NULL(SCALAR) (1 wrapper).
(def introspection-response
  {:data
   {:__schema
    {:queryType {:name "query_root"}
     :types
     [{:kind "OBJECT"
       :name "query_root"
       :fields
       ;; [T!]! root field type: NON_NULL → LIST → NON_NULL → OBJECT
       [{:name "authors"
         :type {:kind "NON_NULL" :name nil
                :ofType {:kind "LIST" :name nil
                         :ofType {:kind "NON_NULL" :name nil
                                  :ofType {:kind "OBJECT" :name "authors"}}}}}
        {:name "articles"
         :type {:kind "NON_NULL" :name nil
                :ofType {:kind "LIST" :name nil
                         :ofType {:kind "NON_NULL" :name nil
                                  :ofType {:kind "OBJECT" :name "articles"}}}}}
        {:name "tags"
         :type {:kind "NON_NULL" :name nil
                :ofType {:kind "LIST" :name nil
                         :ofType {:kind "NON_NULL" :name nil
                                  :ofType {:kind "OBJECT" :name "tags"}}}}}
        {:name "article_tags"
         :type {:kind "NON_NULL" :name nil
                :ofType {:kind "LIST" :name nil
                         :ofType {:kind "NON_NULL" :name nil
                                  :ofType {:kind "OBJECT" :name "article_tags"}}}}}
        {:name "orders"
         :type {:kind "NON_NULL" :name nil
                :ofType {:kind "LIST" :name nil
                         :ofType {:kind "NON_NULL" :name nil
                                  :ofType {:kind "OBJECT" :name "orders"}}}}}
        {:name "order_items"
         :type {:kind "NON_NULL" :name nil
                :ofType {:kind "LIST" :name nil
                         :ofType {:kind "NON_NULL" :name nil
                                  :ofType {:kind "OBJECT" :name "order_items"}}}}}
        {:name "article_stats"
         :type {:kind "NON_NULL" :name nil
                :ofType {:kind "LIST" :name nil
                         :ofType {:kind "NON_NULL" :name nil
                                  :ofType {:kind "OBJECT" :name "article_stats"}}}}}
        ;; Aggregate fields — not table-like; should be filtered by introspection parser.
        {:name "authors_aggregate"
         :type {:kind "OBJECT" :name "authors_aggregate" :ofType nil}}
        {:name "articles_aggregate"
         :type {:kind "OBJECT" :name "articles_aggregate" :ofType nil}}]}
      {:kind "OBJECT"
       :name "authors"
       :fields
       ;; Column fields use NON_NULL(SCALAR) for non-null columns.
       [{:name "id"         :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "Int"         :ofType nil}}}
        {:name "name"       :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "String"      :ofType nil}}}
        {:name "email"      :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "String"      :ofType nil}}}
        {:name "created_at" :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "timestamptz" :ofType nil}}}
        ;; Relationship: NON_NULL(LIST(NON_NULL(OBJECT))) — must be excluded by scalar-field?
        {:name "articles"
         :type {:kind "NON_NULL" :name nil
                :ofType {:kind "LIST" :name nil
                         :ofType {:kind "NON_NULL" :name nil
                                  :ofType {:kind "OBJECT" :name "articles"}}}}}]}
      {:kind "OBJECT"
       :name "articles"
       :fields
       [{:name "id"         :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "Int"         :ofType nil}}}
        {:name "title"      :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "String"      :ofType nil}}}
        {:name "body"       :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "String"      :ofType nil}}}
        {:name "author_id"  :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "Int"         :ofType nil}}}
        {:name "published"  :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "Boolean"     :ofType nil}}}
        {:name "word_count" :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "Int"         :ofType nil}}}
        {:name "created_at" :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "timestamptz" :ofType nil}}}
        ;; Object relationship — must be excluded by scalar-field?
        {:name "author"     :type {:kind "NON_NULL" :name nil :ofType {:kind "OBJECT" :name "authors"     :ofType nil}}}]}
      {:kind "OBJECT"
       :name "order_items"
       :fields
       [{:name "id"           :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "Int"     :ofType nil}}}
        {:name "order_id"     :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "Int"     :ofType nil}}}
        {:name "product_name" :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "String"  :ofType nil}}}
        {:name "quantity"     :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "Int"     :ofType nil}}}
        {:name "unit_price"   :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "numeric" :ofType nil}}}
        {:name "discount"     :type {:kind "NON_NULL" :name nil :ofType {:kind "SCALAR" :name "numeric" :ofType nil}}}
        ;; Object relationship — must be excluded by scalar-field?
        {:name "order"        :type {:kind "NON_NULL" :name nil :ofType {:kind "OBJECT" :name "orders"  :ofType nil}}}]}]}}})

;; ---------------------------------------------------------------------------
;; Metadata API fixture  (export_metadata, Hasura 2.27.0 cli-migrations-v3)
;;
;; Hasura 2.27.0 returns the metadata object directly from /v1/metadata
;; export_metadata — there is no resource_version wrapper at this version.
;; ---------------------------------------------------------------------------

(def metadata-export-response
  {:version 3
   :sources
   [{:name "default"
     :kind "postgres"
     :tables
     [{:table {:schema "public" :name "authors"}}
      {:table {:schema "public" :name "articles"}}
      {:table {:schema "public" :name "tags"}}
      {:table {:schema "public" :name "article_tags"}}
      {:table {:schema "public" :name "orders"}}
      {:table {:schema "public" :name "order_items"}}
      {:table {:schema "public" :name "article_stats"}}]}]})

;; ---------------------------------------------------------------------------
;; Query response fixtures
;; ---------------------------------------------------------------------------

(def query-response-list
  "Simple list-of-objects response for a SELECT on authors."
  {:data
   {:authors
    [{:id 1 :name "Alice Smith"  :email "alice@example.com" :created_at "2024-01-01T00:00:00Z"}
     {:id 2 :name "Bob Jones"    :email "bob@example.com"   :created_at "2024-01-02T00:00:00Z"}
     {:id 3 :name "Carol White"  :email "carol@example.com" :created_at "2024-01-03T00:00:00Z"}]}})

(def query-response-nested
  "Response with a nested object relationship (articles with author)."
  {:data
   {:articles
    [{:id 1 :title "Introduction to GraphQL" :published true
      :author {:id 1 :name "Alice Smith"}}
     {:id 2 :title "Hasura Deep Dive" :published true
      :author {:id 1 :name "Alice Smith"}}]}})

(def query-response-aggregate
  "Aggregate response shape from order_items_aggregate."
  {:data
   {:order_items_aggregate
    {:aggregate
     {:count 10
      :sum {:quantity 28 :unit_price 459.84 :discount 33.50}
      :avg {:unit_price 45.98}
      :min {:unit_price 9.99}
      :max {:unit_price 199.99}}}}})

(def query-response-with-array
  "Response where one field is an array — must be serialised as JSON string."
  {:data
   {:authors
    [{:id 1 :name "Alice Smith"
      :articles [{:id 1 :title "Introduction to GraphQL"}
                 {:id 2 :title "Hasura Deep Dive"}]}]}})

;; ---------------------------------------------------------------------------
;; Error response fixtures
;; ---------------------------------------------------------------------------

(def error-auth
  {:status 401
   :headers {"content-type" "application/json"}
   :body "{\"error\":\"invalid x-hasura-admin-secret/x-hasura-client-cert\"}"})

(def error-graphql-auth
  "Hasura 2.x response when the admin secret is invalid: HTTP 200 with access-denied."
  {:data nil
   :errors [{:message "invalid x-hasura-admin-secret/x-hasura-access-key"
             :extensions {:code "access-denied" :path "$"}}]})

(def error-graphql-validation
  {:data nil
   :errors [{:message "field 'nonexistent' not found in type: 'query_root'"
             :extensions {:code "validation-failed"
                          :path "$.selectionSet.nonexistent"}}]})

(def error-graphql-permission
  {:data nil
   :errors [{:message "field 'admin_only_table' not found in type: 'query_root'"
             :extensions {:code "validation-failed"}}]})

;; ---------------------------------------------------------------------------
;; Connection config fixture
;; ---------------------------------------------------------------------------

(def test-conn
  {:endpoint      base-url
   :admin-secret  admin-secret
   :role          nil
   :use-metadata-api true
   :request-timeout-ms 5000})
