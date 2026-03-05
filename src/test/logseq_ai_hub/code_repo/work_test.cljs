(ns logseq-ai-hub.code-repo.work-test
  (:require [cljs.test :refer [deftest testing is async]]
            [logseq-ai-hub.code-repo.work :as work]))

;; ---------------------------------------------------------------------------
;; Validation tests
;; ---------------------------------------------------------------------------

(deftest handle-work-log-rejects-missing-project
  (async done
    (-> (work/handle-work-log {"project" "" "action" "commit" "details" "merged PR #42"})
        (.then (fn [_] (is false "Should have rejected")))
        (.catch (fn [err]
                  (is (re-find #"project" (.-message err)))
                  (done))))))

(deftest handle-work-log-rejects-missing-action
  (async done
    (-> (work/handle-work-log {"project" "my-proj" "action" "" "details" "some details"})
        (.then (fn [_] (is false "Should have rejected")))
        (.catch (fn [err]
                  (is (re-find #"action" (.-message err)))
                  (done))))))

;; ---------------------------------------------------------------------------
;; Happy-path tests (mock js/logseq.Editor)
;; ---------------------------------------------------------------------------

(deftest handle-work-log-creates-log-entry
  (async done
    (let [original-editor js/logseq.Editor
          create-calls    (atom [])
          append-calls    (atom [])]
      (set! js/logseq.Editor
        #js {:createPage       (fn [name _props _opts]
                                 (swap! create-calls conj {:name name})
                                 (js/Promise.resolve #js {:name name}))
             :appendBlockInPage (fn [name content]
                                  (swap! append-calls conj {:name name :content content})
                                  (js/Promise.resolve #js {:uuid "test-uuid"}))})
      (-> (work/handle-work-log {"project" "test-proj" "action" "commit" "details" "merged PR #42"})
          (.then (fn [result]
                   (is (= true (:logged result)))
                   (is (= "test-proj" (:project result)))
                   (is (= "commit" (:action result)))
                   (is (= 1 (count @create-calls)))
                   (is (= 1 (count @append-calls)))
                   (set! js/logseq.Editor original-editor)
                   (done)))
          (.catch (fn [err]
                    (set! js/logseq.Editor original-editor)
                    (is false (str "Should not reject: " err))
                    (done)))))))

(deftest handle-work-log-page-name-follows-expected-pattern
  (async done
    (let [original-editor js/logseq.Editor
          created-pages   (atom [])]
      (set! js/logseq.Editor
        #js {:createPage       (fn [name _props _opts]
                                 (swap! created-pages conj name)
                                 (js/Promise.resolve #js {:name name}))
             :appendBlockInPage (fn [_name _content]
                                  (js/Promise.resolve #js {:uuid "test-uuid"}))})
      (-> (work/handle-work-log {"project" "ardanova" "action" "deploy" "details" "v1.2.0"})
          (.then (fn [result]
                   (is (= "Projects/ardanova/log" (:page result)))
                   (is (= "Projects/ardanova/log" (first @created-pages)))
                   (set! js/logseq.Editor original-editor)
                   (done)))
          (.catch (fn [err]
                    (set! js/logseq.Editor original-editor)
                    (is false (str "Should not reject: " err))
                    (done)))))))

(deftest handle-work-log-block-content-includes-action-and-details
  (async done
    (let [original-editor js/logseq.Editor
          appended        (atom [])]
      (set! js/logseq.Editor
        #js {:createPage       (fn [name _props _opts]
                                 (js/Promise.resolve #js {:name name}))
             :appendBlockInPage (fn [_name content]
                                  (swap! appended conj content)
                                  (js/Promise.resolve #js {:uuid "test-uuid"}))})
      (-> (work/handle-work-log {"project" "my-proj" "action" "refactor" "details" "extracted helpers"})
          (.then (fn [_result]
                   (let [content (first @appended)]
                     (is (string? content))
                     (is (re-find #"\*\*refactor\*\*" content))
                     (is (re-find #"extracted helpers" content)))
                   (set! js/logseq.Editor original-editor)
                   (done)))
          (.catch (fn [err]
                    (set! js/logseq.Editor original-editor)
                    (is false (str "Should not reject: " err))
                    (done)))))))

(deftest handle-work-log-works-without-details
  (async done
    (let [original-editor js/logseq.Editor]
      (set! js/logseq.Editor
        #js {:createPage       (fn [name _props _opts]
                                 (js/Promise.resolve #js {:name name}))
             :appendBlockInPage (fn [_name _content]
                                  (js/Promise.resolve #js {:uuid "test-uuid"}))})
      (-> (work/handle-work-log {"project" "proj-x" "action" "review"})
          (.then (fn [result]
                   (is (= true (:logged result)))
                   (is (= "proj-x" (:project result)))
                   (set! js/logseq.Editor original-editor)
                   (done)))
          (.catch (fn [err]
                    (set! js/logseq.Editor original-editor)
                    (is false (str "Should not reject: " err))
                    (done)))))))
