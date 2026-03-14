(ns de.meko.metabase.driver.hasura.integration-introspection-test
  "Integration tests for introspection parsing and metadata enrichment against
  the live Docker stack.

  Requires the stack to be running:
    make stack-up

  Run with:
    make test-integration
    clojure -M:test-integration"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [de.meko.metabase.driver.hasura.config :as config]
            [de.meko.metabase.driver.hasura.introspection :as introspection]))

;; ---------------------------------------------------------------------------
;; Stack coordinates (same resolution logic as integration-connection-test)
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

(defn- env [key] (or (System/getenv key) (get dotenv key)))

(def ^:private hasura-url    (or (env "HASURA_URL") "http://localhost:6080"))
(def ^:private hasura-secret (or (env "HASURA_SECRET")
                                 (env "HASURA_GRAPHQL_ADMIN_SECRET")
                                 "local-admin-secret"))

(def ^:private cfg
  (config/connection-config {:endpoint         hasura-url
                              :admin-secret     hasura-secret
                              :use-metadata-api true}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest integration-fetch-schema-returns-tables
  (testing "fetch-schema returns a valid internal schema map from a live Hasura instance"
    (let [result (introspection/fetch-schema cfg)]
      (is (seq (:sources result))
          "Result should have at least one source")
      (is (pos? (count (get-in result [:sources 0 :tables])))
          "First source should contain at least one table")
      (testing "table entries have required keys"
        (let [t (first (get-in result [:sources 0 :tables]))]
          (is (string? (:id t)))
          (is (string? (:schema t)))
          (is (string? (:name t)))
          (is (vector? (:fields t))))))))

(deftest integration-fetch-metadata-returns-sources
  (testing "fetch-metadata returns metadata from a live Hasura instance"
    (let [result (introspection/fetch-metadata cfg)]
      (is (some? result) "Metadata should be available with admin credentials")
      (is (seq (:sources result)))
      (is (= "default" (-> result :sources first :name))))))

(deftest integration-enrich-with-metadata-full-pipeline
  (testing "fetch-schema + fetch-metadata + enrich-with-metadata produces an enriched schema"
    (let [schema-map (introspection/fetch-schema cfg)
          metadata   (introspection/fetch-metadata cfg)
          enriched   (introspection/enrich-with-metadata schema-map metadata)]
      (is (seq (:sources enriched)))
      (is (= "default" (-> enriched :sources first :name))
          "Source name should come from metadata")
      (let [tables (get-in enriched [:sources 0 :tables])]
        (is (pos? (count tables)))
        (is (every? #(= "public" (:schema %)) tables)
            "All tables should have schema from metadata")
        (is (every? #(str/starts-with? (:id %) "public.") tables)
            "All table IDs should be schema-qualified")
        (is (every? #(vector? (:fields %)) tables)
            "All tables should preserve their introspection-derived fields")))))

(deftest integration-fetch-metadata-nil-when-disabled
  (testing "fetch-metadata returns nil when use-metadata-api is false"
    (let [cfg-no-meta (config/connection-config {:endpoint         hasura-url
                                                  :admin-secret     hasura-secret
                                                  :use-metadata-api false})]
      (is (nil? (introspection/fetch-metadata cfg-no-meta))))))
