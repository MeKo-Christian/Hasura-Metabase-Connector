(ns de.meko.metabase.driver.hasura.config-test
  "Unit tests for connection-config normalisation, validate-config!,
  request-headers, and guard-query!.

  These tests require no Metabase classpath dependency — config.clj is
  a pure utility namespace."
  (:require [clojure.test :refer [deftest testing is are]]
            [de.meko.metabase.driver.hasura.config :as config]))

;; ─────────────────────────────────────────────────────────────────────────────
;; connection-config
;; ─────────────────────────────────────────────────────────────────────────────

(deftest connection-config-endpoint-normalisation
  (testing "trailing slashes are stripped from endpoint"
    (are [raw expected] (= expected (:endpoint (config/connection-config {:endpoint raw})))
      "https://hasura.example.com/"   "https://hasura.example.com"
      "https://hasura.example.com///" "https://hasura.example.com"
      "https://hasura.example.com"    "https://hasura.example.com"))

  (testing "whitespace is trimmed from endpoint"
    (is (= "https://hasura.example.com"
           (:endpoint (config/connection-config {:endpoint "  https://hasura.example.com  "})))))

  (testing "nil endpoint passes through as nil"
    (is (nil? (:endpoint (config/connection-config {}))))))

(deftest connection-config-auth-fields
  (testing "non-empty admin-secret is preserved"
    (is (= "my-secret"
           (:admin-secret (config/connection-config {:admin-secret "my-secret"})))))

  (testing "empty string admin-secret becomes nil"
    (is (nil? (:admin-secret (config/connection-config {:admin-secret ""})))))

  (testing "whitespace-only admin-secret becomes nil"
    (is (nil? (:admin-secret (config/connection-config {:admin-secret "   "})))))

  (testing "absent admin-secret is nil"
    (is (nil? (:admin-secret (config/connection-config {})))))

  (testing "non-empty access-token is preserved"
    (is (= "bearer-token"
           (:access-token (config/connection-config {:access-token "bearer-token"})))))

  (testing "empty access-token becomes nil"
    (is (nil? (:access-token (config/connection-config {:access-token ""})))))

  (testing "role is normalised the same way"
    (is (= "admin" (:role (config/connection-config {:role "admin"}))))
    (is (nil?      (:role (config/connection-config {:role ""}))))
    (is (nil?      (:role (config/connection-config {}))))))

(deftest connection-config-use-metadata-api
  (testing "defaults to true when key is absent"
    (is (true? (:use-metadata-api (config/connection-config {})))))

  (testing "explicit true is preserved"
    (is (true? (:use-metadata-api (config/connection-config {:use-metadata-api true})))))

  (testing "explicit false is respected"
    (is (false? (:use-metadata-api (config/connection-config {:use-metadata-api false})))))

  (testing "truthy non-boolean is coerced to boolean true"
    (is (true? (:use-metadata-api (config/connection-config {:use-metadata-api 1}))))))

(deftest connection-config-timeout
  (testing "defaults to 30000 when key is absent"
    (is (= 30000 (:timeout-ms (config/connection-config {})))))

  (testing "positive integer is used as-is"
    (is (= 10000 (:timeout-ms (config/connection-config {:request-timeout-ms 10000})))))

  (testing "zero is rejected and default is used"
    (is (= 30000 (:timeout-ms (config/connection-config {:request-timeout-ms 0})))))

  (testing "negative value is rejected and default is used"
    (is (= 30000 (:timeout-ms (config/connection-config {:request-timeout-ms -1})))))

  (testing "nil value is rejected and default is used"
    (is (= 30000 (:timeout-ms (config/connection-config {:request-timeout-ms nil}))))))

(deftest connection-config-all-fields-together
  (testing "full details map produces expected config"
    (let [cfg (config/connection-config
               {:endpoint            "https://hasura.example.com/"
                :admin-secret        "secret123"
                :access-token        nil
                :role                "editor"
                :use-metadata-api    false
                :request-timeout-ms  15000})]
      (is (= "https://hasura.example.com" (:endpoint cfg)))
      (is (= "secret123"                  (:admin-secret cfg)))
      (is (nil?                           (:access-token cfg)))
      (is (= "editor"                     (:role cfg)))
      (is (false?                         (:use-metadata-api cfg)))
      (is (= 15000                        (:timeout-ms cfg))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; guard-query!
;; ─────────────────────────────────────────────────────────────────────────────

(deftest guard-query-allows-select-queries
  (testing "shorthand query {field} is allowed"
    (is (nil? (config/guard-query! "{ authors { id name } }"))))

  (testing "explicit query keyword is allowed"
    (is (nil? (config/guard-query! "query { authors { id } }"))))

  (testing "query with operation name is allowed"
    (is (nil? (config/guard-query! "query GetAuthors { authors { id } }"))))

  (testing "nil is allowed (treated as no query)"
    (is (nil? (config/guard-query! nil))))

  (testing "empty string is allowed"
    (is (nil? (config/guard-query! "")))))

(deftest guard-query-rejects-mutations
  (testing "mutation keyword throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/guard-query! "mutation { insert_authors(objects: {name: \"x\"}) { affected_rows } }"))))

  (testing "Mutation with capital letter throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/guard-query! "Mutation { insert_authors(objects: {}) { affected_rows } }"))))

  (testing "mutation with leading whitespace throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/guard-query! "  mutation { do_something { id } }"))))

  (testing "thrown exception has correct error type"
    (try
      (config/guard-query! "mutation { x { id } }")
      (catch clojure.lang.ExceptionInfo e
        (is (= :hasura.error/forbidden (-> e ex-data :hasura/error-type)))
        (is (= "mutation"              (-> e ex-data :operation)))))))

(deftest guard-query-rejects-subscriptions
  (testing "subscription keyword throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/guard-query! "subscription { authors { id } }"))))

  (testing "thrown exception has correct error type"
    (try
      (config/guard-query! "subscription { x { id } }")
      (catch clojure.lang.ExceptionInfo e
        (is (= :hasura.error/forbidden (-> e ex-data :hasura/error-type)))))))

(deftest guard-query-allows-field-starting-with-mutation-word
  (testing "a query field named mutation_log is not rejected"
    ;; The guard matches the OPERATION KEYWORD, not field names.
    ;; Shorthand query with a field called mutation_something must pass.
    (is (nil? (config/guard-query! "{ mutation_log { id } }")))))

;; ─────────────────────────────────────────────────────────────────────────────
;; validate-config!
;; ─────────────────────────────────────────────────────────────────────────────

(deftest validate-config-accepts-valid-configs
  (testing "http endpoint passes"
    (is (= "http://localhost:8080"
           (:endpoint (config/validate-config! {:endpoint "http://localhost:8080"})))))

  (testing "https endpoint passes"
    (is (some? (config/validate-config! {:endpoint "https://hasura.example.com"}))))

  (testing "returns cfg unchanged on success (allows threading)"
    (let [cfg {:endpoint "https://hasura.example.com" :timeout-ms 5000}]
      (is (= cfg (config/validate-config! cfg)))))

  (testing "open Hasura with no auth fields passes"
    (is (some? (config/validate-config! {:endpoint "http://localhost:8080"
                                         :admin-secret nil
                                         :access-token nil}))))

  (testing "endpoint with port and path passes"
    (is (some? (config/validate-config! {:endpoint "https://hasura.example.com:443/graphql-prefix"})))))

(deftest validate-config-rejects-missing-endpoint
  (testing "nil endpoint throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config! {:endpoint nil}))))

  (testing "absent endpoint key throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config! {}))))

  (testing "blank endpoint throws (already nil after connection-config)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config! (config/connection-config {:endpoint "   "})))))

  (testing "error carries correct type and field"
    (try
      (config/validate-config! {:endpoint nil})
      (catch clojure.lang.ExceptionInfo e
        (is (= :hasura.error/config-invalid (-> e ex-data :hasura/error-type)))
        (is (= :endpoint                    (-> e ex-data :field)))))))

(deftest validate-config-rejects-non-http-schemes
  (testing "ftp:// scheme throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config! {:endpoint "ftp://hasura.example.com"}))))

  (testing "//host (scheme-relative) throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config! {:endpoint "//hasura.example.com"}))))

  (testing "bare hostname throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config! {:endpoint "hasura.example.com"}))))

  (testing "error carries the bad value"
    (try
      (config/validate-config! {:endpoint "ftp://bad.example.com"})
      (catch clojure.lang.ExceptionInfo e
        (is (= :hasura.error/config-invalid (-> e ex-data :hasura/error-type)))
        (is (= "ftp://bad.example.com"      (-> e ex-data :value)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; request-headers
;; ─────────────────────────────────────────────────────────────────────────────

(deftest request-headers-always-includes-content-type
  (testing "Content-Type and Accept are always present"
    (let [h (config/request-headers {})]
      (is (= "application/json" (get h "Content-Type")))
      (is (= "application/json" (get h "Accept"))))))

(deftest request-headers-admin-secret
  (testing "X-Hasura-Admin-Secret is set when admin-secret is non-nil"
    (let [h (config/request-headers {:admin-secret "my-secret"})]
      (is (= "my-secret" (get h "X-Hasura-Admin-Secret")))))

  (testing "X-Hasura-Admin-Secret is absent when admin-secret is nil"
    (let [h (config/request-headers {:admin-secret nil})]
      (is (not (contains? h "X-Hasura-Admin-Secret"))))))

(deftest request-headers-bearer-token
  (testing "Authorization Bearer is set when only access-token is present"
    (let [h (config/request-headers {:admin-secret nil :access-token "jwt-token"})]
      (is (= "Bearer jwt-token" (get h "Authorization")))))

  (testing "Authorization is absent when access-token is nil"
    (let [h (config/request-headers {:admin-secret nil :access-token nil})]
      (is (not (contains? h "Authorization"))))))

(deftest request-headers-auth-priority
  (testing "when both admin-secret and access-token are set, admin-secret wins"
    (let [h (config/request-headers {:admin-secret "secret" :access-token "token"})]
      (is (= "secret" (get h "X-Hasura-Admin-Secret")))
      ;; access-token must NOT be sent alongside admin-secret
      (is (not (contains? h "Authorization")))))

  (testing "no auth headers at all when both are nil (open Hasura)"
    (let [h (config/request-headers {:admin-secret nil :access-token nil})]
      (is (not (contains? h "X-Hasura-Admin-Secret")))
      (is (not (contains? h "Authorization"))))))

(deftest request-headers-role
  (testing "X-Hasura-Role is set when role is non-nil"
    (let [h (config/request-headers {:role "editor"})]
      (is (= "editor" (get h "X-Hasura-Role")))))

  (testing "X-Hasura-Role is absent when role is nil"
    (let [h (config/request-headers {:role nil})]
      (is (not (contains? h "X-Hasura-Role")))))

  (testing "X-Hasura-Role is sent alongside admin-secret"
    (let [h (config/request-headers {:admin-secret "secret" :role "admin"})]
      (is (= "secret" (get h "X-Hasura-Admin-Secret")))
      (is (= "admin"  (get h "X-Hasura-Role")))))

  (testing "X-Hasura-Role is sent alongside bearer token"
    (let [h (config/request-headers {:admin-secret nil :access-token "tok" :role "viewer"})]
      (is (= "Bearer tok" (get h "Authorization")))
      (is (= "viewer"     (get h "X-Hasura-Role"))))))

(deftest request-headers-full-config
  (testing "full config from connection-config normalisation round-trip"
    (let [cfg (config/connection-config {:endpoint         "https://hasura.example.com/"
                                         :admin-secret     "s3cr3t"
                                         :access-token     "ignored-when-secret-present"
                                         :role             "analyst"
                                         :use-metadata-api true
                                         :request-timeout-ms 10000})
          h   (config/request-headers cfg)]
      (is (= "s3cr3t"           (get h "X-Hasura-Admin-Secret")))
      (is (= "analyst"          (get h "X-Hasura-Role")))
      (is (not (contains? h "Authorization")))
      (is (= "application/json" (get h "Content-Type"))))))
