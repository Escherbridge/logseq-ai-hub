(ns logseq-ai-hub.llm.graph-context
  "Resolves generic [[Page]] references into LLM system prompt context.
   Fetches page block trees, optionally following nested links, with token budgets."
  (:require [clojure.string :as str]))

(def ^:private default-depth 0)
(def ^:private default-max-tokens 8000)
(def ^:private chars-per-token 4)

(defn- estimate-tokens [text]
  (js/Math.ceil (/ (count text) chars-per-token)))

(defn- extract-page-links
  "Extracts all [[Page Name]] references from a text string.
   Returns a vector of page name strings."
  [text]
  (let [matches (re-seq #"\[\[([^\]]+)\]\]" text)]
    (mapv second matches)))

(defn- flatten-blocks
  "Recursively flattens a block tree into indented bullet lines.
   blocks should already be clj maps with :content and :children."
  ([blocks] (flatten-blocks blocks 0))
  ([blocks indent-level]
   (let [prefix (apply str (repeat indent-level "  "))]
     (mapcat (fn [block]
               (let [content (or (:content block) "")
                     line (str prefix "- " (str/trim content))
                     children (:children block)]
                 (if (seq children)
                   (cons line (flatten-blocks children (inc indent-level)))
                   [line])))
             blocks))))

(defn- fetch-page-content
  "Fetches all blocks from a Logseq page and returns formatted content.
   Returns Promise<{:content string :links [page-names-found]}>."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.then (fn [blocks]
               (if (and blocks (pos? (.-length blocks)))
                 (let [block-arr (js->clj blocks :keywordize-keys true)
                       lines (flatten-blocks block-arr)
                       content (str/join "\n" lines)
                       links (extract-page-links content)]
                   {:content content :links links})
                 {:content "" :links []})))
      (.catch (fn [err]
                (js/console.warn "Could not fetch page:" page-name (.-message err))
                {:content "" :links []}))))

(defn- get-default-depth []
  (let [v (aget js/logseq.settings "pageRefDepth")]
    (if (and (some? v) (pos? v)) v default-depth)))

(defn- get-default-max-tokens []
  (let [v (aget js/logseq.settings "pageRefMaxTokens")]
    (if (and (some? v) (pos? v)) v default-max-tokens)))

(defn- resolve-page-refs-bfs
  "BFS traversal across page links.

   Parameters:
   - initial-pages: vector of page names to start from
   - max-depth: how many levels of links to follow (0 = only fetch initial pages)
   - max-tokens: token budget for total accumulated content

   Returns Promise<vector of {:page-name :content}> in BFS order."
  [initial-pages max-depth max-tokens]
  (let [visited (atom #{})
        results (atom [])
        token-count (atom 0)
        queue (atom (mapv (fn [p] {:page-name p :depth 0}) initial-pages))]
    (letfn [(process-next []
              (if (or (empty? @queue)
                      (> @token-count max-tokens))
                (js/Promise.resolve @results)
                (let [{:keys [page-name depth]} (first @queue)]
                  (swap! queue #(vec (rest %)))
                  (if (contains? @visited (str/lower-case page-name))
                    (process-next)
                    (do
                      (swap! visited conj (str/lower-case page-name))
                      (-> (fetch-page-content page-name)
                          (.then (fn [{:keys [content links]}]
                                   (when (and (not (str/blank? content))
                                              (<= (+ @token-count (estimate-tokens content))
                                                  max-tokens))
                                     (swap! results conj {:page-name page-name
                                                          :content content})
                                     (swap! token-count + (estimate-tokens content))
                                     ;; Enqueue child links if under max-depth
                                     (when (< depth max-depth)
                                       (let [new-items (->> links
                                                            (remove #(contains? @visited (str/lower-case %)))
                                                            (remove #(or (str/starts-with? % "MCP/")
                                                                         (str/starts-with? % "AI-Memory/")))
                                                            (mapv (fn [l] {:page-name l :depth (inc depth)})))]
                                         (swap! queue into new-items))))
                                   (process-next)))
                          (.catch (fn [_] (process-next)))))))))]
      (process-next))))

(defn resolve-page-refs
  "Fetches page content for all referenced pages with BFS link traversal.

   page-refs: vector of page names like [\"My Research\" \"Project Plan\"]
   options: map with optional :depth and :max-tokens overrides

   Returns Promise<string|nil> with formatted context:
     ## Context from referenced pages:
     ### My Research
     - Block content here...
     ### Project Plan
     - More content...

   Returns nil if no page refs provided or all empty."
  [page-refs options]
  (if (empty? page-refs)
    (js/Promise.resolve nil)
    (let [depth (or (:depth options) (get-default-depth))
          max-tokens (or (:max-tokens options) (get-default-max-tokens))]
      (-> (resolve-page-refs-bfs page-refs depth max-tokens)
          (.then (fn [results]
                   (when (seq results)
                     (let [sections (map (fn [{:keys [page-name content]}]
                                           (str "### " page-name "\n" content))
                                         results)]
                       (str "## Context from referenced pages:\n\n"
                            (str/join "\n\n" sections))))))))))
