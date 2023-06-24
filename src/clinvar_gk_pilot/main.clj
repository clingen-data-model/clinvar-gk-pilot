(ns clinvar-gk-pilot.main
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [hato.client :as http]
            [charred.api :as charred]
            [com.climate.claypoole :as cp]
            [taoensso.timbre :as log]))

(log/set-level! :info)

;; (defn log [& vals]
;;   (assert (= 0 (rem (count vals) 2)) "Must provide even number of vals")
;;   (.write *out* (prn-str (apply hash-map vals))))

(spec/def ::string (spec/and string? seq))
(spec/def ::filename ::string)
(spec/def ::args (spec/keys :req-un [::filename]))

(def http-client (http/build-http-client {:version :http-2
                                          :connect-timeout (* 30 1000)}))

(defn add-error
  "Adds some error object to ctx under :errors"
  [ctx error]
  (update ctx :errors (fn [errors]
                        (concat [] errors [error]))))

(defn normalize-spdi
  "Returns normalized form of variant.

   If return value has some :errors, it didn't work"
  [variant {:keys [normalizer-url] :as opts}]
  (let [spdi (get variant "canonical_spdi")
        resp (http/get (str normalizer-url "/to_canonical_variation")
                       {:http-client http-client
                        :throw-exceptions false
                        :query-params {"q" spdi
                                       "fmt" "spdi"
                                       "untranslatable_returns_text" false}})
        body (some-> resp :body charred/read-json)]
    (if (or (not= 200 (:status resp))
            (seq (-> body :errors)))
      (add-error variant {:fn ::norm-spdi
                          :spdi spdi
                          :opts opts
                          :response resp})
      (-> body
          (get "canonical_variation")
          (get "canonical_context")))))

(defn normalize-record
  "Try to normalize a variant record.

   If return value has some :errors, it didn't work"
  [record opts]
  (cond
    (get record "canonical_spdi")
    (normalize-spdi record opts)

    :else
    {:errors ["Unrecognized variation record"]}))

(def defaults
  {:normalizer-url "https://normalization.clingen.app/variation"})

(defn parse-args
  "Arg parser for clinvar-gk-pilot. Accepts args seq. Parsed as pairs"
  [args]
  (letfn [(keywordize-colon [s]
            (if (and (string? s) (.startsWith s ":")) (keyword (subs s 1)) s))
          (keywordize-m [m]
            (->> (map (fn [[k v]]
                        [(keywordize-colon k) v])
                      m)
                 (into {})))]
    (let [args (->> args (apply hash-map) keywordize-m)
          args (merge defaults args)]
      (when (not (spec/valid? ::args args))
        (throw (RuntimeException. (str "Args don't conform: "
                                       #_(spec/explain-data ::args args)
                                       (with-out-str (spec/explain ::args args))))))
      args)))

(def keep-running-atom (atom true))

(defn -main [& args]
  (let [args (parse-args args)
        _ (log/info :args args)]
    (cp/with-shutdown! [threadpool (cp/threadpool 10)]
      (with-open [reader (io/reader (:filename args))]
        (->> (doall
              (cp/upmap
               threadpool
               #(normalize-record % args)
               (->> reader
                    (line-seq)
                    (map charred/read-json)
                    (filter #(get % "canonical_spdi"))
                    #_(#(do (log/info (first %)) %))
                    (take 10))))
             (#(doseq [out %]
                 (prn out))))))))
