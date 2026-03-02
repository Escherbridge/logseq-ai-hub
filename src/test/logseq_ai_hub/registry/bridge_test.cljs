(ns logseq-ai-hub.registry.bridge-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [logseq-ai-hub.registry.bridge :as bridge]
            [logseq-ai-hub.registry.store :as store]))

(defn setup-mocks! []
  (store/init-store!)
  ;; Add some test entries
  (store/add-entry {:id "send-slack" :type :tool :name "Send Slack"
                    :description "Send notifications to Slack" :properties {:handler :http}
                    :source :graph-page})
  (store/add-entry {:id "email-send" :type :tool :name "Email Sender"
                    :description "Send emails" :properties {:handler :http}
                    :source :graph-page})
  (store/add-entry {:id "code-review" :type :prompt :name "Code Review"
                    :description "Review code for quality" :properties {}
                    :source :graph-page})
  (store/add-entry {:id "deploy" :type :procedure :name "Deploy"
                    :description "Deploy to production" :properties {}
                    :source :graph-page}))

(use-fixtures :each {:before setup-mocks!})

(deftest test-handle-registry-list-all
  (testing "Lists all entries without filter"
    (async done
      (-> (bridge/handle-registry-list {})
          (.then (fn [result]
                   (is (= 4 (:count result)))
                   (is (= 4 (count (:entries result))))
                   (done)))
          (.catch (fn [err] (is false (str "Error: " err)) (done)))))))

(deftest test-handle-registry-list-by-type
  (testing "Lists entries filtered by type"
    (async done
      (-> (bridge/handle-registry-list {"type" "tool"})
          (.then (fn [result]
                   (is (= 2 (:count result)))
                   (is (every? #(= "tool" (:type %)) (:entries result)))
                   (done)))
          (.catch (fn [err] (is false (str "Error: " err)) (done)))))))

(deftest test-handle-registry-get-found
  (testing "Gets an existing entry"
    (async done
      (-> (bridge/handle-registry-get {"name" "send-slack" "type" "tool"})
          (.then (fn [result]
                   (is (= "send-slack" (:id result)))
                   (is (= "Send Slack" (:name result)))
                   (done)))
          (.catch (fn [err] (is false (str "Error: " err)) (done)))))))

(deftest test-handle-registry-get-not-found
  (testing "Returns error for non-existent entry"
    (async done
      (-> (bridge/handle-registry-get {"name" "nonexistent" "type" "tool"})
          (.then (fn [_] (is false "Should have rejected") (done)))
          (.catch (fn [err]
                    (is (re-find #"not found" (str err)))
                    (done)))))))

(deftest test-handle-registry-get-missing-params
  (testing "Returns error for missing params"
    (async done
      (-> (bridge/handle-registry-get {"name" "test"})
          (.then (fn [_] (is false "Should have rejected") (done)))
          (.catch (fn [err]
                    (is (re-find #"Missing" (str err)))
                    (done)))))))

(deftest test-handle-registry-search
  (testing "Searches by keyword"
    (async done
      (-> (bridge/handle-registry-search {"query" "slack"})
          (.then (fn [result]
                   (is (= 1 (:count result)))
                   (is (= "send-slack" (:id (first (:entries result)))))
                   (done)))
          (.catch (fn [err] (is false (str "Error: " err)) (done)))))))

(deftest test-handle-registry-search-with-type
  (testing "Searches with type filter"
    (async done
      (-> (bridge/handle-registry-search {"query" "send" "type" "tool"})
          (.then (fn [result]
                   (is (= 2 (:count result)))
                   (done)))
          (.catch (fn [err] (is false (str "Error: " err)) (done)))))))

(deftest test-handle-registry-search-missing-query
  (testing "Returns error for missing query"
    (async done
      (-> (bridge/handle-registry-search {})
          (.then (fn [_] (is false "Should have rejected") (done)))
          (.catch (fn [err]
                    (is (re-find #"Missing" (str err)))
                    (done)))))))

(deftest test-handle-execute-skill-missing-id
  (testing "Returns error for missing skillId"
    (async done
      (-> (bridge/handle-execute-skill {})
          (.then (fn [_] (is false "Should have rejected") (done)))
          (.catch (fn [err]
                    (is (re-find #"Missing" (str err)))
                    (done)))))))

(deftest test-handle-execute-skill-not-initialized
  (testing "Returns error when executor not wired"
    (async done
      (reset! bridge/bridge-fns {})
      (-> (bridge/handle-execute-skill {"skillId" "Skills/foo"})
          (.then (fn [_] (is false "Should have rejected") (done)))
          (.catch (fn [err]
                    (is (re-find #"not initialized" (str err)))
                    (done)))))))

(deftest test-handle-execute-skill-with-mock
  (testing "Delegates to wired executor function"
    (async done
      (bridge/set-execute-skill-fn!
        (fn [skill-id inputs]
          (js/Promise.resolve {:skill-id skill-id :result "success" :inputs inputs})))
      (-> (bridge/handle-execute-skill {"skillId" "Skills/test" "inputs" {"query" "hello"}})
          (.then (fn [result]
                   (is (= "Skills/test" (:skill-id result)))
                   (is (= "success" (:result result)))
                   (done)))
          (.catch (fn [err] (is false (str "Error: " err)) (done)))))))
