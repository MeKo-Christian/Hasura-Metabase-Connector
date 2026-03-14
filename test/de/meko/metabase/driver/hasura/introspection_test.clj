(ns de.meko.metabase.driver.hasura.introspection-test
  "Unit tests for GraphQL introspection parsing (Task 2.2) and Metadata API
  enrichment (Task 2.3)."
  (:require [clojure.test :refer [deftest testing is]]
            [de.meko.metabase.driver.hasura.introspection :as introspection]
            [de.meko.metabase.driver.hasura.client :as client]
            [de.meko.metabase.driver.hasura.fixtures :as f]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- schema []
  (get-in f/introspection-response [:data :__schema]))

(defn- parsed []
  (introspection/parse-schema (schema)))

;; ---------------------------------------------------------------------------
;; Task 2.2 — table discovery
;; ---------------------------------------------------------------------------

(deftest parse-schema-returns-table-list
  (testing "parse-schema extracts all table-like fields from fixture introspection response"
    (let [tables (get-in (parsed) [:sources 0 :tables])]
      (is (= 7 (count tables))
          "Expected 7 tables: authors, articles, tags, article_tags, orders, order_items, article_stats")
      (is (= #{"authors" "articles" "tags" "article_tags" "orders" "order_items" "article_stats"}
             (set (map :name tables)))))))

(deftest parse-schema-excludes-aggregate-fields
  (testing "parse-schema filters out _aggregate root fields"
    (let [table-names (map :name (get-in (parsed) [:sources 0 :tables]))]
      (is (not-any? #(clojure.string/ends-with? % "_aggregate") table-names))
      (is (not-any? #(clojure.string/ends-with? % "_by_pk") table-names))
      (is (not-any? #(clojure.string/ends-with? % "_stream") table-names)))))

(deftest parse-schema-stable-ids
  (testing "repeated calls to parse-schema produce the same table identifiers"
    (let [ids-1 (map :id (get-in (parsed) [:sources 0 :tables]))
          ids-2 (map :id (get-in (parsed) [:sources 0 :tables]))]
      (is (= ids-1 ids-2))
      ;; IDs are schema-qualified
      (is (every? #(clojure.string/starts-with? % "public.") ids-1)))))

(deftest extract-columns-scalar-only
  (testing "parse-schema returns only scalar fields, not relationship fields"
    (let [tables    (get-in (parsed) [:sources 0 :tables])
          authors   (first (filter #(= "authors" (:name %)) tables))
          articles  (first (filter #(= "articles" (:name %)) tables))]
      (testing "authors: scalar columns present"
        (is (= #{"id" "name" "email" "created_at"}
               (set (map :name (:fields authors)))))
        ;; 'articles' is a relationship LIST field — must be excluded
        (is (not-any? #(= "articles" (:name %)) (:fields authors))))
      (testing "articles: scalar columns present"
        (is (= #{"id" "title" "body" "author_id" "published" "word_count" "created_at"}
               (set (map :name (:fields articles)))))
        ;; 'author' is an OBJECT relationship field — must be excluded
        (is (not-any? #(= "author" (:name %)) (:fields articles)))))))

(deftest parse-schema-field-types
  (testing "gql-type is set to the leaf scalar name"
    (let [tables  (get-in (parsed) [:sources 0 :tables])
          authors (first (filter #(= "authors" (:name %)) tables))
          id-col  (first (filter #(= "id" (:name %)) (:fields authors)))]
      (is (= "Int" (:gql-type id-col)))))
  (testing "nullable? is false when the top-level type is NON_NULL"
    ;; Fixture uses NON_NULL(SCALAR) for all column fields — none are nullable.
    (let [tables  (get-in (parsed) [:sources 0 :tables])
          authors (first (filter #(= "authors" (:name %)) tables))]
      (is (not-any? :nullable? (:fields authors))))))

(deftest unwrap-type-follows-chain
  (testing "unwrap-type resolves through multiple wrappers to reach the leaf"
    (let [non-null-scalar {:kind "NON_NULL"
                           :ofType {:kind "SCALAR" :name "Int" :ofType nil}}
          list-of-scalar  {:kind "LIST"
                           :ofType {:kind "NON_NULL"
                                    :ofType {:kind "SCALAR" :name "String" :ofType nil}}}]
      (is (= {:kind "SCALAR" :name "Int" :ofType nil}
             (introspection/unwrap-type non-null-scalar)))
      (is (= {:kind "SCALAR" :name "String" :ofType nil}
             (introspection/unwrap-type list-of-scalar))))))

;; ---------------------------------------------------------------------------
;; Task 2.3 — metadata enrichment (pure enrich-with-metadata)
;; ---------------------------------------------------------------------------

(deftest enrich-with-metadata-fallback
  (testing "enrich-with-metadata returns introspection result unchanged when metadata response is nil"
    (is (= (parsed) (introspection/enrich-with-metadata (parsed) nil)))))

(deftest enrich-with-metadata-overlays-source-name
  (testing "enrich-with-metadata uses Hasura source name from export_metadata"
    (let [sources (:sources (introspection/enrich-with-metadata (parsed) f/metadata-export-response))]
      (is (= 1 (count sources)))
      (is (= "default" (-> sources first :name))))))

(deftest enrich-with-metadata-updates-schema-and-id
  (testing "enrich-with-metadata sets :schema and :id from metadata for each table"
    (let [tables (get-in (introspection/enrich-with-metadata (parsed) f/metadata-export-response)
                         [:sources 0 :tables])]
      (is (every? #(= "public" (:schema %)) tables))
      (is (every? #(clojure.string/starts-with? (:id %) "public.") tables)))))

(deftest enrich-with-metadata-preserves-fields
  (testing "enrich-with-metadata keeps introspection-derived :fields on each table"
    (let [tables  (get-in (introspection/enrich-with-metadata (parsed) f/metadata-export-response)
                          [:sources 0 :tables])
          authors (first (filter #(= "authors" (:name %)) tables))]
      (is (seq (:fields authors)))
      (is (= #{"id" "name" "email" "created_at"} (set (map :name (:fields authors))))))))

(deftest enrich-with-metadata-includes-only-tracked-tables
  (testing "enrich-with-metadata includes only tables tracked in metadata, not all introspection tables"
    (let [meta-count     (count (get-in f/metadata-export-response [:sources 0 :tables]))
          enriched-count (count (get-in (introspection/enrich-with-metadata (parsed) f/metadata-export-response)
                                        [:sources 0 :tables]))]
      (is (= meta-count enriched-count)))))

;; ---------------------------------------------------------------------------
;; Task 2.3 — fetch-metadata behavior
;; ---------------------------------------------------------------------------

(deftest fetch-metadata-returns-nil-when-disabled
  (testing "fetch-metadata returns nil without calling the API when use-metadata-api is false"
    (let [cfg (assoc f/test-conn :use-metadata-api false)]
      (is (nil? (introspection/fetch-metadata cfg))))))

(deftest fetch-metadata-swallows-auth-error
  (testing "fetch-metadata returns nil when the Metadata API returns an auth error"
    (with-redefs [client/metadata-post
                  (fn [_ _] (throw (ex-info "auth" {:hasura/error-type :hasura.error/auth})))]
      (is (nil? (introspection/fetch-metadata f/test-conn))))))

(deftest fetch-metadata-rethrows-server-error
  (testing "fetch-metadata re-throws non-auth errors"
    (with-redefs [client/metadata-post
                  (fn [_ _] (throw (ex-info "server" {:hasura/error-type :hasura.error/server})))]
      (let [err (try (introspection/fetch-metadata f/test-conn)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/server (:hasura/error-type err)))))))

(deftest fetch-metadata-strips-role-from-request
  (testing "fetch-metadata does not forward :role to the Metadata API"
    (let [captured-cfg (atom nil)
          cfg-with-role (assoc f/test-conn :role "analyst")]
      (with-redefs [client/metadata-post
                    (fn [cfg _] (reset! captured-cfg cfg) {:version 3 :sources []})]
        (introspection/fetch-metadata cfg-with-role))
      (is (nil? (:role @captured-cfg))
          "X-Hasura-Role must not be forwarded to /v1/metadata"))))
