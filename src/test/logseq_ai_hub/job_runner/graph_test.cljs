(ns logseq-ai-hub.job-runner.graph-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [logseq-ai-hub.job-runner.graph :as graph]))

(defn setup-logseq-mock! []
  (set! js/logseq
    #js {:Editor #js {:getPageBlocksTree (fn [page-name]
                                            (js/Promise.resolve
                                              (case page-name
                                                "Jobs/Test Job"
                                                #js [#js {:content "job-name:: Test Job"
                                                         :children #js [#js {:content "step 1: llm-call"}
                                                                       #js {:content "step 2: block-insert"}]}]
                                                "Skills/Test Skill"
                                                #js [#js {:content "skill-name:: Test Skill"
                                                         :children #js [#js {:content "step 1: graph-query"}]}]
                                                "Jobs/Test 1"
                                                #js [#js {:content "" :children nil}]
                                                "Jobs/Test 2"
                                                #js [#js {:content "" :children nil}]
                                                "Skills/Summarize"
                                                #js [#js {:content "" :children nil}]
                                                "Jobs/Empty"
                                                #js []
                                                nil)))
                       :appendBlockInPage (fn [page content]
                                            (js/Promise.resolve
                                              #js {:uuid "new-block-uuid"}))
                       :updateBlock (fn [uuid content]
                                     (js/Promise.resolve #js {}))
                       :upsertBlockProperty (fn [uuid key val]
                                             (js/Promise.resolve #js {}))}
         :DB #js {:datascriptQuery (fn [query]
                                     (cond
                                       (.includes query "jobs/")
                                       #js [#js [#js {"block/name" "jobs/test-1"
                                                     "block/original-name" "Jobs/Test 1"}]
                                           #js [#js {"block/name" "jobs/test-2"
                                                     "block/original-name" "Jobs/Test 2"}]]
                                       (.includes query "skills/")
                                       #js [#js [#js {"block/name" "skills/summarize"
                                                     "block/original-name" "Skills/Summarize"}]]
                                       :else #js []))}
         :settings #js {:selectedModel "mock-model"}}))

(use-fixtures :each
  {:before setup-logseq-mock!})

(deftest test-read-job-page
  (async done
    (-> (graph/read-job-page "Jobs/Test Job")
        (.then (fn [result]
                 (is (some? result) "Should return parsed job definition")
                 (is (map? result) "Should be a map")
                 (is (contains? result :job-id) "Should have :job-id key")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-read-job-page-not-found
  (async done
    (-> (graph/read-job-page "Jobs/Nonexistent")
        (.then (fn [result]
                 (is (nil? result) "Should return nil for nonexistent page")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-read-job-page-empty
  (async done
    (-> (graph/read-job-page "Jobs/Empty")
        (.then (fn [result]
                 (is (nil? result) "Should return nil for page with no blocks")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-read-skill-page
  (async done
    (-> (graph/read-skill-page "Skills/Test Skill")
        (.then (fn [result]
                 (is (some? result) "Should return parsed skill definition")
                 (is (map? result) "Should be a map")
                 (is (contains? result :skill-id) "Should have :skill-id key")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-scan-job-pages
  (async done
    (-> (graph/scan-job-pages "Jobs/")
        (.then (fn [results]
                 (is (vector? results) "Should return a vector")
                 (is (= 2 (count results)) "Should find 2 job pages")
                 (is (every? map? results) "All results should be maps")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-scan-skill-pages
  (async done
    (-> (graph/scan-skill-pages "Skills/")
        (.then (fn [results]
                 (is (vector? results) "Should return a vector")
                 (is (= 1 (count results)) "Should find 1 skill page")
                 (is (every? map? results) "All results should be maps")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-update-job-status
  (async done
    (let [update-calls (atom [])]
      (set! (.-upsertBlockProperty (.-Editor js/logseq))
            (fn [uuid key val]
              (swap! update-calls conj {:uuid uuid :key key :val val})
              (js/Promise.resolve #js {})))

      ;; Mock getPageBlocksTree to return a page with UUID
      (set! (.-getPageBlocksTree (.-Editor js/logseq))
            (fn [page-name]
              (js/Promise.resolve
                #js [#js {:uuid "page-uuid" :content "job-name:: Test"}])))

      (-> (graph/update-job-status! "Jobs/Test" "running")
          (.then (fn [_]
                   (is (= 1 (count @update-calls)) "Should make one update call")
                   (let [call (first @update-calls)]
                     (is (= "page-uuid" (:uuid call)))
                     (is (= "job-status" (:key call)))
                     (is (= "running" (:val call))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-update-job-property
  (async done
    (let [update-calls (atom [])]
      (set! (.-upsertBlockProperty (.-Editor js/logseq))
            (fn [uuid key val]
              (swap! update-calls conj {:uuid uuid :key key :val val})
              (js/Promise.resolve #js {})))

      (set! (.-getPageBlocksTree (.-Editor js/logseq))
            (fn [page-name]
              (js/Promise.resolve
                #js [#js {:uuid "page-uuid" :content "job-name:: Test"}])))

      (-> (graph/update-job-property! "Jobs/Test" "custom-key" "custom-value")
          (.then (fn [_]
                   (is (= 1 (count @update-calls)) "Should make one update call")
                   (let [call (first @update-calls)]
                     (is (= "page-uuid" (:uuid call)))
                     (is (= "custom-key" (:key call)))
                     (is (= "custom-value" (:val call))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-append-job-log
  (async done
    (let [append-calls (atom [])]
      (set! (.-appendBlockInPage (.-Editor js/logseq))
            (fn [page content]
              (swap! append-calls conj {:page page :content content})
              (js/Promise.resolve #js {:uuid "new-block-uuid"})))

      (-> (graph/append-job-log! "Jobs/Test" "Task completed successfully")
          (.then (fn [_]
                   (is (= 1 (count @append-calls)) "Should make one append call")
                   (let [call (first @append-calls)]
                     (is (= "Jobs/Test" (:page call)))
                     (is (.includes (:content call) "Task completed successfully")
                         "Content should include log message")
                     (is (re-find #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}" (:content call))
                         "Content should include ISO timestamp"))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-write-queue-serialization
  (async done
    (let [execution-order (atom [])
          delay-promise (fn [ms val]
                         (js/Promise. (fn [resolve]
                                       (js/setTimeout #(do
                                                        (swap! execution-order conj val)
                                                        (resolve val))
                                                     ms))))]

      ;; Queue three writes with different delays
      (graph/queue-write! #(delay-promise 50 :write-1))
      (graph/queue-write! #(delay-promise 10 :write-2))
      (graph/queue-write! #(delay-promise 5 :write-3))

      ;; Wait for all to complete
      (js/setTimeout
        (fn []
          (is (= [:write-1 :write-2 :write-3] @execution-order)
              "Writes should execute in queue order, not completion order")
          (done))
        200))))
