(ns build
  "Build this thing."
  (:require [clojure.tools.build.api :as b]))

(def native-jvm-opts
  "Needed when using jvm natively linked libraries like
  https://github.com/clj-python/libpython-clj"
  ["--add-modules" "jdk.incubator.foreign"
   "--enable-native-access=ALL-UNNAMED"])

(def defaults
  "The defaults to configure a build."
  {:class-dir  "target/classes"
   :main       'clinvar-gk-pilot.main
   :path       "target"
   :project    "deps.edn"
   :target-dir "target/classes"
   :uber-file  "target/clinvar-gk-pilot.jar"})



(defn uber
  "Throw or make an uberjar from source."
  [_]
  ;; https://clojure.github.io/tools.build/clojure.tools.build.api.html#var-compile-clj
  (let [{:keys [paths] :as basis} (b/create-basis {:project "deps.edn"})
        class-dir "target/classes"]
    (prn :basis basis)
    (b/delete      {:path "target"})

    ;; paths comes from deps.edn
    (b/copy-dir    {:src-dirs paths
                    :target-dir "target/classes"})
    ;; only compile src/
    (b/compile-clj {:basis basis
                    :src-dirs ["src"]
                    :class-dir class-dir
                    :java-opts ["-Dclojure.main.report=stderr"]})
    (b/uber        {:class-dir class-dir
                    :uber-file "target/clinvar-gk-pilot.jar"
                    :basis basis
                    :main 'clinvar-gk-pilot.main})))
