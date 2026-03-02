(ns logseq-ai-hub.registry.commands-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.registry.commands :as commands]
            [logseq-ai-hub.registry.store :as store]))

(deftest test-command-registry
  (testing "Command registry contains expected commands"
    (is (contains? commands/command-registry "registry:refresh"))
    (is (contains? commands/command-registry "registry:list"))
    (is (= 2 (count commands/command-registry)))))

(deftest test-handlers-are-functions
  (testing "All handlers are functions"
    (doseq [[_name handler] commands/command-registry]
      (is (fn? handler)))))

(deftest test-handle-registry-list-formats-entries
  (testing "handle-registry-list formats entries from store"
    ;; Set up store with test entries
    (store/init-store!)
    (store/add-entry {:id "Tools/test-tool"
                      :type :tool
                      :name "test-tool"
                      :description "A test tool"})
    (store/add-entry {:id "Skills/test-skill"
                      :type :skill
                      :name "test-skill"
                      :description "A test skill"})
    (store/bump-version!)

    ;; Mock Logseq API
    (let [inserted (atom nil)]
      (set! js/logseq.Editor.insertBlock
            (fn [_uuid content & _]
              (reset! inserted content)))

      ;; Call handler with mock event
      (commands/handle-registry-list #js {:uuid "block-123"})

      ;; Verify formatted output includes our entries
      (is (some? @inserted))
      (is (re-find #"test-tool" @inserted))
      (is (re-find #"test-skill" @inserted))
      (is (re-find #"\[tool\]" @inserted))
      (is (re-find #"\[skill\]" @inserted))
      (is (re-find #"2 entries" @inserted))

      ;; Clean up
      (store/init-store!))))
