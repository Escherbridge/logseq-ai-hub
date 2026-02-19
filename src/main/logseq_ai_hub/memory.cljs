(ns logseq-ai-hub.memory
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce state
  (atom {:config {:page-prefix "AI-Memory/" :enabled false}
         :index {}})) ;; tag -> list of memory block UUIDs

;; ---------------------------------------------------------------------------
;; Pure Helpers (public for testability)
;; ---------------------------------------------------------------------------

(defn- current-iso-timestamp []
  (.toISOString (js/Date.)))

(defn memory-page-name
  "Returns the Logseq page name for a given memory tag."
  [prefix tag]
  (str prefix tag))

(defn format-memory-block
  "Formats a memory block content string with properties."
  [content tag]
  (str content "\n"
       "stored-at:: " (current-iso-timestamp) "\n"
       "memory-tag:: " tag))

;; ---------------------------------------------------------------------------
;; Storage Functions
;; ---------------------------------------------------------------------------

(defn store-memory!
  "Creates page [[AI-Memory/<tag>]] if needed, appends block with content + timestamp property.
   Returns Promise."
  [tag content]
  (let [{:keys [page-prefix enabled]} (:config @state)]
    (if-not enabled
      (js/Promise.reject (js/Error. "Memory system is not enabled"))
      (let [page-name (memory-page-name page-prefix tag)
            block-content (format-memory-block content tag)]
        (-> (js/logseq.Editor.createPage
              page-name
              #js {}
              #js {:createFirstBlock false :redirect false})
            (.catch (fn [_] nil)) ;; page may already exist
            (.then (fn [_]
                     (js/logseq.Editor.appendBlockInPage page-name block-content))))))))

;; ---------------------------------------------------------------------------
;; Retrieval Functions
;; ---------------------------------------------------------------------------

(defn retrieve-by-tag
  "Gets all blocks from a specific tag page AI-Memory/<tag>.
   Returns Promise resolving to vector of blocks."
  [tag]
  (let [{:keys [page-prefix enabled]} (:config @state)]
    (if-not enabled
      (js/Promise.reject (js/Error. "Memory system is not enabled"))
      (let [page-name (memory-page-name page-prefix tag)]
        (-> (js/logseq.Editor.getPageBlocksTree page-name)
            (.then (fn [blocks]
                     (if blocks
                       (js->clj blocks :keywordize-keys true)
                       [])))
            (.catch (fn [_] [])))))))

(defn retrieve-memories
  "Uses Datalog query to search across memory pages for blocks matching query.
   Returns Promise resolving to vector of block maps."
  [query-str]
  (let [{:keys [page-prefix enabled]} (:config @state)]
    (if-not enabled
      (js/Promise.reject (js/Error. "Memory system is not enabled"))
      (if (str/blank? query-str)
        (js/Promise.resolve [])
        (let [;; Datalog query to find blocks containing the query string
              ;; and belonging to pages starting with the memory prefix
              query (str "[:find (pull ?b [*])
                          :where
                          [?b :block/content ?content]
                          [?b :block/page ?p]
                          [?p :block/name ?page-name]
                          [(clojure.string/includes? ?content \"" query-str "\")]
                          [(clojure.string/starts-with? ?page-name \"" (str/lower-case page-prefix) "\")]]")]
          (-> (js/logseq.DB.datascriptQuery query)
              (.then (fn [results]
                       (if results
                         ;; Results are in format [[block-map] [block-map] ...]
                         ;; Extract first element of each result and convert to ClojureScript
                         (let [converted (js->clj results :keywordize-keys true)]
                           (mapv first converted))
                         [])))
              (.catch (fn [err]
                        (js/console.error "Datalog query error:" err)
                        []))))))))

;; ---------------------------------------------------------------------------
;; Clear Functions
;; ---------------------------------------------------------------------------

(defn clear-memories!
  "Deletes all memory pages under the prefix. Returns Promise."
  []
  (let [{:keys [page-prefix enabled]} (:config @state)]
    (if-not enabled
      (js/Promise.reject (js/Error. "Memory system is not enabled"))
      ;; Query for all pages starting with the prefix
      (let [query (str "[:find (pull ?p [:block/name])
                        :where
                        [?p :block/name ?name]
                        [(clojure.string/starts-with? ?name \"" (str/lower-case page-prefix) "\")]]")]
        (-> (js/logseq.DB.datascriptQuery query)
            (.then (fn [results]
                     (if results
                       (let [converted (js->clj results :keywordize-keys true)
                             page-names (mapv (fn [result]
                                                (get (first result) :block/name))
                                              converted)]
                         ;; Delete each page
                         (js/Promise.all
                           (clj->js (mapv (fn [page-name]
                                            (js/logseq.Editor.deletePage page-name))
                                          page-names))))
                       (js/Promise.resolve []))))
            (.then (fn [_]
                     (swap! state assoc :index {})
                     nil)))))))

;; ---------------------------------------------------------------------------
;; Slash Commands
;; ---------------------------------------------------------------------------

(defn- handle-store-command [e]
  (let [block-uuid (.-uuid e)]
    (-> (js/logseq.Editor.getBlock block-uuid)
        (.then (fn [block]
                 (let [content (.-content block)]
                   ;; Extract tag and content from user input
                   ;; Expected format: "tag: content"
                   (if-let [[_ tag memory-content] (re-matches #"(?i)^(\S+):\s*(.+)$" (str/trim content))]
                     (-> (store-memory! tag memory-content)
                         (.then (fn [_]
                                  (js/logseq.App.showMsg "Memory stored successfully!" :success)))
                         (.catch (fn [err]
                                   (js/logseq.App.showMsg (str "Error: " (.-message err)) :error))))
                     (js/logseq.App.showMsg "Format: tag: content" :warning)))))
        (.catch (fn [err]
                  (js/console.error "Store command error:" err))))))

(defn- handle-recall-command [e]
  (let [block-uuid (.-uuid e)]
    (-> (js/logseq.Editor.getBlock block-uuid)
        (.then (fn [block]
                 (let [query (.-content block)]
                   (-> (retrieve-memories query)
                       (.then (fn [results]
                                (if (seq results)
                                  (let [summary (str "Found " (count results) " memories:\n"
                                                   (str/join "\n" (map #(str "- " (:block/content %)) results)))]
                                    (js/logseq.Editor.insertBlock block-uuid summary))
                                  (js/logseq.App.showMsg "No memories found" :info))))
                       (.catch (fn [err]
                                 (js/logseq.App.showMsg (str "Error: " (.-message err)) :error)))))))
        (.catch (fn [err]
                  (js/console.error "Recall command error:" err))))))

(defn register-commands!
  "Registers /ai-memory:store and /ai-memory:recall slash commands in Logseq."
  []
  (js/logseq.Editor.registerSlashCommand "ai-memory:store" handle-store-command)
  (js/logseq.Editor.registerSlashCommand "ai-memory:recall" handle-recall-command))

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init!
  "Initializes the memory module. Reads settings and scans existing memory pages to build index."
  []
  (let [settings js/logseq.settings
        enabled (aget settings "memoryEnabled")
        page-prefix (aget settings "memoryPagePrefix")]
    (swap! state assoc-in [:config :enabled] (boolean enabled))
    (when page-prefix
      (swap! state assoc-in [:config :page-prefix] page-prefix))

    (when enabled
      (register-commands!)
      (js/console.log "Memory module initialized with prefix:" (:page-prefix (:config @state))))))
