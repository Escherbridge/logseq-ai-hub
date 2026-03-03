(ns logseq-ai-hub.registry.init-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.registry.init :as init]))

(deftest test-init-is-function
  (testing "init! is a function"
    (is (fn? init/init!))))
