(ns de.meko.metabase.driver.hasura.config
  "Pure connection-config normalisation, validation, header building, and
  query-guard utilities.

  This namespace has NO Metabase dependencies and can be required from unit
  tests without a Metabase checkout on the classpath.

  Public API:

    connection-config   Convert a raw Metabase details map → normalised config.
    validate-config!    Throw with a clear message if the config is invalid.
    request-headers     Build HTTP request headers from a normalised config.
    guard-query!        Throw if a query string is a mutation or subscription.")

;; ---------------------------------------------------------------------------
;; Connection configuration normalisation
;; ---------------------------------------------------------------------------

(defn connection-config
  "Convert a raw Metabase connection `details` map into the normalised config
  map used by client, sync, and execute namespaces.

  The keys in `details` match the names declared in metabase-plugin.yaml.

  Returned keys:
    :endpoint          Base URL string, trailing slash stripped, nil if blank.
    :admin-secret      String or nil (blank → nil).
    :access-token      String or nil (blank → nil).
    :role              String or nil (blank → nil).
    :use-metadata-api  Boolean (default true when key absent).
    :timeout-ms        Positive integer milliseconds (default 30000)."
  [details]
  {:endpoint         (not-empty
                      (some-> (:endpoint details)
                              clojure.string/trim
                              (clojure.string/replace #"/+$" "")))
   :admin-secret     (not-empty (some-> (:admin-secret details) clojure.string/trim))
   :access-token     (not-empty (some-> (:access-token details) clojure.string/trim))
   :role             (not-empty (some-> (:role details) clojure.string/trim))
   :use-metadata-api (if (contains? details :use-metadata-api)
                       (boolean (:use-metadata-api details))
                       true)
   :timeout-ms       (or (when-let [t (:request-timeout-ms details)]
                           (when (pos-int? t) t))
                         30000)})

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-config!
  "Validate a normalised connection config map.

  Throws ex-info with :hasura/error-type :hasura.error/config-invalid and a
  human-readable :message on the first violation found.

  Returns `cfg` unchanged on success so callers can thread:
    (-> details connection-config validate-config! ...)

  Rules checked (in order):
    1. :endpoint must be non-nil.
    2. :endpoint must start with http:// or https://."
  [cfg]
  (let [endpoint (:endpoint cfg)]
    (when (nil? endpoint)
      (throw (ex-info "Endpoint is required. Enter the base URL of your Hasura instance."
                      {:hasura/error-type :hasura.error/config-invalid
                       :field             :endpoint
                       :value             nil})))
    (when-not (re-matches #"https?://.*" endpoint)
      (throw (ex-info (str "Endpoint must use http or https. Got: \"" endpoint "\"")
                      {:hasura/error-type :hasura.error/config-invalid
                       :field             :endpoint
                       :value             endpoint}))))
  cfg)

;; ---------------------------------------------------------------------------
;; Request header construction
;; ---------------------------------------------------------------------------

(defn request-headers
  "Build the HTTP request header map from a normalised connection config.

  Auth priority (mutually exclusive — Hasura accepts one at a time):
    1. X-Hasura-Admin-Secret is sent when :admin-secret is non-nil.
    2. Authorization: Bearer is sent only when :admin-secret is nil and
       :access-token is non-nil.

  Role header:
    X-Hasura-Role is always added when :role is non-nil, independent of
    which auth method is in use.

  Returns a plain string→string map suitable for clj-http :headers."
  [cfg]
  (cond-> {"Content-Type" "application/json"
           "Accept"       "application/json"}
    (:admin-secret cfg)
    (assoc "X-Hasura-Admin-Secret" (:admin-secret cfg))

    ;; Access-token is only sent when admin-secret is absent.
    (and (nil? (:admin-secret cfg)) (:access-token cfg))
    (assoc "Authorization" (str "Bearer " (:access-token cfg)))

    (:role cfg)
    (assoc "X-Hasura-Role" (:role cfg))))

;; ---------------------------------------------------------------------------
;; Read-only query guard
;; ---------------------------------------------------------------------------

(def ^:private forbidden-operations
  #{"mutation" "subscription"})

(defn guard-query!
  "Throw if `query-str` starts with a forbidden operation keyword.

  Matches the first word token after optional whitespace so that
  `mutation { ... }` is rejected while `{ mutation_field }` is allowed
  (shorthand query, not a mutation operation).

  Throws ex-info with :hasura/error-type :hasura.error/forbidden on rejection.
  Returns nil for allowed queries."
  [query-str]
  (when (string? query-str)
    (let [trimmed (clojure.string/trim query-str)
          op      (re-find #"(?i)^\w+" trimmed)]
      (when (contains? forbidden-operations (some-> op clojure.string/lower-case))
        (throw (ex-info (str "Operation type '" op "' is not allowed. "
                             "This driver is read-only (native queries only).")
                        {:hasura/error-type :hasura.error/forbidden
                         :operation         op
                         :query             query-str}))))))
