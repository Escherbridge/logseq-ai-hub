(ns logseq-ai-hub.llm.arg-parser-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.llm.arg-parser :as arg-parser]))

(deftest parse-llm-args-single-mcp-ref
  (testing "extracts single MCP reference"
    (let [result (arg-parser/parse-llm-args "[[MCP/brave-search]] Find news about ClojureScript")]
      (is (= ["MCP/brave-search"] (:mcp-refs result)))
      (is (= [] (:memory-refs result)))
      (is (= [] (:page-refs result)))
      (is (= "Find news about ClojureScript" (:prompt result))))))

(deftest parse-llm-args-multiple-mcp-refs
  (testing "extracts multiple MCP references"
    (let [result (arg-parser/parse-llm-args "[[MCP/brave-search]] [[MCP/github]] Search for repos")]
      (is (= ["MCP/brave-search" "MCP/github"] (:mcp-refs result)))
      (is (= "Search for repos" (:prompt result))))))

(deftest parse-llm-args-single-memory-ref
  (testing "extracts single Memory reference"
    (let [result (arg-parser/parse-llm-args "[[AI-Memory/project-notes]] summarize my notes")]
      (is (= [] (:mcp-refs result)))
      (is (= ["AI-Memory/project-notes"] (:memory-refs result)))
      (is (= "summarize my notes" (:prompt result))))))

(deftest parse-llm-args-multiple-memory-refs
  (testing "extracts multiple Memory references"
    (let [result (arg-parser/parse-llm-args "[[AI-Memory/notes]] [[AI-Memory/style]] review my code")]
      (is (= ["AI-Memory/notes" "AI-Memory/style"] (:memory-refs result)))
      (is (= "review my code" (:prompt result))))))

(deftest parse-llm-args-mixed-refs
  (testing "extracts both MCP and Memory references together"
    (let [result (arg-parser/parse-llm-args "[[MCP/brave-search]] [[AI-Memory/project]] Find news about my project")]
      (is (= ["MCP/brave-search"] (:mcp-refs result)))
      (is (= ["AI-Memory/project"] (:memory-refs result)))
      (is (= "Find news about my project" (:prompt result))))))

(deftest parse-llm-args-no-refs
  (testing "returns empty refs for plain text"
    (let [result (arg-parser/parse-llm-args "Just a normal question")]
      (is (= [] (:mcp-refs result)))
      (is (= [] (:memory-refs result)))
      (is (= [] (:page-refs result)))
      (is (= {} (:options result)))
      (is (= "Just a normal question" (:prompt result))))))

(deftest parse-llm-args-empty-content
  (testing "handles nil and empty content"
    (is (= {:mcp-refs [] :memory-refs [] :skill-refs [] :page-refs [] :options {} :prompt ""}
           (arg-parser/parse-llm-args nil)))
    (is (= {:mcp-refs [] :memory-refs [] :skill-refs [] :page-refs [] :options {} :prompt ""}
           (arg-parser/parse-llm-args "")))
    (is (= {:mcp-refs [] :memory-refs [] :skill-refs [] :page-refs [] :options {} :prompt ""}
           (arg-parser/parse-llm-args "  ")))))

(deftest parse-llm-args-collapses-whitespace
  (testing "collapses extra whitespace left by removed refs"
    (let [result (arg-parser/parse-llm-args "[[MCP/a]]  [[MCP/b]]  hello  world")]
      (is (= "hello world" (:prompt result))))))

(deftest parse-llm-args-ref-with-spaces
  (testing "handles refs with spaces in names"
    (let [result (arg-parser/parse-llm-args "[[MCP/my server]] query")]
      (is (= ["MCP/my server"] (:mcp-refs result)))
      (is (= "query" (:prompt result))))))

(deftest has-special-refs-test
  (testing "has-special-refs? detects special references"
    (is (true? (arg-parser/has-special-refs? {:mcp-refs ["MCP/x"] :memory-refs []})))
    (is (true? (arg-parser/has-special-refs? {:mcp-refs [] :memory-refs ["AI-Memory/x"]})))
    (is (true? (arg-parser/has-special-refs? {:mcp-refs ["MCP/x"] :memory-refs ["AI-Memory/y"]})))
    (is (false? (arg-parser/has-special-refs? {:mcp-refs [] :memory-refs []})))))

;; ---------------------------------------------------------------------------
;; Page reference tests
;; ---------------------------------------------------------------------------

(deftest parse-llm-args-extracts-page-refs
  (testing "extracts generic page references"
    (let [result (arg-parser/parse-llm-args "[[My Research]] summarize this")]
      (is (= ["My Research"] (:page-refs result)))
      (is (= [] (:mcp-refs result)))
      (is (= "summarize this" (:prompt result))))))

(deftest parse-llm-args-page-refs-with-mcp
  (testing "extracts page refs alongside MCP refs"
    (let [result (arg-parser/parse-llm-args "[[MCP/search]] [[Project Plan]] find info")]
      (is (= ["MCP/search"] (:mcp-refs result)))
      (is (= ["Project Plan"] (:page-refs result)))
      (is (= "find info" (:prompt result))))))

(deftest parse-llm-args-multiple-page-refs
  (testing "extracts multiple page references"
    (let [result (arg-parser/parse-llm-args "[[Page A]] [[Page B]] compare these")]
      (is (= ["Page A" "Page B"] (:page-refs result)))
      (is (= "compare these" (:prompt result))))))

(deftest parse-llm-args-all-three-ref-types
  (testing "extracts all three ref types together"
    (let [result (arg-parser/parse-llm-args
                   "[[MCP/brave]] [[AI-Memory/notes]] [[Project Spec]] research this")]
      (is (= ["MCP/brave"] (:mcp-refs result)))
      (is (= ["AI-Memory/notes"] (:memory-refs result)))
      (is (= ["Project Spec"] (:page-refs result)))
      (is (= "research this" (:prompt result))))))

;; ---------------------------------------------------------------------------
;; Skill reference tests
;; ---------------------------------------------------------------------------

(deftest parse-llm-args-single-skill-ref
  (testing "extracts single Skill reference"
    (let [result (arg-parser/parse-llm-args "[[Skills/summarize]] Summarize this document")]
      (is (= ["Skills/summarize"] (:skill-refs result)))
      (is (= [] (:mcp-refs result)))
      (is (= [] (:memory-refs result)))
      (is (= "Summarize this document" (:prompt result))))))

(deftest parse-llm-args-multiple-skill-refs
  (testing "extracts multiple Skill references"
    (let [result (arg-parser/parse-llm-args "[[Skills/summarize]] [[Skills/translate]] Process this")]
      (is (= ["Skills/summarize" "Skills/translate"] (:skill-refs result)))
      (is (= "Process this" (:prompt result))))))

(deftest parse-llm-args-skills-with-mcp-and-memory
  (testing "extracts skill refs alongside MCP and Memory refs"
    (let [result (arg-parser/parse-llm-args
                   "[[MCP/brave]] [[AI-Memory/notes]] [[Skills/summarize]] Do research")]
      (is (= ["MCP/brave"] (:mcp-refs result)))
      (is (= ["AI-Memory/notes"] (:memory-refs result)))
      (is (= ["Skills/summarize"] (:skill-refs result)))
      (is (= "Do research" (:prompt result))))))

(deftest parse-llm-args-skills-not-in-page-refs
  (testing "skill refs are not included in generic page-refs"
    (let [result (arg-parser/parse-llm-args "[[Skills/foo]] [[My Page]] question")]
      (is (= ["Skills/foo"] (:skill-refs result)))
      (is (= ["My Page"] (:page-refs result)))
      (is (not (some #{"Skills/foo"} (:page-refs result)))))))

(deftest has-special-refs-with-skills
  (testing "has-special-refs? detects skill references"
    (is (true? (arg-parser/has-special-refs?
                 {:mcp-refs [] :memory-refs [] :skill-refs ["Skills/x"]})))
    (is (false? (arg-parser/has-special-refs?
                  {:mcp-refs [] :memory-refs [] :skill-refs []})))))

(deftest has-context-refs-with-skills
  (testing "has-context-refs? detects skill references"
    (is (true? (arg-parser/has-context-refs?
                 {:mcp-refs [] :memory-refs [] :skill-refs ["Skills/x"] :page-refs []})))))

;; ---------------------------------------------------------------------------
;; Inline option tests
;; ---------------------------------------------------------------------------

(deftest parse-llm-args-inline-options
  (testing "parses depth and max-tokens options"
    (let [result (arg-parser/parse-llm-args "[[My Page]] depth:2 max-tokens:4000 summarize")]
      (is (= ["My Page"] (:page-refs result)))
      (is (= {:depth 2 :max-tokens 4000} (:options result)))
      (is (= "summarize" (:prompt result))))))

(deftest parse-llm-args-depth-only
  (testing "parses depth option alone"
    (let [result (arg-parser/parse-llm-args "[[Page]] depth:3 question here")]
      (is (= 3 (get-in result [:options :depth])))
      (is (nil? (get-in result [:options :max-tokens])))
      (is (= "question here" (:prompt result))))))

(deftest parse-llm-args-no-page-refs
  (testing "returns empty page-refs for text with no page links"
    (let [result (arg-parser/parse-llm-args "plain question")]
      (is (= [] (:page-refs result)))
      (is (= {} (:options result))))))

;; ---------------------------------------------------------------------------
;; has-context-refs? tests
;; ---------------------------------------------------------------------------

(deftest has-context-refs-test
  (testing "detects page references"
    (is (true? (arg-parser/has-context-refs? {:mcp-refs [] :memory-refs [] :page-refs ["X"]})))
    (is (true? (arg-parser/has-context-refs? {:mcp-refs ["Y"] :memory-refs [] :page-refs []})))
    (is (false? (arg-parser/has-context-refs? {:mcp-refs [] :memory-refs [] :page-refs []})))))
