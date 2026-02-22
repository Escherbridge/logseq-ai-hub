(ns logseq-ai-hub.llm.memory-context-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.llm.memory-context :as memory-context]
            [logseq-ai-hub.memory :as memory]))

(defn- setup-memory-enabled! []
  (reset! memory/state {:config {:page-prefix "AI-Memory/" :enabled true}
                        :index {}}))

(deftest resolve-memory-refs-empty
  (testing "returns nil for empty refs"
    (async done
      (-> (memory-context/resolve-memory-refs [])
          (.then (fn [result]
                   (is (nil? result))
                   (done)))))))

(deftest resolve-memory-refs-with-blocks
  (testing "fetches and formats memory blocks"
    (async done
      (setup-memory-enabled!)
      ;; Mock getPageBlocksTree to return blocks
      (set! js/logseq
            #js {:Editor #js {:getPageBlocksTree
                              (fn [page-name]
                                (case page-name
                                  "AI-Memory/project"
                                  (js/Promise.resolve
                                    (clj->js [{:content "First memory block"}
                                              {:content "Second memory block"}]))
                                  (js/Promise.resolve nil)))}
                 :settings #js {:memoryEnabled true
                                :memoryPagePrefix "AI-Memory/"}
                 :DB #js {:datascriptQuery (fn [_] #js [])}})

      (-> (memory-context/resolve-memory-refs ["AI-Memory/project"])
          (.then (fn [result]
                   (is (some? result))
                   (is (clojure.string/includes? result "Context from your memories"))
                   (is (clojure.string/includes? result "project"))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest resolve-memory-refs-bad-prefix
  (testing "returns nil for refs without AI-Memory/ prefix"
    (async done
      (-> (memory-context/resolve-memory-refs ["SomethingElse/page"])
          (.then (fn [result]
                   (is (nil? result))
                   (done)))))))
