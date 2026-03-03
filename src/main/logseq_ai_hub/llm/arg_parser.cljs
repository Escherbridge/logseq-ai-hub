(ns logseq-ai-hub.llm.arg-parser
  "Dynamic argument parser for LLM calls.
   Extracts special page references from block content before LLM processing:
   - [[MCP/...]] references for MCP tool augmentation
   - [[AI-Memory/...]] references for context injection
   - [[Skills/...]] references for ad-hoc skill invocation via tool-use
   - [[Page Name]] references for graph context injection
   - depth:N and max-tokens:N inline options"
  (:require [clojure.string :as str]))

(def ^:private mcp-ref-pattern
  "Regex matching [[MCP/server-name]] page references."
  #"\[\[MCP/([^\]]+)\]\]")

(def ^:private memory-ref-pattern
  "Regex matching [[AI-Memory/tag]] page references."
  #"\[\[AI-Memory/([^\]]+)\]\]")

(def ^:private skill-ref-pattern
  "Regex matching [[Skills/skill-name]] page references."
  #"\[\[Skills/([^\]]+)\]\]")

(def ^:private generic-ref-pattern
  "Regex matching any [[Page Name]] page reference."
  #"\[\[([^\]]+)\]\]")

(def ^:private inline-option-pattern
  "Regex matching key:value option tokens like depth:2 or max-tokens:4000"
  #"\b(depth|max-tokens):(\d+)\b")

(defn- parse-inline-options
  "Extracts depth:N and max-tokens:N from content.
   Returns {:depth N :max-tokens N} with only the keys that were found."
  [content]
  (let [matches (re-seq inline-option-pattern content)]
    (reduce (fn [opts [_ k v]]
              (assoc opts (keyword k) (js/parseInt v 10)))
            {}
            matches)))

(defn parse-llm-args
  "Extracts MCP, Memory, and Page references plus inline options from block content.

   Returns:
   {:mcp-refs [\"MCP/brave-search\" \"MCP/github\"]
    :memory-refs [\"AI-Memory/project-notes\" \"AI-Memory/coding-style\"]
    :skill-refs [\"Skills/summarize\" \"Skills/translate\"]
    :page-refs [\"My Research\" \"Project Plan\"]
    :options {:depth 2 :max-tokens 4000}
    :prompt \"The cleaned prompt text with refs and options removed\"}"
  [content]
  (if (or (nil? content) (str/blank? content))
    {:mcp-refs [] :memory-refs [] :skill-refs [] :page-refs [] :options {} :prompt ""}
    (let [;; Extract MCP, Memory, and Skill refs
          mcp-matches (re-seq mcp-ref-pattern content)
          memory-matches (re-seq memory-ref-pattern content)
          skill-matches (re-seq skill-ref-pattern content)
          mcp-refs (mapv (fn [[_ server-name]] (str "MCP/" server-name)) mcp-matches)
          memory-refs (mapv (fn [[_ tag]] (str "AI-Memory/" tag)) memory-matches)
          skill-refs (mapv (fn [[_ skill-name]] (str "Skills/" skill-name)) skill-matches)
          ;; Strip MCP, Memory, and Skill refs from content
          after-special (-> content
                            (str/replace mcp-ref-pattern "")
                            (str/replace memory-ref-pattern "")
                            (str/replace skill-ref-pattern ""))
          ;; Extract remaining [[Page]] refs (generic page references)
          generic-matches (re-seq generic-ref-pattern after-special)
          page-refs (mapv second generic-matches)
          ;; Extract inline options
          options (parse-inline-options after-special)
          ;; Strip page refs and options from prompt
          cleaned (-> after-special
                      (str/replace generic-ref-pattern "")
                      (str/replace inline-option-pattern "")
                      str/trim
                      (str/replace #"\s{2,}" " "))]
      {:mcp-refs mcp-refs
       :memory-refs memory-refs
       :skill-refs skill-refs
       :page-refs page-refs
       :options options
       :prompt cleaned})))

(defn has-special-refs?
  "Returns true if parsed args contain any MCP, Memory, or Skill references.
   Kept for backward compatibility."
  [{:keys [mcp-refs memory-refs skill-refs]}]
  (boolean (or (seq mcp-refs) (seq memory-refs) (seq skill-refs))))

(defn has-context-refs?
  "Returns true if parsed args contain any MCP, Memory, Skill, or Page references."
  [{:keys [mcp-refs memory-refs skill-refs page-refs]}]
  (boolean (or (seq mcp-refs) (seq memory-refs) (seq skill-refs) (seq page-refs))))
