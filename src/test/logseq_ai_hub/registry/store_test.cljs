(ns logseq-ai-hub.registry.store-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [logseq-ai-hub.registry.store :as store]))

(use-fixtures :each
  {:before (fn [] (store/init-store!))})

(deftest test-init-store
  (testing "init-store! creates empty registry with expected structure"
    (store/init-store!)
    (let [snap (store/get-snapshot)]
      (is (empty? (:tools snap)))
      (is (empty? (:prompts snap)))
      (is (empty? (:procedures snap)))
      (is (empty? (:agents snap)))
      (is (empty? (:skills snap)))
      (is (= 0 (:version snap)))
      (is (nil? (:last-scan snap))))))

(deftest test-add-entry
  (testing "Adding a tool entry"
    (store/add-entry {:id "send-slack" :type :tool :name "Send Slack"
                      :description "Send to Slack" :properties {}})
    (is (= 1 (count (store/list-entries :tool))))
    (is (= "Send Slack" (:name (store/get-entry :tool "send-slack")))))

  (testing "Adding a prompt entry"
    (store/add-entry {:id "code-review" :type :prompt :name "Code Review"
                      :description "Review code" :properties {}})
    (is (= 1 (count (store/list-entries :prompt)))))

  (testing "Adding entries of different types"
    (store/add-entry {:id "deploy" :type :procedure :name "Deploy"
                      :description "Deploy to prod" :properties {}})
    (store/add-entry {:id "reviewer" :type :agent :name "Reviewer"
                      :description "Code reviewer agent" :properties {}})
    (store/add-entry {:id "summarize" :type :skill :name "Summarize"
                      :description "Summarize text" :properties {}})
    (is (= 1 (count (store/list-entries :procedure))))
    (is (= 1 (count (store/list-entries :agent))))
    (is (= 1 (count (store/list-entries :skill))))))

(deftest test-remove-entry
  (testing "Removing an existing entry returns true"
    (store/add-entry {:id "test-tool" :type :tool :name "Test" :description "Test"})
    (is (true? (store/remove-entry :tool "test-tool")))
    (is (nil? (store/get-entry :tool "test-tool"))))

  (testing "Removing a non-existent entry returns false"
    (is (false? (store/remove-entry :tool "non-existent")))))

(deftest test-get-entry
  (testing "Get existing entry returns it"
    (store/add-entry {:id "my-tool" :type :tool :name "My Tool"
                      :description "A tool" :properties {:handler :http}})
    (let [entry (store/get-entry :tool "my-tool")]
      (is (= "my-tool" (:id entry)))
      (is (= :tool (:type entry)))
      (is (= "My Tool" (:name entry)))
      (is (= :http (get-in entry [:properties :handler])))))

  (testing "Get non-existent entry returns nil"
    (is (nil? (store/get-entry :tool "does-not-exist")))))

(deftest test-list-entries
  (testing "List by type returns only that type"
    (store/add-entry {:id "t1" :type :tool :name "Tool 1" :description "First"})
    (store/add-entry {:id "t2" :type :tool :name "Tool 2" :description "Second"})
    (store/add-entry {:id "p1" :type :prompt :name "Prompt 1" :description "A prompt"})
    (is (= 2 (count (store/list-entries :tool))))
    (is (= 1 (count (store/list-entries :prompt)))))

  (testing "List without type returns all entries"
    (let [all (store/list-entries)]
      (is (= 3 (count all))))))

(deftest test-search-entries
  (testing "Search by name substring"
    (store/add-entry {:id "slack-notify" :type :tool :name "Slack Notification"
                      :description "Send notifications"})
    (store/add-entry {:id "email-send" :type :tool :name "Email Sender"
                      :description "Send emails"})
    (store/add-entry {:id "code-review" :type :prompt :name "Code Review"
                      :description "Review code quality"})
    (let [results (store/search-entries "slack")]
      (is (= 1 (count results)))
      (is (= "slack-notify" (:id (first results))))))

  (testing "Search by description substring"
    (let [results (store/search-entries "send")]
      (is (= 2 (count results)))))

  (testing "Search is case-insensitive"
    (let [results (store/search-entries "SLACK")]
      (is (= 1 (count results)))))

  (testing "Search with type filter"
    (let [results (store/search-entries "send" :tool)]
      (is (= 2 (count results))))
    (let [results (store/search-entries "send" :prompt)]
      (is (= 0 (count results)))))

  (testing "Search with no matches"
    (is (= 0 (count (store/search-entries "xyz-no-match"))))))

(deftest test-get-snapshot
  (testing "Snapshot returns serializable data"
    (store/add-entry {:id "t1" :type :tool :name "T1" :description "Tool 1"})
    (store/add-entry {:id "p1" :type :prompt :name "P1" :description "Prompt 1"})
    (let [snap (store/get-snapshot)]
      (is (= 1 (count (:tools snap))))
      (is (= 1 (count (:prompts snap))))
      (is (= 0 (count (:procedures snap))))
      (is (= 0 (:version snap))))))

(deftest test-bump-version
  (testing "Bumping version increments counter"
    (is (= 0 (:version (store/get-snapshot))))
    (let [v1 (store/bump-version!)]
      (is (= 1 v1))
      (is (= 1 (:version (store/get-snapshot)))))
    (let [v2 (store/bump-version!)]
      (is (= 2 v2))))

  (testing "Bumping version sets last-scan timestamp"
    (store/bump-version!)
    (is (some? (:last-scan (store/get-snapshot))))))

(deftest test-clear-category
  (testing "Clear removes all entries in a category"
    (store/add-entry {:id "t1" :type :tool :name "T1" :description "D1"})
    (store/add-entry {:id "t2" :type :tool :name "T2" :description "D2"})
    (store/add-entry {:id "p1" :type :prompt :name "P1" :description "D3"})
    (is (= 2 (count (store/list-entries :tool))))
    (store/clear-category! :tool)
    (is (= 0 (count (store/list-entries :tool))))
    ;; Other categories unaffected
    (is (= 1 (count (store/list-entries :prompt))))))
