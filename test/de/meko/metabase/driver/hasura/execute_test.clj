(ns de.meko.metabase.driver.hasura.execute-test
  "Unit tests for result flattening and execution guards.

  Guard tests are implemented now (guard-query! is a pure function in config.clj).
  Flattening tests are pending until Phase 3 (Task 3.3)."
  (:require [clojure.test :refer [deftest testing is]]
            [de.meko.metabase.driver.hasura.fixtures :as f]))

;; ---------------------------------------------------------------------------
;; guard-query! — covered by config_test.clj (the implementation lives there).
;; The tests below cross-check that the contract is enforced end-to-end from
;; the execute namespace's perspective.
;; ---------------------------------------------------------------------------

;; Phase 3 flattening placeholders

(deftest ^:pending flatten-list-response
  (testing "flatten-response converts list-of-objects to {:cols [...] :rows [...]}"
    ;; Use f/query-response-list fixture.
    ))

(deftest ^:pending flatten-nested-objects-as-dot-paths
  (testing "nested object fields appear as dot-path column names like author.name"
    ;; Use f/query-response-nested fixture.
    ))

(deftest ^:pending flatten-aggregate-response
  (testing "aggregate response fields appear as top-level columns"
    ;; Use f/query-response-aggregate fixture.
    ))

(deftest ^:pending flatten-array-field-serialised
  (testing "array field in response is serialised to a JSON string column"
    ;; Use f/query-response-with-array fixture.
    ))

(deftest ^:pending flatten-null-values-preserved
  (testing "null values in response produce nil in result rows"))
