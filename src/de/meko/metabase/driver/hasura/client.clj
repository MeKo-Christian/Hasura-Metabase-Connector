(ns de.meko.metabase.driver.hasura.client
  "HTTP client for Hasura GraphQL Engine.

  This namespace has no Metabase dependencies — it is a pure HTTP + JSON
  layer that works in isolation and can be unit-tested with clj-http-fake.

  Public API:

    (graphql-post  cfg body)   POST to /v1/graphql,  return parsed body.
    (ping!         cfg)        Minimal introspection to test connectivity.
    (metadata-post cfg body)   POST to /v1/metadata, return parsed body.

  Every function accepts a `cfg` map as produced by
  `de.meko.metabase.driver.hasura.config/connection-config`.
  Callers are responsible for calling `config/validate-config!` before
  invoking client functions.

  Error model:
    All errors are thrown as ex-info with a :hasura/error-type key:

    :hasura.error/auth         HTTP 401/403, or HTTP 200 with access-denied GraphQL error
    :hasura.error/client       HTTP 4xx (other)
    :hasura.error/server       HTTP 5xx
    :hasura.error/graphql      Response body contains a non-auth :errors key
    :hasura.error/unreachable  Connection refused / DNS failure
    :hasura.error/timeout      Read timeout exceeded"
  (:require
   [de.meko.metabase.driver.hasura.config :as config]
   [clj-http.client :as http]
   [cheshire.core :as json]))

;; ---------------------------------------------------------------------------
;; Internal URL helpers
;; ---------------------------------------------------------------------------

(defn- graphql-endpoint [cfg]
  (str (:endpoint cfg) "/v1/graphql"))

(defn- metadata-endpoint [cfg]
  (str (:endpoint cfg) "/v1/metadata"))

;; Header construction is delegated to config/request-headers so that it
;; can be unit-tested without requiring the HTTP client layer.

;; ---------------------------------------------------------------------------
;; Internal response normalisation
;; ---------------------------------------------------------------------------

(defn- normalise-response
  "Convert a clj-http response map into either the parsed body or a thrown error."
  [resp url]
  (let [status (:status resp)
        body   (:body resp)]
    (cond
      (#{401 403} status)
      (throw (ex-info "Hasura auth failed. Check admin-secret or bearer token."
                      {:hasura/error-type :hasura.error/auth :status status :url url}))

      (and (>= status 400) (< status 500))
      (throw (ex-info (str "Hasura returned HTTP " status)
                      {:hasura/error-type :hasura.error/client :status status :url url}))

      (>= status 500)
      (throw (ex-info (str "Hasura returned HTTP " status)
                      {:hasura/error-type :hasura.error/server :status status :url url}))

      ;; Hasura 2.x returns HTTP 200 with access-denied in the GraphQL envelope
      ;; when the admin secret is invalid (rather than HTTP 401).
      (= "access-denied" (-> body :errors first :extensions :code))
      (throw (ex-info "Hasura auth failed. Check admin-secret or bearer token."
                      {:hasura/error-type :hasura.error/auth :url url}))

      (seq (:errors body))
      (throw (ex-info (str "GraphQL error: " (-> body :errors first :message))
                      {:hasura/error-type :hasura.error/graphql
                       :errors (:errors body) :url url}))

      :else body)))

;; ---------------------------------------------------------------------------
;; Public API — Task 1.4
;; ---------------------------------------------------------------------------

(defn graphql-post
  "POST a GraphQL request body to /v1/graphql.
  `body` is a Clojure map with :query and optional :variables.
  Returns the parsed response map on success.
  Throws an ex-info with :hasura/error-type on any failure."
  [cfg body]
  (let [url     (graphql-endpoint cfg)
        timeout (:timeout-ms cfg 30000)]
    (try
      (normalise-response
       (http/post url
                  {:headers          (config/request-headers cfg)
                   :body             (json/generate-string body)
                   :socket-timeout   timeout
                   :conn-timeout     timeout
                   :as               :json
                   :coerce           :always
                   :throw-exceptions false})
       url)
      (catch java.net.ConnectException _
        (throw (ex-info "Hasura endpoint unreachable"
                        {:hasura/error-type :hasura.error/unreachable :url url})))
      (catch java.net.SocketTimeoutException _
        (throw (ex-info "Request timed out"
                        {:hasura/error-type :hasura.error/timeout
                         :url url :timeout-ms timeout}))))))

(defn ping!
  "Send a minimal __typename introspection query to verify connectivity.
  Returns true on success, throws on any error."
  [cfg]
  (boolean (:data (graphql-post cfg {:query "{ __typename }"}))))

;; ---------------------------------------------------------------------------
;; Public API — Task 2.1
;; ---------------------------------------------------------------------------

(defn metadata-post
  "POST a Metadata API command to /v1/metadata.
  `body` is a Clojure map with :type and :args.
  Returns the parsed response map on success."
  [cfg body]
  (let [url     (metadata-endpoint cfg)
        timeout (:timeout-ms cfg 30000)]
    (try
      (normalise-response
       (http/post url
                  {:headers          (config/request-headers cfg)
                   :body             (json/generate-string body)
                   :socket-timeout   timeout
                   :conn-timeout     timeout
                   :as               :json
                   :coerce           :always
                   :throw-exceptions false})
       url)
      (catch java.net.ConnectException _
        (throw (ex-info "Hasura endpoint unreachable"
                        {:hasura/error-type :hasura.error/unreachable :url url})))
      (catch java.net.SocketTimeoutException _
        (throw (ex-info "Request timed out"
                        {:hasura/error-type :hasura.error/timeout
                         :url url :timeout-ms timeout}))))))
