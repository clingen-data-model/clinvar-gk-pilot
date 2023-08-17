(ns clinvar-gk-pilot.vrs-python
  (:require [libpython-clj2.require :refer [require-python]]
            [libpython-clj2.python :refer [py. py.. py.-] :as py]))

(py/initialize! :python-executable "./venv/bin/python3")
(require-python '[ga4gh.core :as core :refer [ga4gh_identify ga4gh_digest ga4gh_serialize]])
(require-python '[ga4gh.vrs :as vrs :refer [models normalize]])
(def sequence-location {:end 44908822 :sequence "ga4gh:SQ.IIB53T8CNeJJdUqzn9V_JnRtQadwWCbl" :start 44908721 :type "SequenceLocation" })
(def sl (py/py** models SequenceLocation sequence-location))
(ga4gh_serialize sl)
(ga4gh_identify sl)
(ga4gh_digest sl)
(py/dir models)

(require-python '[ga4gh.vrs.extras.translator :as translator ])
(py/py** ga4gh.vrs.extras Translator {})



 
