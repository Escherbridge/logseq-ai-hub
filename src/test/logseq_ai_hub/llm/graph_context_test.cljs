(ns logseq-ai-hub.llm.graph-context-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.llm.graph-context :as graph-context]
            [clojure.string :as str]))

(defn- setup-mock! [page-map]
  (set! js/logseq
    #js {:Editor #js {:getPageBlocksTree
                      (fn [page-name]
                        (if-let [blocks (get page-map page-name)]
                          (js/Promise.resolve (clj->js blocks))
                          (js/Promise.resolve nil)))}
         :settings #js {:pageRefDepth 0
                        :pageRefMaxTokens 8000}}))

(deftest resolve-page-refs-empty
  (testing "returns nil for empty page refs"
    (async done
      (-> (graph-context/resolve-page-refs [] {})
          (.then (fn [result]
                   (is (nil? result))
                   (done)))))))

(deftest resolve-page-refs-single-page
  (testing "fetches content from a single page"
    (async done
      (setup-mock!
        {"My Research" [{:content "Research findings here"}
                        {:content "More data points"}]})
      (-> (graph-context/resolve-page-refs ["My Research"] {})
          (.then (fn [result]
                   (is (some? result))
                   (is (str/includes? result "Context from referenced pages"))
                   (is (str/includes? result "My Research"))
                   (is (str/includes? result "Research findings"))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest resolve-page-refs-nonexistent-page
  (testing "handles nonexistent pages gracefully"
    (async done
      (setup-mock! {})
      (-> (graph-context/resolve-page-refs ["Nonexistent"] {})
          (.then (fn [result]
                   (is (nil? result) "Should return nil when page has no content")
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest resolve-page-refs-respects-token-limit
  (testing "stops fetching when token budget is exhausted"
    (async done
      ;; Page A: 100 words = ~500 chars → ~126 tokens with "- " prefix
      ;; Budget: 130 tokens — Page A fits and nearly exhausts it
      ;; Page B: ~7 tokens — won't fit in remaining ~4 tokens
      (let [medium-content (str/join (repeat 100 "word "))]
        (setup-mock!
          {"Page A" [{:content medium-content}]
           "Page B" [{:content "This should be skipped"}]})
        (-> (graph-context/resolve-page-refs ["Page A" "Page B"] {:max-tokens 130})
            (.then (fn [result]
                     (is (some? result) "Page A should have been included")
                     (when result
                       (is (str/includes? result "Page A"))
                       (is (not (str/includes? result "Page B"))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Promise rejected: " err))
                      (done))))))))

(deftest resolve-page-refs-with-depth-traversal
  (testing "follows links to depth 1"
    (async done
      (setup-mock!
        {"Main Page" [{:content "See [[Sub Page]] for details"}]
         "Sub Page" [{:content "Sub-page content here"}]})
      (-> (graph-context/resolve-page-refs ["Main Page"] {:depth 1})
          (.then (fn [result]
                   (is (some? result))
                   (is (str/includes? result "Main Page"))
                   (is (str/includes? result "Sub Page"))
                   (is (str/includes? result "Sub-page content here"))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest resolve-page-refs-avoids-cycles
  (testing "does not revisit already-visited pages"
    (async done
      (let [fetch-count (atom 0)]
        (set! js/logseq
          #js {:Editor #js {:getPageBlocksTree
                            (fn [page-name]
                              (swap! fetch-count inc)
                              (case page-name
                                "A" (js/Promise.resolve
                                      (clj->js [{:content "Links to [[B]]"}]))
                                "B" (js/Promise.resolve
                                      (clj->js [{:content "Links back to [[A]]"}]))
                                (js/Promise.resolve nil)))}
               :settings #js {:pageRefDepth 0
                               :pageRefMaxTokens 8000}})
        (-> (graph-context/resolve-page-refs ["A"] {:depth 5})
            (.then (fn [_]
                     ;; Should only fetch A and B once each, not loop
                     (is (<= @fetch-count 2)
                         "Should not re-fetch pages in a cycle")
                     (done)))
            (.catch (fn [err]
                      (is false (str "Promise rejected: " err))
                      (done))))))))

(deftest resolve-page-refs-skips-special-prefixes
  (testing "does not follow MCP/ or AI-Memory/ links during traversal"
    (async done
      (setup-mock!
        {"Main" [{:content "See [[MCP/server]] and [[AI-Memory/notes]] and [[Real Page]]"}]
         "Real Page" [{:content "Real content"}]})
      (-> (graph-context/resolve-page-refs ["Main"] {:depth 1})
          (.then (fn [result]
                   (is (some? result))
                   (is (str/includes? result "Real Page"))
                   ;; MCP and AI-Memory pages should not be fetched as context
                   (is (not (str/includes? result "### MCP/server")))
                   (is (not (str/includes? result "### AI-Memory/notes")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))
