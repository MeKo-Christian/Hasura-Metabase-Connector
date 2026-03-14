(ns de.meko.metabase.driver.hasura.client-test
  "Unit tests for the Hasura HTTP client layer.

  Header generation is tested directly via config/request-headers (the pure
  function that client.clj delegates to).

  HTTP-level tests use clj-http-fake to intercept requests without a live
  Hasura instance.  Network error tests use with-redefs to inject Java
  exceptions that clj-http would throw in real failure scenarios."
  (:require [clojure.test :refer [deftest testing is]]
            [clj-http.fake :refer [with-fake-routes]]
            [cheshire.core :as json]
            [de.meko.metabase.driver.hasura.config :as config]
            [de.meko.metabase.driver.hasura.client :as client]
            [de.meko.metabase.driver.hasura.fixtures :as fixtures]))

;; ---------------------------------------------------------------------------
;; Header behaviour — tested via config/request-headers directly.
;; These replace the pending tests from Phase 0.
;; ---------------------------------------------------------------------------

(deftest headers-admin-secret-sent
  (testing "X-Hasura-Admin-Secret header is produced for admin-secret config"
    (let [cfg (config/connection-config {:endpoint "https://h.example.com"
                                         :admin-secret "my-secret"})]
      (is (= "my-secret" (get (config/request-headers cfg) "X-Hasura-Admin-Secret"))))))

(deftest headers-bearer-token-sent
  (testing "Authorization: Bearer is produced when only access-token is set"
    (let [cfg (config/connection-config {:endpoint "https://h.example.com"
                                         :access-token "my-jwt"})]
      (is (= "Bearer my-jwt" (get (config/request-headers cfg) "Authorization"))))))

(deftest headers-admin-secret-wins-over-token
  (testing "Authorization header is absent when admin-secret is also set"
    (let [cfg (config/connection-config {:endpoint "https://h.example.com"
                                         :admin-secret "secret"
                                         :access-token "token"})]
      (is (contains? (config/request-headers cfg) "X-Hasura-Admin-Secret"))
      (is (not (contains? (config/request-headers cfg) "Authorization"))))))

(deftest headers-role-appended
  (testing "X-Hasura-Role is added when role is non-nil"
    (let [cfg (config/connection-config {:endpoint "https://h.example.com"
                                         :admin-secret "secret"
                                         :role "analyst"})]
      (is (= "analyst" (get (config/request-headers cfg) "X-Hasura-Role"))))))

;; ---------------------------------------------------------------------------
;; Shared test config
;; ---------------------------------------------------------------------------

(def ^:private test-cfg
  (config/connection-config {:endpoint      fixtures/base-url
                              :admin-secret  fixtures/admin-secret}))

(defn- fake-json-post
  "Return a clj-http-fake route handler that responds with `status` and `body-map`."
  [status body-map]
  {:post (fn [_] {:status  status
                  :headers {"content-type" "application/json"}
                  :body    (json/generate-string body-map)})})

;; ---------------------------------------------------------------------------
;; graphql-post — success path
;; ---------------------------------------------------------------------------

(deftest client-graphql-post-success
  (testing "graphql-post returns parsed response data on 200"
    (with-fake-routes
      {(str fixtures/base-url "/v1/graphql")
       (fake-json-post 200 {:data {:__typename "query_root"}})}
      (let [result (client/graphql-post test-cfg {:query "{ __typename }"})]
        (is (= {:__typename "query_root"} (:data result)))))))

;; ---------------------------------------------------------------------------
;; graphql-post — HTTP error paths
;; ---------------------------------------------------------------------------

(deftest client-graphql-post-auth-failure
  (testing "graphql-post throws :hasura.error/auth on 401"
    (with-fake-routes
      {(str fixtures/base-url "/v1/graphql")
       (fake-json-post 401 {:error "invalid x-hasura-admin-secret"})}
      (let [err (try (client/graphql-post test-cfg {:query "{ __typename }"})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/auth (:hasura/error-type err)))
        (is (= 401 (:status err)))))))

(deftest client-graphql-post-forbidden
  (testing "graphql-post throws :hasura.error/auth on 403"
    (with-fake-routes
      {(str fixtures/base-url "/v1/graphql")
       (fake-json-post 403 {:error "forbidden"})}
      (let [err (try (client/graphql-post test-cfg {:query "{ __typename }"})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/auth (:hasura/error-type err)))
        (is (= 403 (:status err)))))))

(deftest client-graphql-post-server-error
  (testing "graphql-post throws :hasura.error/server on 5xx"
    (with-fake-routes
      {(str fixtures/base-url "/v1/graphql")
       (fake-json-post 500 {:error "Internal Server Error"})}
      (let [err (try (client/graphql-post test-cfg {:query "{ __typename }"})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/server (:hasura/error-type err)))
        (is (= 500 (:status err)))))))

;; ---------------------------------------------------------------------------
;; graphql-post — GraphQL error envelope
;; ---------------------------------------------------------------------------

(deftest client-graphql-post-graphql-access-denied
  (testing "graphql-post throws :hasura.error/auth when Hasura returns access-denied in errors"
    ;; Hasura 2.x returns HTTP 200 + access-denied error for invalid admin secret.
    (with-fake-routes
      {(str fixtures/base-url "/v1/graphql")
       (fake-json-post 200 fixtures/error-graphql-auth)}
      (let [err (try (client/graphql-post test-cfg {:query "{ __typename }"})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/auth (:hasura/error-type err)))))))

(deftest client-graphql-post-graphql-error
  (testing "graphql-post throws :hasura.error/graphql when body contains non-auth errors"
    (with-fake-routes
      {(str fixtures/base-url "/v1/graphql")
       (fake-json-post 200 fixtures/error-graphql-validation)}
      (let [err (try (client/graphql-post test-cfg {:query "{ nonexistent }"})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/graphql (:hasura/error-type err)))
        (is (seq (:errors err)))))))

(deftest client-graphql-partial-errors
  (testing "graphql-post throws :hasura.error/graphql when response has both :data and :errors (strict MVP behavior)"
    ;; Hasura can return a partial result: some data fields succeed while others
    ;; fail.  The driver deliberately treats any :errors presence as a failure
    ;; in the MVP, rather than surfacing partial data silently.
    (with-fake-routes
      {(str fixtures/base-url "/v1/graphql")
       (fake-json-post 200 {:data   {:authors [{:id 1}]}
                            :errors [{:message  "field 'nonexistent' not found"
                                      :extensions {:code "validation-failed"}}]})}
      (let [err (try (client/graphql-post test-cfg {:query "{ authors { id nonexistent } }"})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/graphql (:hasura/error-type err)))
        (is (seq (:errors err)))))))

;; ---------------------------------------------------------------------------
;; graphql-post — network error paths (injected via with-redefs)
;; ---------------------------------------------------------------------------

(deftest client-graphql-post-timeout
  (testing "graphql-post throws :hasura.error/timeout on SocketTimeoutException"
    (with-redefs [clj-http.client/post
                  (fn [& _] (throw (java.net.SocketTimeoutException. "Read timed out")))]
      (let [err (try (client/graphql-post test-cfg {:query "{ __typename }"})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/timeout (:hasura/error-type err)))))))

(deftest client-graphql-post-unreachable
  (testing "graphql-post throws :hasura.error/unreachable on ConnectException"
    (with-redefs [clj-http.client/post
                  (fn [& _] (throw (java.net.ConnectException. "Connection refused")))]
      (let [err (try (client/graphql-post test-cfg {:query "{ __typename }"})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/unreachable (:hasura/error-type err)))))))

;; ---------------------------------------------------------------------------
;; ping! — success and error propagation
;; ---------------------------------------------------------------------------

(deftest ping!-success
  (testing "ping! returns true on a successful __typename response"
    (with-fake-routes
      {(str fixtures/base-url "/v1/graphql")
       (fake-json-post 200 {:data {:__typename "query_root"}})}
      (is (true? (client/ping! test-cfg))))))

(deftest ping!-auth-failure
  (testing "ping! propagates :hasura.error/auth"
    (with-fake-routes
      {(str fixtures/base-url "/v1/graphql")
       (fake-json-post 401 {:error "invalid credentials"})}
      (let [err (try (client/ping! test-cfg)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/auth (:hasura/error-type err)))))))

(deftest ping!-unreachable
  (testing "ping! propagates :hasura.error/unreachable"
    (with-redefs [clj-http.client/post
                  (fn [& _] (throw (java.net.ConnectException. "Connection refused")))]
      (let [err (try (client/ping! test-cfg)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/unreachable (:hasura/error-type err)))))))

;; ---------------------------------------------------------------------------
;; metadata-post — success and error paths (Task 2.1)
;; ---------------------------------------------------------------------------

(deftest client-metadata-post-success
  (testing "metadata-post returns parsed export_metadata result on 200"
    (with-fake-routes
      {(str fixtures/base-url "/v1/metadata")
       (fake-json-post 200 fixtures/metadata-export-response)}
      (let [result (client/metadata-post test-cfg {:type "export_metadata" :args {}})]
        (is (= 3 (:version result)))
        (is (seq (:sources result)))))))

(deftest client-metadata-post-auth-failure
  (testing "metadata-post throws :hasura.error/auth on 401"
    (with-fake-routes
      {(str fixtures/base-url "/v1/metadata")
       (fake-json-post 401 {:error "invalid x-hasura-admin-secret"})}
      (let [err (try (client/metadata-post test-cfg {:type "export_metadata" :args {}})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/auth (:hasura/error-type err)))))))

(deftest client-metadata-post-server-error
  (testing "metadata-post throws :hasura.error/server on 500"
    (with-fake-routes
      {(str fixtures/base-url "/v1/metadata")
       (fake-json-post 500 {:error "Internal Server Error"})}
      (let [err (try (client/metadata-post test-cfg {:type "export_metadata" :args {}})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :hasura.error/server (:hasura/error-type err)))))))

(deftest client-timeout-propagated
  (testing "request-timeout-ms from connection config is passed to clj-http"
    (let [captured-opts (atom nil)
          cfg (config/connection-config {:endpoint      fixtures/base-url
                                         :admin-secret  fixtures/admin-secret
                                         :request-timeout-ms 5000})]
      (with-redefs [clj-http.client/post
                    (fn [_ opts]
                      (reset! captured-opts opts)
                      {:status 200
                       :headers {"content-type" "application/json"}
                       :body {:data {:__typename "query_root"}}})]
        (client/graphql-post cfg {:query "{ __typename }"}))
      (is (= 5000 (:socket-timeout @captured-opts)))
      (is (= 5000 (:conn-timeout @captured-opts))))))
