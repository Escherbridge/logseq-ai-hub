(ns logseq-ai-hub.llm.memory-context
  "Resolves AI-Memory page references into LLM system prompt context.
   Fetches memory blocks and formats them as context for the LLM."
  (:require [logseq-ai-hub.memory :as memory]
            [clojure.string :as str]))

(def ^:private memory-prefix "AI-Memory/")

(defn- extract-tag
  "Extracts the tag from an AI-Memory page name.
   'AI-Memory/project-notes' -> 'project-notes'"
  [page-name]
  (when (str/starts-with? page-name memory-prefix)
    (subs page-name (count memory-prefix))))

(defn- format-memory-blocks
  "Formats memory blocks for a single tag into readable text."
  [tag blocks]
  (if (empty? blocks)
    (str "### " tag "\n(no memories found)")
    (let [block-texts (map (fn [b]
                             (let [content (or (:content b) "")]
                               (str "- " (str/trim content))))
                           blocks)]
      (str "### " tag "\n" (str/join "\n" block-texts)))))

(defn resolve-memory-refs
  "Fetches memory content for all referenced memory pages.

   memory-refs is a vector of page names like [\"AI-Memory/project-notes\" \"AI-Memory/coding\"].

   Returns Promise<string|nil> with formatted context:
     ## Context from your memories:
     ### project-notes
     - Memory block 1...
     ### coding
     - Memory block 2...

   Returns nil if no memory refs provided or all empty."
  [memory-refs]
  (if (empty? memory-refs)
    (js/Promise.resolve nil)
    (let [tags (keep extract-tag memory-refs)]
      (cond
        (empty? tags)
        (js/Promise.resolve nil)

        (not (get-in @memory/state [:config :enabled]))
        (do (js/console.warn "AI-Memory refs used but memory system is disabled. Enable it in plugin settings.")
            (js/Promise.resolve nil))

        :else
        (-> (js/Promise.all
              (clj->js
                (mapv (fn [tag]
                        (-> (memory/retrieve-by-tag tag)
                            (.then (fn [blocks]
                                     {:tag tag :blocks blocks}))
                            (.catch (fn [_]
                                      {:tag tag :blocks []}))))
                      tags)))
            (.then (fn [results]
                     (let [tag-sections (js->clj results :keywordize-keys true)
                           formatted (map (fn [{:keys [tag blocks]}]
                                            (format-memory-blocks
                                              (or tag "")
                                              (or blocks [])))
                                          tag-sections)
                           non-empty (filter #(not (str/includes? % "(no memories found)")) formatted)]
                       (when (seq non-empty)
                         (str "## Context from your memories:\n\n"
                              (str/join "\n\n" formatted)))))))))))
