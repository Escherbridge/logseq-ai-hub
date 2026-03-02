(ns logseq-ai-hub.registry.scanner
  "Scans the Logseq graph for tagged pages and populates the registry store.
   Delegates to existing scanners for skills and agents."
  (:require [clojure.string :as str]
            [logseq-ai-hub.registry.store :as store]
            [logseq-ai-hub.registry.parser :as parser]
            [logseq-ai-hub.job-runner.parser :as block-parser]))

;; Tag conventions for each registry type
(def registry-tags
  {:tool "logseq-ai-hub-tool"
   :prompt "logseq-ai-hub-prompt"
   :procedure "logseq-ai-hub-procedure"})

(defn scan-tagged-pages!
  "Queries Logseq for all pages containing the given tag string in block content.
   Returns Promise<vector of {:page-name :original-name}>.
   Matches the sub_agents.cljs pattern for broad compatibility."
  [tag]
  (let [query (str "[:find (pull ?p [:block/name :block/original-name]) "
                   ":where [?b :block/page ?p] "
                   "[?b :block/content ?c] "
                   "[(clojure.string/includes? ?c \"" tag "\")]]")]
    (-> (js/logseq.DB.datascriptQuery query)
        (.then (fn [results]
                 (if (and results (pos? (.-length results)))
                   (let [converted (js->clj results :keywordize-keys true)]
                     (mapv (fn [r]
                             (let [page (first r)]
                               {:page-name (:block/name page)
                                :original-name (or (:block/original-name page)
                                                   (:block/name page))}))
                           converted))
                   [])))
        (.catch (fn [err]
                  (js/console.warn "Registry scan error for tag" tag ":" err)
                  [])))))

(defn- read-page-first-block
  "Reads a page's block tree and returns the first block's content string.
   Returns Promise<string|nil>."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.then (fn [blocks]
               (when (and blocks (pos? (.-length blocks)))
                 (let [first-block (aget blocks 0)]
                   (.-content first-block)))))
      (.catch (fn [err]
                (js/console.warn "Error reading page:" page-name err)
                nil))))

(defn- scan-and-parse-type!
  "Scans for pages with the given tag, reads each page, and parses them.
   Returns Promise<vector of parsed entries>."
  [tag parse-fn]
  (-> (scan-tagged-pages! tag)
      (.then (fn [pages]
               (js/Promise.all
                 (clj->js
                   (for [{:keys [page-name original-name]} pages]
                     (-> (read-page-first-block (or original-name page-name))
                         (.then (fn [content]
                                  (when content
                                    (let [result (parse-fn (or original-name page-name) content)]
                                      (when (:valid result)
                                        (:entry result))))))
                         (.catch (fn [err]
                                   (js/console.warn "Failed to parse page:" original-name err)
                                   nil))))))))
      (.then (fn [results]
               (vec (filter some? (js->clj results)))))))

(def ^:dynamic *scan-skill-pages-fn*
  "Dynamic var for skill scanning. Override for testing."
  nil)

(def ^:dynamic *scan-agent-pages-fn*
  "Dynamic var for agent scanning. Override for testing."
  nil)

(defn refresh-registry!
  "Full registry refresh: scans all tag categories, skills, and agents.
   Clears existing entries, re-populates the store, bumps version.
   Returns Promise<{:tools N :skills N :prompts N :agents N :procedures N}>."
  []
  (let [skill-prefix (or (aget js/logseq "settings" "skillPagePrefix") "Skills/")]
    (-> (js/Promise.all
          (clj->js
            [(scan-and-parse-type! (:tool registry-tags) parser/parse-tool-page)
             (scan-and-parse-type! (:prompt registry-tags) parser/parse-prompt-page)
             (scan-and-parse-type! (:procedure registry-tags) parser/parse-procedure-page)
             ;; Skills: delegate to existing scanner or dynamic var
             (if *scan-skill-pages-fn*
               (*scan-skill-pages-fn* skill-prefix)
               (js/Promise.resolve []))
             ;; Agents: delegate to existing scanner or dynamic var
             (if *scan-agent-pages-fn*
               (*scan-agent-pages-fn*)
               (js/Promise.resolve []))]))
        (.then (fn [results]
                 (let [[tools prompts procedures skills agents]
                       (js->clj results :keywordize-keys true)]
                   ;; Clear and repopulate
                   (store/clear-category! :tool)
                   (store/clear-category! :prompt)
                   (store/clear-category! :procedure)
                   (store/clear-category! :skill)
                   (store/clear-category! :agent)

                   ;; Add tools from tagged pages
                   (doseq [entry tools]
                     (store/add-entry entry))

                   ;; Add prompts
                   (doseq [entry prompts]
                     (store/add-entry entry))

                   ;; Add procedures
                   (doseq [entry procedures]
                     (store/add-entry entry))

                   ;; Add skills and also wrap as tool entries
                   (doseq [skill-def skills]
                     (when (:valid skill-def)
                       (store/add-entry {:id (:skill-id skill-def)
                                         :type :skill
                                         :name (last (str/split (:skill-id skill-def) #"/"))
                                         :description (get-in skill-def [:properties :skill-description] "")
                                         :properties (:properties skill-def)
                                         :source :auto-detected})
                       ;; Also wrap as tool
                       (store/add-entry (parser/parse-skill-as-tool skill-def))))

                   ;; Add agents
                   (doseq [agent-info agents]
                     (store/add-entry {:id (or (:page-name agent-info) (:id agent-info))
                                       :type :agent
                                       :name (or (:original-name agent-info) (:name agent-info) "")
                                       :description ""
                                       :properties {}
                                       :source :auto-detected}))

                   (store/bump-version!)

                   (let [counts {:tools (count (store/list-entries :tool))
                                 :skills (count (store/list-entries :skill))
                                 :prompts (count (store/list-entries :prompt))
                                 :agents (count (store/list-entries :agent))
                                 :procedures (count (store/list-entries :procedure))}]
                     (js/console.log "Registry:" (pr-str counts))
                     counts)))))))
