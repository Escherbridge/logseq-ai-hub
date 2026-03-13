(ns logseq-ai-hub.code-repo.init-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [logseq-ai-hub.code-repo.init :as init]))

(defn setup-mocks! []
  ;; Reset init state
  (init/shutdown!)
  ;; Setup minimal Logseq API mocks
  (set! js/logseq #js {})
  (set! js/logseq.DB #js {:datascriptQuery (fn [_q] (js/Promise.resolve #js []))
                           :onChanged (fn [_cb] nil)})
  (set! js/logseq.Editor #js {:getPageBlocksTree (fn [_name] (js/Promise.resolve #js []))
                               :registerSlashCommand (fn [_ _] nil)
                               :createPage (fn [name _props _opts] (js/Promise.resolve #js {:name name}))
                               :appendBlockInPage (fn [_page _content] (js/Promise.resolve #js {:uuid "mock-uuid"}))})
  (set! js/logseq.UI #js {:showMsg (fn [& _] nil)})
  (set! js/logseq.settings #js {}))

(use-fixtures :each {:before setup-mocks!})

(deftest test-init-returns-promise
  (testing "init! returns a Promise that resolves"
    (async done
      (-> (init/init!)
          (.then (fn [result]
                   (is (= "initialized" (:status result)))
                   (is (number? (:projects result)))
                   (done)))
          (.catch (fn [err] (is false (str "Error: " err)) (done)))))))

(deftest test-init-idempotent
  (testing "Second init! returns already-initialized"
    (async done
      (-> (init/init!)
          (.then (fn [_]
                   (init/init!)))
          (.then (fn [result]
                   (is (= "already-initialized" (:status result)))
                   (done)))
          (.catch (fn [err] (is false (str "Error: " err)) (done)))))))

(deftest test-shutdown-resets-state
  (testing "shutdown! allows re-initialization"
    (async done
      (-> (init/init!)
          (.then (fn [_]
                   (init/shutdown!)
                   (init/init!)))
          (.then (fn [result]
                   (is (= "initialized" (:status result)))
                   (done)))
          (.catch (fn [err] (is false (str "Error: " err)) (done)))))))
