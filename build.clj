(ns build
  "Plugin JAR builder for the Hasura Metabase driver.

  Metabase community plugins are shipped as JARs that contain:
    - metabase-plugin.yaml at the root (loaded by Metabase's plugin system)
    - .clj source files on the classpath paths (compiled at plugin load time
      by Metabase's own JVM; no AOT required)

  Because we do NOT do AOT, the build does not need Metabase on the classpath.
  Clojure compilation is deferred to the Metabase runtime, which means the
  plugin JAR can be built in CI without a full Metabase checkout.

  Usage:
    clojure -T:build jar     -- build the plugin JAR
    clojure -T:build clean   -- delete target/"
  (:require [clojure.tools.build.api :as b]))

(def lib     'de.meko/metabase-driver-hasura)
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def jar-file  (format "target/%s-%s.metabase-driver.jar" (name lib) version))

;; Basis without Metabase — build alias deps only.
(def basis (b/create-basis {:project "deps.edn" :aliases [:build]}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  "Produce the plugin JAR.

  Copies src/ and resources/ into target/classes/, then packages them as a
  JAR.  metabase-plugin.yaml ends up at the root of the JAR because it lives
  in resources/ and is copied with no extra directory prefix."
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file
          :main      nil})  ; not a runnable JAR
  (println (str "Built: " jar-file)))

(defn verify
  "Assert that the JAR contains metabase-plugin.yaml at the root.
  Called by CI after the jar step."
  [_]
  (require 'clojure.java.shell)
  (let [result ((resolve 'clojure.java.shell/sh) "jar" "tf" jar-file)
        entries (:out result)]
    (if (clojure.string/includes? entries "metabase-plugin.yaml")
      (println "OK: metabase-plugin.yaml present in JAR")
      (do (println "ERROR: metabase-plugin.yaml NOT found in JAR")
          (System/exit 1)))))
