(ns clinvar-gk-pilot.main
  (:require [charred.api :as charred]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [com.climate.claypoole :as cp]
            [hato.client :as http]
            [taoensso.timbre :as log])
  (:import (java.time Duration Instant)))

(log/set-min-level! :info)
(def appender
  "File appender for timbre."
  (log/spit-appender
   {:fname (str "logs/clinvar-gk-pilot.log")}))
(log/swap-config!
 #(update % :appenders merge {:file appender}))

(spec/def ::string (spec/and string? seq))
(spec/def ::filename ::string)
(spec/def ::args (spec/keys :req-un [::filename]))

;; Adding a round-robin http pool doesn't seem to help much, if at all
(defn build-client []
  (http/build-http-client {:version :http-2
                           :connect-timeout (* 30 1000)}))
(def http-client-pool (mapv (fn [_] (atom (build-client)))
                            (range 10)))
(def http-client-pool-pointer (atom 0))
(defn get-http-client
  "Returns http clients from the pool round-robin"
  []
  (let [idx (swap! http-client-pool-pointer
                   (fn [v] (let [newv (inc v)]
                             ;; check overflow
                             (if (>= newv (count http-client-pool)) 0 newv))))]
    (nth http-client-pool idx)))

(defn http-get [url params]
  (loop []
    (let [client-atom (get-http-client)
          client @client-atom
          goaway? (atom false)
          resp (try (http/get url
                              {:http-client client
                               :throw-exceptions false
                               :query-params params})
                    (catch java.io.IOException e
                      (let [msg (.getMessage e)]
                        (when (some-> msg #(.contains "GOAWAY"))
                          (reset! goaway? true))
                        nil)))]
      ;; HTTP2 GOAWAY response results in IOException in the Java http client.
      ;; the hato :throw-exceptions false doesn't disable this.
      ;; So we need to check for 502, and for IOExceptions that are GOAWAY, and
      ;; treat them the same.
      (if (or @goaway? (= 502 (:status resp)))
        (do (log/warn :fn :http-get :url url :params params
                      :status (:status resp) :goaway @goaway?
                      :msg "Received temporary server error, trying again")
            ;; Construct a new client to re-establish connection
            ;; ... actually it seems like the hato (or java) client does this automatically? (need to verify)
            (reset! client-atom (build-client))
            (Thread/sleep 500)
            (recur))
        resp))))

(defn add-error
  "Adds some error object to ctx under :errors"
  [ctx error]
  (update ctx :errors (fn [errors]
                        (into [] (concat [] errors [error])))))

(defn add-errors
  "Adds multiple error objects to ctx under :errors"
  [ctx errors]
  (reduce (fn [agg e] (add-error agg e)) ctx errors))

(defn validate-response
  "Takes a ring/clj-http/hato style HTTP Response map.
   Checks if status code is 200, and response is parseable JSON, and has no errors or warnings"
  [resp]
  (let [error-templ {:fn ::validate-response
                     :status (:status resp)
                     :body (:body resp)}]
    (if (not= 200 (:status resp))
      (add-error {} (assoc error-templ :msg "Not status 200"))
      (try (let [body (some-> resp :body json/parse-string)]
             (if (or (seq (get body "errors"))
                     (seq (get body "warnings")))
               (add-error {} (assoc error-templ
                                    :msg "Error returned from variation normalizer"
                                    :response-errors (concat (get body "errors")
                                                             (get body "warnings"))))
               {:body body}))
           (catch Exception e (add-error {} (.getMessage e)))))))

(defn normalize-spdi
  "Returns normalized form of variant.

   If return value has some :errors, it didn't work

   TODO: will be switching from /to_canonical_variation to /translate_from when API is updated."
  [record {:keys [normalizer-url] :as opts}]
  (let [spdi (get record "canonical_spdi")
        resp (http-get (str normalizer-url "/to_canonical_variation")
                       {"q" spdi
                        "fmt" "spdi"})
        {errors :errors body :body} (validate-response resp)]
    (if (seq errors)
      (add-errors record errors)
      (get-in body ["canonical_variation" "canonical_context"]))))

(defn normalize-copy-number-count
  "Normalize a copy number count record (ClinVar variant name ends in x[0-9]+)"
  ;; Names ending in 'x[0-9]+' (CopyNumberCount): When the absolute_copies value is not null use the hgvs.nucleotide value if it is available to call the hgvs_to_copy_number_count api. If the hgvs.nucleotide value is null then use the seq.derived_hgvs value if it is available.

  ;; NOTES:
  ;; .seq.derived_hgvs in spec should be just .derived_hgvs
  [record {:keys [normalizer-url] :as opts}]
  (let [absolute-copies (get record "absolute_copies")
        hgvs-nucleotide (get-in record ["hgvs" "nucleotide"])
        seq-derived-hgvs (get-in record ["derived_hgvs"])]
    (cond
      (and (not (nil? absolute-copies))
           (or (not (nil? hgvs-nucleotide))
               (not (nil? seq-derived-hgvs))))
      (let [hgvs-expr (or hgvs-nucleotide seq-derived-hgvs)
            resp (http-get (str normalizer-url "/hgvs_to_copy_number_count")
                           {"hgvs_expr" hgvs-expr
                            "baseline_copies" absolute-copies})
            {errors :errors body :body} (validate-response resp)]
        (if (seq errors)
          (add-errors record errors)
          (-> body (get "copy_number_count"))))

      ;; Is a copy number count with a single count,
      ;; but doesn't have the necessary info in the record to normalize it
      :else
      {:errors ["Record looked like CopyNumberCount but was missing necessary information"]})))



(defn clinvar-copy-class-to-EFO
  "Returns an EFO CURIE for a copy class str.
   Uses lower-case 'efo' prefix"
  [copy-class-str]
  ({"Deletion"         "efo:0030067"
    "copy number loss" "efo:0030067"

    "Duplication"      "efo:0030070"
    "copy number gain" "efo:0030070"}
   copy-class-str))

(defn min-or-nil [values]
  (when (not-empty values)
    (apply min values)))

(defn max-or-nil [values]
  (when (not-empty values)
    (apply max values)))

(defn drop-nils [values]
  (filter #(not (nil? %)) values))

(defn variation-too-long-to-normalize?
  "If the variant length exceeds limit, return the length. nil otherwise.
   Defaults to 1000 as the limit.
   Uses all start/stop positions in the record to determine the max span."
  ([record]
   (variation-too-long-to-normalize? record 100000))
  ([record limit]
   (let [lowest-start (->> [(some-> (get-in record ["seq" "disp_start"]) parse-long)
                            (some-> (get-in record ["seq" "inner_start"]) parse-long)
                            (some-> (get-in record ["seq" "outer_start"]) parse-long)]
                           (drop-nils)
                           (min-or-nil))
         highest-stop (->> [(some-> (get-in record ["seq" "disp_stop"]) parse-long)
                            (some-> (get-in record ["seq" "inner_stop"]) parse-long)
                            (some-> (get-in record ["seq" "outer_stop"]) parse-long)]
                           (drop-nils)
                           (max-or-nil))]
     (when (or (nil? lowest-start)
               (nil? highest-stop))
       (log/warn :msg "Record was missing a start or stop"
                 :record record
                 :lowest-start lowest-start
                 :highest-stop highest-stop))
     (when (and lowest-start
                highest-stop
                (< limit (- highest-stop lowest-start)))
       (- highest-stop lowest-start)))))

(defn normalize-copy-number-change
  "Normalize a copy number change record (ClinVar marked as Dup/Del or copy number gain/loss)"
  ;; Remaining copy loss/gain, dels/dups (CopyNumberChange): If the variation_type is 'Deletion', 'Duplication', 'copy number loss', or 'copy number gain' then use the hgvs.nucleotide expression to call the hgvs_to_copy_number_change API. If the hgvs.nucleotide value is null then use the seq.derived_hgvs if it is not null .
  [record {:keys [normalizer-url] :as opts}]
  (let [hgvs-nucleotide (get-in record ["hgvs" "nucleotide"])
        derived-hgvs (get-in record ["derived_hgvs"])
        efo-copy-class (clinvar-copy-class-to-EFO
                        (get-in record ["variation_type"]))
        hgvs-expr (or hgvs-nucleotide derived-hgvs) ;; "NC_000010.11:g.110589642dup"
        resp (http-get (str normalizer-url "/hgvs_to_copy_number_change")
                       {"hgvs_expr" hgvs-expr
                        "copy_change" efo-copy-class})
        {errors :errors body :body} (validate-response resp)]
    (if (seq errors)
      (add-errors record errors)
      (-> body (get "copy_number_change")))))

(defn normalize-hgvs-allele
  "Normalize hgvs that doesn't meet prior clauses, using /to_vrs endpoint.
   May consider switching to /translate_from."
  [record {:keys [normalizer-url] :as opts}]
  (let [hgvs-nucleotide (get-in record ["hgvs" "nucleotide"])
        resp (http-get (str normalizer-url "/to_vrs")
                       {"q" hgvs-nucleotide})
        {errors :errors body :body} (validate-response resp)]
    (if (seq errors)
      (add-errors record errors)
      (do (when (not= 1 (count (get body "variations")))
            (log/error :msg "to_vrs did not return exactly 1 variation"
                       :hgvs-nucleotide hgvs-nucleotide
                       :resp-body body))
          (-> body (get "variations") first)))))

(defn normalize-text [record]
  (let [id (get record "id")]
    (if (nil? id)
      (add-error record "Record had no id")
      {"id" (str "Text:clinvar:" (get record "id"))
       "type" "Text"
       "definition" (str "clinvar:" (get record "id"))})))

(defn hgvs-snv?
  "a"
  [expr]
  (re-matches #"(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+[ACTG]\>[ACTG]+$" expr))

(defn hgvs-same-as-ref?
  "b"
  [expr]
  (re-matches #"(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+[ACTG]?\=$" expr))

(defn hgvs-single-residue-dupdel-delins?
  "c"
  [expr]
  (re-matches #"(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+(dup|del|delins)[ACTG]*$" expr))

(defn hgvs-precise-dupdel-delins-ins?
  "d"
  [expr]
  (re-matches #"(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+\_[0-9]+(dup|del|delins|ins)[ACTG]*$" expr))

(defn hgvs-precise-innerouter-dupdel-delins-ins?
  "e"
  [expr]
  (re-matches #"(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.\([0-9]+\_[0-9]+\)\_\([0-9]+\_[0-9]+\)(dup|del|delins|ins)[ACTG]*$" expr))

(defn hgvs-imprecise-innerouter-dupdel-delins-ins?
  "f"
  [expr]
  (re-matches #"(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.\(\?\_[0-9]+\)\_\([0-9]+\_\?\)(dup|del|delins|ins)[ACTG]*$" expr))

(defn hgvs-imprecise-inner-dupdel-delins-ins?
  "g"
  [expr]
  (re-matches #"(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.\([0-9]+\_\?\)\_\(\?\_[0-9]+\)(dup|del|delins|ins)[ACTG]*$" expr))

(defn supported-hgvs?
  [^String expr]
  ;; a. snvs: '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+[ACTG]\>[ACTG]+$'
  ;; b. same as ref: '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+[ACTG]?\=$'
  ;; c. single residue dup/del/delins: '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+(dup|del|delins)[ACTG]*$')
  ;; d. precise range dup/del/delins/ins: '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+\_[0-9]+(dup|del|delins|ins)[ACTG]*$'
  ;; e. precise inner/outer range dup or del or delins or ins: '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.\([0-9]+\_[0-9]+\)\_\([0-9]+\_[0-9]+\)(dup|del|delins|ins)[ACTG]*$'
  ;; f. imprecise outer range dup or del or delins or ins '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.\(\?\_[0-9]+\)\_\([0-9]+\_\?\)(dup|del|delins|ins)[ACTG]*$'
  ;; g. imprecise inner range dup or del or delins or ins '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.\([0-9]+\_\?\)\_\(\?\_[0-9]+\)(dup|del|delins|ins)[ACTG]*$'
  (letfn [(check [fn-var expr]
            "Return the name of the var if it called on expr is truthy (whole expr matches regex), else nil"
            (when ((-> fn-var var-get) expr)
              (-> fn-var symbol name)))]
    (or (check #'hgvs-snv? expr)
        (check #'hgvs-same-as-ref? expr)
        (check #'hgvs-single-residue-dupdel-delins? expr)
        (check #'hgvs-precise-dupdel-delins-ins? expr)
        (check #'hgvs-precise-innerouter-dupdel-delins-ins? expr)
        (check #'hgvs-imprecise-innerouter-dupdel-delins-ins? expr)
        (check #'hgvs-imprecise-inner-dupdel-delins-ins? expr))))


(defn annotate-record [record opts]
  )

(defn normalize-record-via-xform-plan [record opts]
  (let [plan-type (get-in record ["vrs_xform_plan" "type"])]
    )
)

(defn normalize-and-annotate-record [record opts]
  (-> (normalize-record-via-xform-plan record opts)
      annotate-record))

(defn normalize-record
  "Try to normalize a variant record.
  
   If return value has some :errors, it didn't work"
  [record opts]
  (cond
    ;; 1
    (get record "canonical_spdi")
    (do (log/debug :case 1)
        (normalize-spdi record opts))

    ;; 2
    (#{"Genotype" "Haplotype"} (get record "subclass_type"))
    (do (log/debug :case 2)
        (normalize-text record))

    ;; 3
    ;; Genome NCBI36 only (Text): if at least one of the hgvs.assembly or seq.assembly has the value 'NCBI36' and neither one has 'GRCh37' or 'GRCh38' then use the id (clinvar variation id) as the input to get a VRS Text object for this record.
    (or (= "NCBI36" (get-in record ["seq" "assembly"]))
        (= "NCBI36" (get-in record ["hgvs" "assembly"])))
    (do (log/debug :case 3)
        (normalize-text record))

    ;; 4
    ;; No HGVS or SeqLoc info (Text): if the hgvs.assembly, hgvs.nucleotide, seq.assembly and seq.accession are all null then use the id (clinvar variation id) as the input to get a VRS Text object for this record.
    (and (nil? (get-in record ["hgvs" "assembly"]))
         (nil? (get-in record ["seq" "assembly"]))
         (nil? (get-in record ["seq" "accession"])))
    (do (log/debug :case 4)
        (normalize-text record))

    ;; 5
    ;; Invalid/Unsupported HGVS (Text): If the hgvs.nucleotide is not null and it does NOT match one of the following REGEXPs then use the id (clinvar variation id) as the input to get a VRS Text object for this record.
    ;; a. snvs: '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+[ACTG]\>[ACTG]+$'
    ;; b. same as ref: '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+[ACTG]?\=$'
    ;; c. single residue dup/del/delins: '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+(dup|del|delins)[ACTG]*$')
    ;; d. precise range dup/del/delins/ins: '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.[0-9]+\_[0-9]+(dup|del|delins|ins)[ACTG]*$'
    ;; e. precise inner/outer range dup or del or delins or ins: '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.\([0-9]+\_[0-9]+\)\_\([0-9]+\_[0-9]+\)(dup|del|delins|ins)[ACTG]*$'
    ;; f. imprecise outer range dup or del or delins or ins '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.\(\?\_[0-9]+\)\_\([0-9]+\_\?\)(dup|del|delins|ins)[ACTG]*$'
    ;; g. imprecise inner range dup or del or delins or ins '(CM|N[CTW]\_)[0-9]+\.[0-9]+\:[gm]\.\([0-9]+\_\?\)\_\(\?\_[0-9]+\)(dup|del|delins|ins)[ACTG]*$'
    (and (not (nil? (get-in record ["hgvs" "nucleotide"])))
         (not (supported-hgvs? (get-in record ["hgvs" "nucleotide"]))))
    (do (log/debug :case 5)
        (normalize-text record))

    ;; 6
    ;; Names ending in 'x[0-9]+' (CopyNumberCount): When the absolute_copies value is not null use the hgvs.nucleotide value if it is available to call the hgvs_to_copy_number_count api. If the hgvs.nucleotide value is null then use the seq.derived_hgvs value if it is available.
    (re-matches #".*x[0-9]$"
                (get record "name"))
    (do (log/debug :case 6)
        (let [too-long (variation-too-long-to-normalize? record)]
          (if too-long
            (add-error (normalize-text record)
                       (format "Variation record was too long to normalize (%d), treating as text"
                               too-long))
            (normalize-copy-number-count record opts))))

    ;; 7
    ;; CNVs with min,max counts (Text): If the min_copies and/or max_copies is not null then use the `'id' to create a Text variation.
    (or (-> record (get "min_copies") nil? not)
        (-> record (get "max_copies") nil? not))
    (do (log/debug :case 7)
        (normalize-text record))

    ;; 8
    ;; Remaining copy loss/gain, dels/dups (CopyNumberChange): If the variation_type is 'Deletion', 'Duplication', 'copy number loss', or 'copy number gain' then use the hgvs.nucleotide expression to call the hgvs_to_copy_number_change API. If the hgvs.nucleotide value is null then use the seq.derived_hgvs if it is not null .
    (let [variation-type (get record "variation_type")]
      (contains? #{"Deletion"
                   "Duplication"
                   "copy number loss"
                   "copy number gain"}
                 variation-type))
    (do (log/debug :case 8)
        (let [too-long (variation-too-long-to-normalize? record)]
          (if too-long
            (add-error (normalize-text record)
                       (format "Variation record was too long to normalize (%d), treating as text"
                               too-long))
            (normalize-copy-number-change record opts))))

    ;; 9
    ;; Remaining supported genomic HGVS (Allele): If any remaining records have a value in the hgvs.nucleotide field, then use it to call the to_vrs api.
    (-> record (get-in ["hgvs" "nucleotide"]) nil? not)
    (do (log/debug :case 9)
        (normalize-hgvs-allele record opts))

    ;; 10
    ;; Insufficient information (Text): all remaining should use the id value to create a Text variation.
    ;; TODO, treat as text but add an annotation for reason
    :else
    (do (log/debug :case 10)
        (add-error (normalize-text record)
                   "Unrecognized variation record"))))

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

(defn flatten1 [inputs]
  (for [a inputs b a] b))

(defn -main [& args]
  (let [args (parse-args args)
        _ (log/info :args args)]
    ;; Might be able a 502 error backoff by messing around with dynamically changing the pool
    ;; size at runtime. cp/threadpool returns a
    ;; java.util.concurrent.ScheduledThreadPoolExecutor (java.util.concurrent.ThreadPoolExecutor)
    ;; setCorePoolSize will gracefully change pool size.
    ;; The problem is with upmap we can't add a task back to the queue
    ;; I could refactor this to use a lazyseq over a channel as the input to upmap,
    ;; and then inputs resulting in 502 could be re-added to the channel
    (cp/with-shutdown! [threadpool (cp/threadpool 8)]
      (with-open [reader (io/reader (:filename args))
                  writer (io/writer (str "output-" (:filename args)))]
        (doseq [[in out] (cp/upmap
                          threadpool
                          #(do (log/info :msg "Calling normalize-record" :id (get % "id"))
                               (let [out (try [% (normalize-record % args)]
                                              (catch Exception e
                                                (log/error :msg "Exception caught calling normalize-record"
                                                           :record %
                                                           :options args
                                                           :exception e)
                                                [% nil]))]
                                 (log/info :msg "Finished normalize-record" :id (get % "id"))
                                 out))
                          (->> (line-seq reader)
                               (map json/parse-string)
                               #_(take (or (-> args :limit) Long/MAX_VALUE))))]
          (log/info :msg "Writing output" :id (get in "id"))
          (.write writer (json/generate-string {:in in :out out}))
          (.write writer "\n"))))))

(comment
  "perf test"
  (time (-main :filename "variation_identity_canonical_spdi.ndjson"
               :normalizer-url "http://localhost:8100/variation"
               :limit 10000)))

(comment
  "Main run through whole file in separate thread"
  (def t (Thread. (fn []
                    (let [start (Instant/now)]
                      (-main :filename "variation_identity.ndjson"
                             #_#_:normalizer-url "http://localhost:8100/variation"
                             #_#_:limit 100000)
                      (let [stop (Instant/now)
                            duration (Duration/between start stop)
                            millis (.toMillis duration)]
                        (log/info :msg "Finished run" :millis millis)
                        (with-open [writer (io/writer "run-time.txt")]
                          (.write writer (str millis " ms"))))))))
  (.start t))

(defn localrun []
  "Run in a local testing environment on the main thread"
  (let [args [:filename "variation_identity.ndjson"
              :normalizer-url "http://localhost:8002/variation"
              :limit 10]
        argm (parse-args args)]
    (with-open [reader (io/reader (:filename argm))]
      (doseq [in (->> (line-seq reader)
                      (map json/parse-string))]
        (let [out (normalize-record in argm)]
          (println (json/generate-string {:in in :out out} {:pretty true})))))))

