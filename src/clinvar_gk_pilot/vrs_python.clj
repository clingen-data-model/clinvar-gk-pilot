(ns clinvar-gk-pilot.vrs-python
  (:require [libpython-clj2.require :refer [require-python]]
            [libpython-clj2.python :refer [py. py.. py.-] :as py]))

(require-python '[ga4gh.core :as core :refer [ga4gh_identify ga4gh_digest ga4gh_serialize]])
(require-python '[ga4gh.vrs :as vrs :refer [models normalize]])
(require-python '[ga4gh.vrs.extras.translator :as translator :refer [AlleleTranslator]])
(require-python '[ga4gh.vrs.dataproxy :refer [SeqRepoDataProxy]])
(require-python '[biocommons.seqrepo :refer [SeqRepo]])

(def data-proxy (-> (or (System/getenv "SEQREPO_ROOT_DIR")
                        "/Users/toneill/dev/seqrepo-2021-01-29")
                    SeqRepo
                    SeqRepoDataProxy))

(def tlr (AlleleTranslator data-proxy {})) 

(defn initialize []
  (py/initialize!))

(defn from_spdi [spdi-str]
  (-> (py. tlr _from_spdi spdi-str)
      (py/py. model_dump :exclude_none true)
      (->> (into {}))))
#_(from_spdi "NC_000013.11:20003096:C:")

(defn from_hgvs [hgvs-str]
  (-> (py. tlr _from_hgvs hgvs-str)
      (py/py. model_dump :exclude_none true)
      (->> (into {}))))
#_(from_hgvs "NM_000256.3:c.3824G>A")

(comment 
  (def sequence-location {:end 44908822 :sequence "ga4gh:SQ.IIB53T8CNeJJdUqzn9V_JnRtQadwWCbl" :start 44908721 :type "SequenceLocation"})
  (def sl (py/py** models SequenceLocation sequence-location))
  (py/py. sl model_dump :exclude_none true)
  (ga4gh_serialize sl)
  (ga4gh_identify sl)
  (ga4gh_digest sl)
  (py/dir models))

