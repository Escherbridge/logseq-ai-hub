(ns logseq-ai-hub.code-repo.safeguard-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [clojure.string :as str]
            [logseq-ai-hub.code-repo.safeguard :as safeguard]))

;;; Mock setup

(defn setup-mocks! []
  (set! js/logseq #js {})
  (set! js/logseq.DB #js {:datascriptQuery (fn [_q] (js/Promise.resolve #js []))})
  (set! js/logseq.Editor #js {:getPageBlocksTree (fn [_] (js/Promise.resolve #js []))
                               :createPage (fn [name _props _opts] (js/Promise.resolve #js {:name name}))
                               :appendBlockInPage (fn [_page _content] (js/Promise.resolve #js {:uuid "mock-uuid"}))}))

(use-fixtures :each {:before setup-mocks!})

;;; parse-safeguard-properties tests (synchronous)

(deftest test-parse-safeguard-properties-complete
  (testing "Parses all known safeguard properties"
    (let [content (str "safeguard-name:: No Force Push Policy\n"
                       "safeguard-project:: logseq-ai-hub\n"
                       "safeguard-level:: 3\n"
                       "safeguard-contact:: alice@example.com\n"
                       "safeguard-escalation-contact:: bob@example.com\n"
                       "safeguard-review-interval:: 30d\n"
                       "safeguard-auto-deny-after:: 5m\n"
                       "tags:: logseq-ai-hub-safeguard")
          props (safeguard/parse-safeguard-properties content)]
      (is (= "No Force Push Policy" (:safeguard-name props)))
      (is (= "logseq-ai-hub" (:safeguard-project props)))
      (is (= "3" (:safeguard-level props)))
      (is (= "alice@example.com" (:safeguard-contact props)))
      (is (= "bob@example.com" (:safeguard-escalation-contact props)))
      (is (= "30d" (:safeguard-review-interval props)))
      (is (= "5m" (:safeguard-auto-deny-after props))))))

(deftest test-parse-safeguard-properties-partial
  (testing "Parses only known properties, ignores unknown keys"
    (let [content (str "safeguard-project:: my-project\n"
                       "safeguard-level:: 1\n"
                       "unknown-key:: ignored-value\n"
                       "tags:: logseq-ai-hub-safeguard")
          props (safeguard/parse-safeguard-properties content)]
      (is (= "my-project" (:safeguard-project props)))
      (is (= "1" (:safeguard-level props)))
      (is (nil? (:safeguard-name props)))
      (is (nil? (:safeguard-contact props)))
      (is (nil? (get props :unknown-key))))))

(deftest test-parse-safeguard-properties-empty
  (testing "Returns empty map for empty content"
    (let [props (safeguard/parse-safeguard-properties "")]
      (is (= {} props))))

  (testing "Returns empty map for content with no matching properties"
    (let [props (safeguard/parse-safeguard-properties "Just free text\nNo properties here")]
      (is (= {} props)))))

;;; parse-safeguard-rules tests (synchronous)

(deftest test-parse-safeguard-rules-multiple-rules
  (testing "Parses multiple valid rule lines"
    (let [content (str "safeguard-project:: my-project\n"
                       "## Rules\n"
                       "- BLOCK: force push to any branch\n"
                       "- APPROVE: read operations on any file\n"
                       "- LOG: all write operations\n"
                       "- NOTIFY: deletion of files matching src/main/**\n")
          rules (safeguard/parse-safeguard-rules content)]
      (is (= 4 (count rules)))
      (let [block-rule (first (filter #(= (:action %) "block") rules))]
        (is (= "block" (:action block-rule)))
        (is (str/includes? (:description block-rule) "force push"))
        (is (nil? (:pattern block-rule))))
      (let [approve-rule (first (filter #(= (:action %) "approve") rules))]
        (is (= "approve" (:action approve-rule))))
      (let [log-rule (first (filter #(= (:action %) "log") rules))]
        (is (= "log" (:action log-rule))))
      (let [notify-rule (first (filter #(= (:action %) "notify") rules))]
        (is (= "notify" (:action notify-rule)))
        (is (= "src/main/**" (:pattern notify-rule)))))))

(deftest test-parse-safeguard-rules-no-rules
  (testing "Returns empty vector when no rule lines present"
    (let [content (str "safeguard-project:: my-project\n"
                       "safeguard-level:: 1\n"
                       "Some free text without any rules.")
          rules (safeguard/parse-safeguard-rules content)]
      (is (= [] rules)))))

(deftest test-parse-safeguard-rules-mixed-valid-invalid
  (testing "Only parses valid rule lines, skips invalid ones"
    (let [content (str "- BLOCK: delete main branch\n"
                       "- INVALID_ACTION: this should be skipped\n"
                       "- not a rule line at all\n"
                       "- LOG: audit everything\n"
                       "  - NOTIFY: indented rule\n")
          rules (safeguard/parse-safeguard-rules content)]
      (is (= 3 (count rules)))
      (is (every? #(contains? #{"block" "log" "notify"} (:action %)) rules))
      (is (not (some #(= (:action %) "invalid_action") rules))))))

(deftest test-parse-safeguard-rules-pattern-extraction
  (testing "Extracts pattern from 'matching <pattern>' in description"
    (let [content "- BLOCK: deletion of files matching src/**/*.cljs\n"
          rules (safeguard/parse-safeguard-rules content)]
      (is (= 1 (count rules)))
      (is (= "src/**/*.cljs" (:pattern (first rules))))))

  (testing "Returns nil pattern when no 'matching' keyword in description"
    (let [content "- BLOCK: force push to main branch\n"
          rules (safeguard/parse-safeguard-rules content)]
      (is (= 1 (count rules)))
      (is (nil? (:pattern (first rules)))))))

;;; parse-safeguard-page tests (synchronous)

(deftest test-parse-safeguard-page-valid
  (testing "Returns valid entry when safeguard-project and safeguard-level are present"
    (let [content (str "safeguard-name:: Strict Policy\n"
                       "safeguard-project:: logseq-ai-hub\n"
                       "safeguard-level:: 3\n"
                       "safeguard-contact:: alice@example.com\n"
                       "safeguard-escalation-contact:: bob@example.com\n"
                       "safeguard-review-interval:: 30d\n"
                       "safeguard-auto-deny-after:: 10m\n"
                       "tags:: logseq-ai-hub-safeguard\n"
                       "## Rules\n"
                       "- BLOCK: force push to any branch\n"
                       "- LOG: all file deletions\n")
          result (safeguard/parse-safeguard-page "Safeguards/logseq-ai-hub/strict-policy" content)]
      (is (true? (:valid result)))
      (is (= "Safeguards/logseq-ai-hub/strict-policy" (get-in result [:entry :id])))
      (is (= :safeguard (get-in result [:entry :type])))
      (is (= "Strict Policy" (get-in result [:entry :name])))
      (is (= "logseq-ai-hub" (get-in result [:entry :project])))
      (is (= 3 (get-in result [:entry :level])))
      (is (= "alice@example.com" (get-in result [:entry :contact])))
      (is (= "bob@example.com" (get-in result [:entry :escalation-contact])))
      (is (= "30d" (get-in result [:entry :review-interval])))
      (is (= "10m" (get-in result [:entry :auto-deny-after])))
      (is (= 2 (count (get-in result [:entry :rules]))))
      (is (= :graph-page (get-in result [:entry :source]))))))

(deftest test-parse-safeguard-page-valid-minimal
  (testing "Valid with only required fields - name falls back to page-name"
    (let [content (str "safeguard-project:: my-project\n"
                       "safeguard-level:: 1\n"
                       "tags:: logseq-ai-hub-safeguard")
          result (safeguard/parse-safeguard-page "Safeguards/my-project/default" content)]
      (is (true? (:valid result)))
      (is (= "Safeguards/my-project/default" (get-in result [:entry :name])))
      (is (= 1 (get-in result [:entry :level])))
      (is (= [] (get-in result [:entry :rules]))))))

(deftest test-parse-safeguard-page-invalid-missing-project
  (testing "Returns invalid when safeguard-project is missing"
    (let [content (str "safeguard-name:: Policy Without Project\n"
                       "safeguard-level:: 2\n"
                       "tags:: logseq-ai-hub-safeguard")
          result (safeguard/parse-safeguard-page "Safeguards/unknown/policy" content)]
      (is (false? (:valid result)))
      (is (vector? (:errors result)))
      (is (some #(= (:field %) :safeguard-project) (:errors result))))))

(deftest test-parse-safeguard-page-invalid-missing-level
  (testing "Returns invalid when safeguard-level is missing"
    (let [content (str "safeguard-name:: Policy Without Level\n"
                       "safeguard-project:: my-project\n"
                       "tags:: logseq-ai-hub-safeguard")
          result (safeguard/parse-safeguard-page "Safeguards/my-project/policy" content)]
      (is (false? (:valid result)))
      (is (vector? (:errors result)))
      (is (some #(= (:field %) :safeguard-level) (:errors result))))))

;;; handle-safeguard-policy-get tests (async, uses Logseq API mocks)

(deftest test-handle-safeguard-policy-get-found
  (async done
    (set! (.-datascriptQuery (.-DB js/logseq))
          (fn [_q]
            (js/Promise.resolve
              (clj->js [[{:block/name "safeguards/logseq-ai-hub/strict-policy"
                          :block/original-name "Safeguards/logseq-ai-hub/strict-policy"}]]))))
    (set! (.-getPageBlocksTree (.-Editor js/logseq))
          (fn [_page-name]
            (js/Promise.resolve
              (clj->js [{:content (str "safeguard-name:: Strict Policy\n"
                                       "safeguard-project:: logseq-ai-hub\n"
                                       "safeguard-level:: 3\n"
                                       "safeguard-contact:: alice@example.com\n"
                                       "tags:: logseq-ai-hub-safeguard\n"
                                       "## Rules\n"
                                       "- BLOCK: force push to any branch\n")}]))))
    (-> (safeguard/handle-safeguard-policy-get {"project" "logseq-ai-hub"})
        (.then (fn [result]
                 (is (= "logseq-ai-hub" (:project result)))
                 (is (= 3 (:level result)))
                 (is (= "supervised" (:levelName result)))
                 (is (= "Strict Policy" (:name result)))
                 (is (not (true? (:isDefault result))))
                 (is (= 1 (count (:rules result))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-handle-safeguard-policy-get-default-when-not-found
  (async done
    (set! (.-datascriptQuery (.-DB js/logseq))
          (fn [_q] (js/Promise.resolve #js [])))
    (-> (safeguard/handle-safeguard-policy-get {"project" "unknown-project"})
        (.then (fn [result]
                 (is (= "unknown-project" (:project result)))
                 (is (= "default" (:name result)))
                 (is (= 1 (:level result)))
                 (is (= "standard" (:levelName result)))
                 (is (= [] (:rules result)))
                 (is (true? (:isDefault result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

;;; handle-safeguard-audit-append tests (async, uses Logseq API mocks)

(deftest test-handle-safeguard-audit-append-appends-block
  (async done
    (let [appended-blocks (atom [])
          created-pages (atom [])]
      (set! (.-createPage (.-Editor js/logseq))
            (fn [name _props _opts]
              (swap! created-pages conj name)
              (js/Promise.resolve #js {:name name})))
      (set! (.-appendBlockInPage (.-Editor js/logseq))
            (fn [page content]
              (swap! appended-blocks conj {:page page :content content})
              (js/Promise.resolve #js {:uuid "mock-uuid"})))
      (-> (safeguard/handle-safeguard-audit-append
            {"project" "logseq-ai-hub"
             "operation" "git push --force origin main"
             "agent" "code-agent-1"
             "action" "block"
             "details" "Force push blocked by safeguard policy"})
          (.then (fn [result]
                   (is (true? (:logged result)))
                   (is (= "Projects/logseq-ai-hub/safeguard-log" (:page result)))
                   (is (= 1 (count @appended-blocks)))
                   (let [block (first @appended-blocks)]
                     (is (= "Projects/logseq-ai-hub/safeguard-log" (:page block)))
                     (is (str/includes? (:content block) "block:"))
                     (is (str/includes? (:content block) "git push --force origin main"))
                     (is (str/includes? (:content block) "code-agent-1"))
                     (is (str/includes? (:content block) "Force push blocked by safeguard policy")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-handle-safeguard-audit-append-creates-page-if-missing
  (async done
    (let [created-pages (atom [])]
      (set! (.-createPage (.-Editor js/logseq))
            (fn [name _props _opts]
              (swap! created-pages conj name)
              (js/Promise.resolve #js {:name name})))
      (set! (.-appendBlockInPage (.-Editor js/logseq))
            (fn [_page _content]
              (js/Promise.resolve #js {:uuid "mock-uuid"})))
      (-> (safeguard/handle-safeguard-audit-append
            {"project" "new-project"
             "operation" "rm -rf src/"
             "agent" "agent-x"
             "action" "block"
             "details" "File deletion blocked"})
          (.then (fn [result]
                   (is (true? (:logged result)))
                   (is (= "Projects/new-project/safeguard-log" (:page result)))
                   (is (some #(= % "Projects/new-project/safeguard-log") @created-pages))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))
