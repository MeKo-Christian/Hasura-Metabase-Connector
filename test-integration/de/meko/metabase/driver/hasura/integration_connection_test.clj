(ns de.meko.metabase.driver.hasura.integration-connection-test
  "Integration tests for connection testing against the live Docker stack.

  Requires the stack to be running:
    make stack-up

  Run with:
    make test-integration
    clojure -M:test-integration

  Secret resolution order (mirrors docker-compose.yml):
    1. HASURA_SECRET env var  (explicit test override)
    2. HASURA_GRAPHQL_ADMIN_SECRET env var  (same name docker-compose.yml reads)
    3. HASURA_GRAPHQL_ADMIN_SECRET entry in .env file  (docker-compose reads this automatically)
    4. Hardcoded default \"local-admin-secret\""
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [de.meko.metabase.driver.hasura.config :as config]
            [de.meko.metabase.driver.hasura.client :as client]))

;; ---------------------------------------------------------------------------
;; .env reader — same source docker-compose uses automatically
;; ---------------------------------------------------------------------------

(defn- read-dotenv
  "Parse `file` as a KEY=value .env file and return a map of string→string.
  Ignores blank lines and comment lines starting with #."
  [file]
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

(defn- env
  "Return the value for `key`, checking JVM env first then .env file."
  [key]
  (or (System/getenv key) (get dotenv key)))

;; ---------------------------------------------------------------------------
;; Stack coordinates
;; ---------------------------------------------------------------------------

(def ^:private hasura-url
  (or (env "HASURA_URL") "http://localhost:6080"))

(def ^:private hasura-secret
  (or (env "HASURA_SECRET")
      (env "HASURA_GRAPHQL_ADMIN_SECRET")
      "local-admin-secret"))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest integration-ping!-success
  (testing "ping! returns true against a live Hasura instance with valid credentials"
    (let [cfg (config/connection-config {:endpoint     hasura-url
                                         :admin-secret hasura-secret})]
      (is (true? (client/ping! cfg))))))

(deftest integration-ping!-auth-failure
  (testing "ping! throws :hasura.error/auth when the admin secret is wrong"
    (let [cfg (config/connection-config {:endpoint     hasura-url
                                         :admin-secret "wrong-secret-for-test"})]
      (let [err (try (client/ping! cfg)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/auth (:hasura/error-type err)))))))

(deftest integration-ping!-unreachable
  (testing "ping! throws :hasura.error/unreachable when the endpoint is not listening"
    (let [cfg (config/connection-config {:endpoint     "http://localhost:19999"
                                         :admin-secret hasura-secret})]
      (let [err (try (client/ping! cfg)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/unreachable (:hasura/error-type err)))))))

(deftest integration-metadata-post-success
  (testing "metadata-post returns export_metadata from a live Hasura instance"
    (let [cfg (config/connection-config {:endpoint     hasura-url
                                         :admin-secret hasura-secret})]
      (let [result (client/metadata-post cfg {:type "export_metadata" :args {}})]
        ;; Hasura 2.27.0 returns the metadata object directly (no resource_version wrapper).
        (is (= 3 (:version result)))
        (is (seq (:sources result)))))))
