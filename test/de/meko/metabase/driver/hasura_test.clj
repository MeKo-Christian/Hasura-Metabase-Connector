(ns de.meko.metabase.driver.hasura-test
  "Phase 0 smoke tests and regression-test placeholders.

  Smoke tests verify structural invariants that must hold from day one.
  Pending tests (marked ^:pending) define the expected test targets for each
  major capability; they will be filled in as each phase is completed.

  Run all non-pending tests:
    clojure -M:test

  Run only pending (to see what is left):
    clojure -M:test --var-pattern '.*pending.*'"
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.java.io :as io]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Task 0.4 — Smoke: plugin packaging
;; ─────────────────────────────────────────────────────────────────────────────

(deftest plugin-manifest-exists
  (testing "metabase-plugin.yaml is present in resources"
    (is (some? (io/resource "metabase-plugin.yaml"))
        "metabase-plugin.yaml must be on the classpath for Metabase to load the plugin")))

(deftest plugin-manifest-has-required-keys
  (testing "metabase-plugin.yaml contains required top-level keys"
    (when-let [url (io/resource "metabase-plugin.yaml")]
      (let [content (slurp url)]
        (are [key] (clojure.string/includes? content key)
          "info:"
          "driver:"
          "name: hasura"
          "display-name:"
          "init:")))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Task 0.4 — Pending placeholders: connection
;; ─────────────────────────────────────────────────────────────────────────────

(deftest ^:pending connection-test-success
  ;; Phase 1 — Task 1.4
  ;; Filled in when: can-connect? is implemented and integration stack is up.
  (testing "connection test succeeds against a valid Hasura instance"))

(deftest ^:pending connection-test-auth-failure
  ;; Phase 1 — Task 1.4
  (testing "connection test returns a clear auth error with wrong admin secret"))

(deftest ^:pending connection-test-unreachable
  ;; Phase 1 — Task 1.4
  (testing "connection test returns a clear error when endpoint does not respond"))

;; ─────────────────────────────────────────────────────────────────────────────
;; Task 0.4 — Pending placeholders: sync
;; ─────────────────────────────────────────────────────────────────────────────

(deftest ^:pending sync-introspection-only
  ;; Phase 2 — Task 2.5
  (testing "describe-database returns tables using introspection alone"))

(deftest ^:pending sync-metadata-enriched
  ;; Phase 2 — Task 2.5
  (testing "describe-database returns richer names when metadata API is available"))

(deftest ^:pending sync-metadata-fallback
  ;; Phase 2 — Task 2.5
  (testing "describe-database falls back to introspection when metadata API is disabled"))

(deftest ^:pending sync-stable-across-runs
  ;; Phase 2 — Task 2.5
  (testing "two consecutive syncs produce identical table and field descriptors"))

;; ─────────────────────────────────────────────────────────────────────────────
;; Task 0.4 — Pending placeholders: native query execution
;; ─────────────────────────────────────────────────────────────────────────────

(deftest ^:pending execute-simple-read
  ;; Phase 3 — Task 3.4
  (testing "a simple table SELECT returns rows and correct column names"))

(deftest ^:pending execute-nested-read
  ;; Phase 3 — Task 3.4
  (testing "nested object relationship fields appear as dot-path columns"))

(deftest ^:pending execute-aggregate-read
  ;; Phase 3 — Task 3.4
  (testing "_aggregate query produces scalar aggregate columns"))

(deftest ^:pending execute-mutation-rejected
  ;; Phase 3 — Task 3.1
  (testing "a query containing 'mutation' keyword is rejected before execution"))

(deftest ^:pending execute-subscription-rejected
  ;; Phase 3 — Task 3.1
  (testing "a query containing 'subscription' keyword is rejected before execution"))

(deftest ^:pending execute-arrays-serialised
  ;; Phase 3 — Task 3.3
  (testing "array fields in the response are serialised to a JSON string column"))

(deftest ^:pending execute-timeout
  ;; Phase 3 — Task 3.4
  (testing "a slow Hasura response produces a clear timeout error"))

;; ─────────────────────────────────────────────────────────────────────────────
;; Task 0.4 — Pending placeholders: MBQL translation (Phase 4)
;; ─────────────────────────────────────────────────────────────────────────────

(deftest ^:pending mbql-unsupported
  ;; Phase 4 — Task 4.4
  (testing "any MBQL query returns ::driver/unsupported until Phase 4 is implemented"))

(deftest ^:pending mbql-join-rejected
  ;; Phase 4 — Task 4.4
  (testing "a join in a MBQL query returns a clear unsupported-feature error"))
