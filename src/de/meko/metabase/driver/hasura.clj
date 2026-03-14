(ns de.meko.metabase.driver.hasura
  "Main driver namespace for the Hasura Metabase community driver.

  This is the entry point declared in metabase-plugin.yaml:

    init:
      - step: load-namespace
        namespace: de.meko.metabase.driver.hasura

  Metabase evaluates every top-level form in this namespace when the plugin
  loads, using its own classloader.  Sub-namespaces that register additional
  defmethod extensions must be required here so those extensions are active.

  Phase schedule:
    Phase 1 (Tasks 1.2–1.4) — registration, display, can-connect?
    Phase 2 (Tasks 2.1–2.5) — describe-database, describe-table
    Phase 3 (Tasks 3.1–3.4) — execute-reducible-query
    Phase 4 (Tasks 4.1–4.5) — mbql->native (limited subset)"
  (:require
   [metabase.driver :as driver]
   ;; config has no Metabase deps; safe to require here for the normaliser.
   [de.meko.metabase.driver.hasura.config :as config]
   [de.meko.metabase.driver.hasura.client :as client]
   [de.meko.metabase.driver.hasura.sync :as sync]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Driver registration  (Task 1.2)
;;
;; No :parent → inherits from the Metabase base driver, NOT :sql or :sql-jdbc.
;; This driver communicates via HTTP/GraphQL, not JDBC.
;; ─────────────────────────────────────────────────────────────────────────────

(driver/register! :hasura)

;; ─────────────────────────────────────────────────────────────────────────────
;; Display name  (Task 1.2)
;; ─────────────────────────────────────────────────────────────────────────────

(defmethod driver/display-name :hasura [_]
  "Hasura")

;; ─────────────────────────────────────────────────────────────────────────────
;; Feature support  (Task 1.2)
;;
;; The Metabase base driver returns false for all features by default, so we
;; only need to explicitly opt IN when a feature is implemented.  No doseq
;; of opt-outs is needed.
;;
;; Opts-in are added here as each phase lands:
;;   Phase 3: (defmethod driver/supports? [:hasura :native-query] [_ _] true)
;; ─────────────────────────────────────────────────────────────────────────────

;; (Phase 3) (defmethod driver/supports? [:hasura :native-query] [_ _] true)

;; ─────────────────────────────────────────────────────────────────────────────
;; Connection config helper  (Task 1.3)
;;
;; Thin delegation to config/connection-config, exposed here so that callers
;; in this namespace can use it without an extra require.
;; ─────────────────────────────────────────────────────────────────────────────

(def connection-config
  "Convert a raw Metabase connection details map to the normalised config map.
  Delegates to de.meko.metabase.driver.hasura.config/connection-config."
  config/connection-config)

;; ─────────────────────────────────────────────────────────────────────────────
;; can-connect?  (Task 1.4)
;; ─────────────────────────────────────────────────────────────────────────────

(defmethod driver/can-connect? :hasura [_ details]
  (let [cfg (-> details connection-config config/validate-config!)]
    (client/ping! cfg)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Sync multimethods  (Phase 2)
;; ─────────────────────────────────────────────────────────────────────────────

;; Phase 2 — Task 2.4

(defmethod driver/describe-database :hasura [_ {details :details}]
  (sync/describe-database (connection-config details)))

(defmethod driver/describe-table :hasura [_ {details :details} {table-name :name}]
  (sync/describe-table (connection-config details) table-name))

;; ─────────────────────────────────────────────────────────────────────────────
;; Query execution  (Phase 3)
;; ─────────────────────────────────────────────────────────────────────────────

;; TODO Phase 3 — Task 3.2:
;; (defmethod driver/execute-reducible-query :hasura [_ query _ respond]
;;   (execute/execute-reducible-query query respond))

;; ─────────────────────────────────────────────────────────────────────────────
;; Sub-namespace requires
;; Uncomment as each phase is implemented.
;; ─────────────────────────────────────────────────────────────────────────────

;; TODO Phase 3: (require 'de.meko.metabase.driver.hasura.execute)
;; TODO Phase 4: (require 'de.meko.metabase.driver.hasura.query-processor)
