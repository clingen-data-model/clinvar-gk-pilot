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
            (seq (-> body :errors))
            (seq (-> body :warnings)))
      (add-error variant {:fn ::norm-spdi
                          :spdi spdi
                          :opts opts
                          :response resp})
      (-> body
          (get "canonical_variation")
          (get "canonical_context")))))

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
            resp (http/get (str normalizer-url "/hgvs_to_copy_number_count")
                           {:http-client http-client
                            :throw-exceptions false
                            :query-params {"hgvs_expr" hgvs-expr
                                           "baseline_copies" absolute-copies}})
            body (some-> resp :body charred/read-json)]
        (if (or (not= 200 (:status resp))
                (seq (-> body :errors))
                (seq (-> body :warnings)))
          (add-error record {:fn ::normalize-copy-number-count
                             :hgvs_expr hgvs-expr
                             :opts opts
                             :response resp})
          (-> body (get "copy_number_count"))))

      ;; Is a copy number count with a single count,
      ;; but doesn't have the necessary info in the record to normalize it
      :else
      ())))

(defn normalize-text [record]
  (let [id (get record "id")]
    (if (nil? id)
      {:errors "Record had no id"}
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


(defn normalize-record
  "Try to normalize a variant record.

   If return value has some :errors, it didn't work"
  [record opts]
  (cond
    ;; 1
    (get record "canonical_spdi")
    (do (log/info :case 1)
        (normalize-spdi record opts))

    ;; 2
    (#{"Genotype" "Haplotype"} (get record "subclass_type"))
    (do (log/info :case 2)
        (normalize-text record))

    ;; 3
    ;; Genome NCBI36 only (Text): if at least one of the hgvs.assembly or seq.assembly has the value 'NCBI36' and neither one has 'GRCh37' or 'GRCh38' then use the id (clinvar variation id) as the input to get a VRS Text object for this record.
    (or (= "NCBI36" (get-in record ["seq" "assembly"]))
        (= "NCBI36" (get-in record ["hgvs" "assembly"])))
    (do (log/info :case 3)
        (normalize-text record))

    ;; 4
    ;; No HGVS or SeqLoc info (Text): if the hgvs.assembly, hgvs.nucleotide, seq.assembly and seq.accession are all null then use the id (clinvar variation id) as the input to get a VRS Text object for this record.
    (and (nil? (get-in record ["hgvs" "assembly"]))
         (nil? (get-in record ["seq" "assembly"]))
         (nil? (get-in record ["seq" "accession"])))
    (do (log/info :case 4)
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
    (do (log/info :case 5)
        (normalize-text record))

    ;; 6
    ;; Names ending in 'x[0-9]+' (CopyNumberCount): When the absolute_copies value is not null use the hgvs.nucleotide value if it is available to call the hgvs_to_copy_number_count api. If the hgvs.nucleotide value is null then use the seq.derived_hgvs value if it is available.
    (re-matches #".*x[0-9]$"
                (get record "name"))
    (do (log/info :case 6)
        (normalize-copy-number-count record opts))


    ;; 7
    ;; CNVs with min,max counts (Text): If the min_copies and/or max_copies is not null then use the `'id' to create a Text variation.

    ;; 8
    ;; Remaining copy loss/gain, dels/dups (CopyNumberChange): If the variation_type is 'Deletion', 'Duplication', 'copy number loss', or 'copy number gain' then use the hgvs.nucleotide expression to call the hgvs_to_copy_number_change API. If the hgvs.nucleotide value is null then use the seq.derived_hgvs if it is not null .

    ;; 9
    ;; Remaining supported genomic HGVS (Allele): If any remaining records have a value in the hgvs.nucleotide field, then use it to call the to_vrs api.


    ;; 10
    ;; Insufficient information (Text): all remaining should use the id value to create a Text variation.
    ;; TODO, treat as text but add an annotation for reason

    :else
    (do (log/info :case 10)
        {:errors ["Unrecognized variation record"]})))

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
