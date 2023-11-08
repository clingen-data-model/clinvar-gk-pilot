Most times when creating the clinvar-gk-files out of main.clj the system will be configured to point to a variant-normalizer/gene normalizer/seqrepo/uta server. But
there are times where running the vrs-python library directly is useful and faster. This describes a brief setup
of using vrs-python accessed via lib-python.

Setup Notes

Prerequisites

You need:
- vrs-python and it's submodules downloaded from github locally.
- seqrepo data loaded locally
- a seqrepo-rest-service server docker image

REPL 

To run with lib-python and vrs-python enabled:

/usr/local/bin/clojure -A:lib-python -Sdeps \
    '{:deps {nrepl/nrepl {:mvn/version "0.9.0"} cider/cider-nrepl {:mvn/version "0.28.5"}} \
      :aliases {:cider/nrepl {:main-opts ["-m" "nrepl.cmdline" "--middleware" "[cider.nrepl/cider-middleware]"]}}}'
      -M:cider/nrepl
Pass command line option ':use-vrs-python true'

To exclude lib-python and vrs-python running directly:

/usr/local/bin/clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "0.9.0"} cider/cider-nrepl \
    {:mvn/version "0.28.5"}} :aliases {:cider/nrepl {:main-opts ["-m" "nrepl.cmdline" "--middleware" \
    "[cider.nrepl/cider-middleware]"]}}}' -M:cider/nrepl
Pass command line option ':use-vrs-python false'

Other command line options:
   :filename "one.json"
   :normalizer-url "http://normalization-dev.clingen.app/variation"
   :do-liftover false

Current varition-normalizer URLS:
   http://normalization-dev.clingen.app/variation - VRS 2.0 alpha (running in broad gcp)
   http://normalization.clingen.app/variation - VRS 1.3 (running in broad gcp)



