(ns test-phase1
  (:require [cljs.test :refer [run-tests]]
            [logseq-ai-hub.job-runner.schemas-test]
            [logseq-ai-hub.job-runner.parser-test]
            [logseq-ai-hub.job-runner.interpolation-test]))

(defn -main []
  (run-tests 'logseq-ai-hub.job-runner.schemas-test
             'logseq-ai-hub.job-runner.parser-test
             'logseq-ai-hub.job-runner.interpolation-test))

(set! *main-cli-fn* -main)
