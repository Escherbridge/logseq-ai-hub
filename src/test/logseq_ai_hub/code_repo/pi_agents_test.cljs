(ns logseq-ai-hub.code-repo.pi-agents-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [logseq-ai-hub.code-repo.pi-agents :as pi-agents]))

;; ---------------------------------------------------------------------------
;; Mock setup
;; ---------------------------------------------------------------------------

(def created-pages (atom []))
(def appended-blocks (atom []))

(defn setup-mocks! []
  (reset! created-pages [])
  (reset! appended-blocks [])
  (set! js/logseq #js {})
  (set! (.-DB js/logseq) #js {:datascriptQuery (fn [_q] (js/Promise.resolve #js []))})
  (set! (.-Editor js/logseq)
    #js {:getPage            (fn [_name] (js/Promise.resolve nil))
         :getPageBlocksTree  (fn [_name] (js/Promise.resolve #js []))
         :createPage         (fn [name _props _opts]
                               (swap! created-pages conj name)
                               (js/Promise.resolve #js {:name name :uuid "page-uuid"}))
         :appendBlockInPage  (fn [page content]
                               (swap! appended-blocks conj {:page page :content content})
                               (js/Promise.resolve #js {:uuid "block-uuid"}))
         :upsertBlockProperty (fn [_uuid _key _val]
                                (js/Promise.resolve nil))}))

(use-fixtures :each {:before setup-mocks!})

;; ---------------------------------------------------------------------------
;; 1. parse-pi-agent-properties — extracts fields (sync)
;; ---------------------------------------------------------------------------

(deftest parse-pi-agent-properties-extracts-fields
  (testing "Extracts all known pi-agent properties from props map"
    (let [props {"pi-agent-name"        "cody"
                 "pi-agent-model"       "anthropic/claude-opus-4"
                 "pi-agent-project"     "my-project"
                 "pi-agent-description" "A helpful coding agent"}
          result (pi-agents/parse-pi-agent-properties props)]
      (is (= "cody" (:name result)))
      (is (= "anthropic/claude-opus-4" (:model result)))
      (is (= "my-project" (:project result)))
      (is (= "A helpful coding agent" (:description result))))))

;; ---------------------------------------------------------------------------
;; 2. parse-pi-agent-properties — uses defaults (sync)
;; ---------------------------------------------------------------------------

(deftest parse-pi-agent-properties-uses-defaults
  (testing "Returns default model when pi-agent-model is absent"
    (let [result (pi-agents/parse-pi-agent-properties {})]
      (is (= "" (:name result)))
      (is (= "anthropic/claude-sonnet-4" (:model result)))
      (is (= "" (:project result)))
      (is (= "" (:description result)))))

  (testing "Returns default model when only some keys are present"
    (let [props {"pi-agent-name" "ava"}
          result (pi-agents/parse-pi-agent-properties props)]
      (is (= "ava" (:name result)))
      (is (= "anthropic/claude-sonnet-4" (:model result))))))

;; ---------------------------------------------------------------------------
;; 3. extract-agent-sections — parses block tree sections (sync)
;; ---------------------------------------------------------------------------

(deftest extract-agent-sections-parses-sections
  (testing "Extracts all four sections from mock block tree"
    (let [mock-blocks
          (clj->js
            [{:content "## System Instructions"
              :children [{:content "You are a coding assistant."}
                         {:content "Focus on clean code."}]}
             {:content "## Skills"
              :children [{:content "ClojureScript"}
                         {:content "TypeScript"}]}
             {:content "## Allowed Tools"
              :children [{:content "read_file"}
                         {:content "write_file"}]}
             {:content "## Restricted Operations"
              :children [{:content "No shell execution"}]}])
          sections (pi-agents/extract-agent-sections mock-blocks)]
      (is (clojure.string/includes? (:system-instructions sections) "coding assistant"))
      (is (clojure.string/includes? (:skills sections) "ClojureScript"))
      (is (clojure.string/includes? (:allowed-tools sections) "read_file"))
      (is (clojure.string/includes? (:restricted-operations sections) "No shell"))))

  (testing "Returns empty strings for empty block array"
    (let [sections (pi-agents/extract-agent-sections #js [])]
      (is (= "" (:system-instructions sections)))
      (is (= "" (:skills sections)))
      (is (= "" (:allowed-tools sections)))
      (is (= "" (:restricted-operations sections)))))

  (testing "Returns empty strings for nil blocks"
    (let [sections (pi-agents/extract-agent-sections nil)]
      (is (= "" (:system-instructions sections)))
      (is (= "" (:restricted-operations sections))))))

;; ---------------------------------------------------------------------------
;; 4. handle-pi-agent-list — resolves even with no required params (async)
;; ---------------------------------------------------------------------------

(deftest handle-pi-agent-list-rejects-nothing
  (async done
    ;; DB mock returns empty array (setup-mocks! default)
    (-> (pi-agents/handle-pi-agent-list {})
        (.then (fn [result]
                 (testing "Result has :agents key"
                   (is (vector? (:agents result))))
                 (testing "Result has :count key"
                   (is (number? (:count result))))
                 (testing "Empty scan returns empty agents"
                   (is (= 0 (:count result))))))
        (.catch (fn [err]
                  (is false (str "Should not reject: " (.-message err)))))
        (.finally done))))

;; ---------------------------------------------------------------------------
;; 5. handle-pi-agent-get — rejects when name is blank (async)
;; ---------------------------------------------------------------------------

(deftest handle-pi-agent-get-rejects-missing-name
  (async done
    (-> (pi-agents/handle-pi-agent-get {"name" ""})
        (.then (fn [_]
                 (is false "Should have rejected")))
        (.catch (fn [err]
                  (is (string? (.-message err)))
                  (is (clojure.string/includes? (.-message err) "name"))))
        (.finally done))))

;; ---------------------------------------------------------------------------
;; 6. handle-pi-agent-create — rejects when name is blank (async)
;; ---------------------------------------------------------------------------

(deftest handle-pi-agent-create-rejects-missing-name
  (async done
    (-> (pi-agents/handle-pi-agent-create {"name" ""
                                            "project" "my-project"})
        (.then (fn [_]
                 (is false "Should have rejected")))
        (.catch (fn [err]
                  (is (string? (.-message err)))
                  (is (clojure.string/includes? (.-message err) "name"))))
        (.finally done))))

;; ---------------------------------------------------------------------------
;; 7. handle-pi-agent-create — builds page correctly (async, mocked editor)
;; ---------------------------------------------------------------------------

(deftest handle-pi-agent-create-builds-page
  (async done
    (-> (pi-agents/handle-pi-agent-create
          {"name"                 "cody"
           "project"             "my-project"
           "model"               "anthropic/claude-opus-4"
           "description"         "My coding agent"
           "systemInstructions"  "You are a helpful coding assistant."
           "skills"              "ClojureScript, TypeScript"
           "allowedTools"        "read_file, write_file"
           "restrictedOperations" "No shell execution"})
        (.then (fn [result]
                 (testing "Result has :page"
                   (is (= "PI-Agents/cody" (:page result))))
                 (testing "Result has :created true"
                   (is (true? (:created result))))
                 (testing "createPage was called once"
                   (is (= 1 (count @created-pages))))
                 (testing "createPage called with correct page name"
                   (is (= "PI-Agents/cody" (first @created-pages))))
                 (testing "Four section blocks were appended"
                   (is (= 4 (count @appended-blocks))))
                 (testing "System Instructions block was appended"
                   (is (some #(clojure.string/includes? (:content %) "## System Instructions")
                             @appended-blocks)))
                 (testing "Skills block was appended"
                   (is (some #(clojure.string/includes? (:content %) "## Skills")
                             @appended-blocks)))
                 (testing "Allowed Tools block was appended"
                   (is (some #(clojure.string/includes? (:content %) "## Allowed Tools")
                             @appended-blocks)))
                 (testing "Restricted Operations block was appended"
                   (is (some #(clojure.string/includes? (:content %) "## Restricted Operations")
                             @appended-blocks)))))
        (.catch (fn [err]
                  (is false (str "Should not reject: " (.-message err)))))
        (.finally done))))

;; ---------------------------------------------------------------------------
;; 8. handle-pi-agent-update — rejects when name is blank (async)
;; ---------------------------------------------------------------------------

(deftest handle-pi-agent-update-rejects-missing-name
  (async done
    (-> (pi-agents/handle-pi-agent-update {"name" "   "
                                            "model" "anthropic/claude-opus-4"})
        (.then (fn [_]
                 (is false "Should have rejected")))
        (.catch (fn [err]
                  (is (string? (.-message err)))
                  (is (clojure.string/includes? (.-message err) "name"))))
        (.finally done))))
