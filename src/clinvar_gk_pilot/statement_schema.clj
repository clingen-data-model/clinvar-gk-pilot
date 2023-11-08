(ns clinvar-gk-pilot.statement-schema
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))


(defn get-lines-as-json-eagerly [filename]
  (with-open [rdr (io/reader filename)]
    (into [] (->> (line-seq rdr)
                  (map json/parse-string)))))

(defn count-lines [filename]
  (with-open [rdr (io/reader filename)]
    (count (line-seq rdr))))

(defn as-jsonld-eager [file] 
  (let [lines (get-lines-as-json-eagerly file)]
    (with-open [writer (io/writer (str "output-" file))]
      (let [reformatted (map #(array-map (get % "id") %) lines)]
        (doseq [n reformatted]
          (.write writer (json/generate-string n))
          (.write writer "\n"))))))

(defn as-jsonld-lazy [file]
  (with-open [reader (io/reader file)
                writer (io/writer (str "output-" file))]
      (doseq [in (->> (line-seq reader)
                      (map json/parse-string))]
        (let [out (array-map (get in "id") in)]
          (.write writer (json/generate-string out))))))

(defn as-one-big-dict [file]
  (with-open [reader (io/reader file)
              writer (io/writer (str "output-" file))]
    (let [in (get-lines-as-json-eagerly file)
          json-map (reduce #(assoc %1 (get %2 "id") %2) {} in)]
      (.write writer (json/generate-string json-map)))))

