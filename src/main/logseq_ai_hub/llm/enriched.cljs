(ns logseq-ai-hub.llm.enriched
  "Universal enriched LLM call. Parses [[Page]], [[MCP/...]], [[AI-Memory/...]] refs
   from content, resolves context, and calls the LLM. Any plugin-side command can use this."
  (:require [logseq-ai-hub.llm.arg-parser :as arg-parser]
            [logseq-ai-hub.llm.graph-context :as graph-context]
            [logseq-ai-hub.llm.memory-context :as memory-context]
            [logseq-ai-hub.llm.tool-use :as tool-use]
            [logseq-ai-hub.mcp.on-demand :as on-demand]
            [logseq-ai-hub.registry.store :as registry-store]
            [logseq-ai-hub.agent :as agent]
            [clojure.string :as str]))

(defn- merge-system-prompts
  "Merges multiple context sections into one system prompt string.
   Filters out nil sections."
  [& sections]
  (let [non-nil (filter some? sections)]
    (when (seq non-nil)
      (str/join "\n\n" non-nil))))

(defn- resolve-skill-tools
  "Looks up skill refs in the registry store and returns tool definitions
   in the server-tools format expected by tool-use/handle-tool-use-request.
   Returns [{:server-id \"skill\" :tools [{:name :description :inputSchema}]}]."
  [skill-refs]
  (when (seq skill-refs)
    (let [tools (reduce
                  (fn [acc skill-ref]
                    (let [entry (registry-store/get-entry :skill skill-ref)]
                      (if entry
                        (conj acc {:name (:name entry)
                                   :description (or (:description entry)
                                                    (str "Execute skill: " (:name entry)))
                                   :inputSchema (or (:input-schema entry)
                                                    {:type "object"
                                                     :properties {"input" {:type "string"
                                                                          :description "Input for the skill"}}})})
                        acc)))
                  []
                  skill-refs)]
      (when (seq tools)
        [{:server-id "skill" :tools tools}]))))

(defn call
  "Universal enriched LLM entry point.
   Takes raw block content, parses refs, resolves all context, calls LLM.
   Returns Promise<string> with the LLM response.

   Options (keyword args):
   - :model-id  — override model (default: from settings 'selectedModel')
   - :extra-system-prompt — additional system prompt text to prepend"
  [content & {:keys [model-id extra-system-prompt]}]
  (let [{:keys [mcp-refs memory-refs skill-refs page-refs options prompt]}
          (arg-parser/parse-llm-args content)
        effective-model (or model-id "llm-model")]
    (if-not (or (arg-parser/has-context-refs?
                  {:mcp-refs mcp-refs :memory-refs memory-refs
                   :skill-refs skill-refs :page-refs page-refs})
                extra-system-prompt)
      ;; Simple path: no refs and no extra system prompt
      (agent/process-input prompt effective-model)
      ;; Enriched path: resolve context + optional MCP/skill tools
      (let [connected-ids (atom nil)
            skill-tools (resolve-skill-tools skill-refs)]
        (-> (js/Promise.all
              #js [(memory-context/resolve-memory-refs memory-refs)
                   (graph-context/resolve-page-refs page-refs options)
                   (if (seq mcp-refs)
                     (-> (on-demand/connect-servers-from-refs! mcp-refs)
                         (.then (fn [ids]
                                  (reset! connected-ids ids)
                                  (on-demand/collect-tools ids))))
                     (js/Promise.resolve []))])
            (.then (fn [results]
                     (let [memory-prompt (aget results 0)
                           page-prompt   (aget results 1)
                           mcp-tools     (js->clj (aget results 2))
                           ;; Merge MCP tools with skill tools
                           all-tools     (into (vec mcp-tools)
                                               (or skill-tools []))
                           system-prompt (apply merge-system-prompts
                                           (filter some? [extra-system-prompt
                                                          memory-prompt
                                                          page-prompt]))]
                       (if (seq all-tools)
                         (tool-use/handle-tool-use-request prompt all-tools system-prompt)
                         (if system-prompt
                           (agent/process-with-system-prompt prompt system-prompt)
                           (agent/process-input prompt effective-model))))))
            (.finally (fn []
                        (when (seq @connected-ids)
                          (on-demand/disconnect-servers! @connected-ids)))))))))
