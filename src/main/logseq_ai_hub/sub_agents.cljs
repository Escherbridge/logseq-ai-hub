(ns logseq-ai-hub.sub-agents
  (:require [clojure.string :as str]
            [logseq-ai-hub.agent :as agent]
            [logseq-ai-hub.llm.enriched :as enriched]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce state
  (atom {:agents {}                ;; slug -> {:page-name "..." :original-name "..."}
         :registered-commands #{}})) ;; set of slugs already registered

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn slugify
  "Converts an agent name to a valid slash command suffix.
   'Meeting Facilitator' -> 'meeting-facilitator'"
  [name]
  (-> name
      str/trim
      str/lower-case
      (str/replace #"[^a-z0-9\s-]" "")
      (str/replace #"\s+" "-")
      (str/replace #"-+" "-")
      (str/replace #"^-|-$" "")))

(defn get-agent-system-prompt
  "Reads the agent page's block tree and concatenates all top-level block
   content to form the system prompt. Filters out lines that are just the
   tags:: property. Returns Promise<string>."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.then (fn [blocks]
               (if (and blocks (pos? (.-length blocks)))
                 (let [converted (js->clj blocks :keywordize-keys true)]
                   (->> converted
                        (map :content)
                        (remove str/blank?)
                        ;; Filter out lines that are just tags:: logseq-ai-hub-agent
                        (map (fn [content]
                               (->> (str/split-lines content)
                                    (remove #(re-matches #"^\s*tags::\s*logseq-ai-hub-agent\s*$" %))
                                    (str/join "\n"))))
                        (remove str/blank?)
                        (str/join "\n\n")))
                 "")))
      (.catch (fn [err]
                (js/console.error "Error reading agent page:" page-name err)
                ""))))

;; ---------------------------------------------------------------------------
;; Agent Scanning
;; ---------------------------------------------------------------------------

(defn scan-agent-pages!
  "Queries Logseq for all pages that contain 'logseq-ai-hub-agent' tag.
   Returns Promise resolving to a vector of {:page-name :original-name} maps."
  []
  ;; Use content-based query for broader compatibility across Logseq versions
  (let [query "[:find (pull ?p [:block/name :block/original-name])
                :where
                [?b :block/page ?p]
                [?b :block/content ?c]
                [(clojure.string/includes? ?c \"logseq-ai-hub-agent\")]]"]
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
                  (js/console.error "Agent scan error:" err)
                  [])))))

;; ---------------------------------------------------------------------------
;; Command Handling
;; ---------------------------------------------------------------------------

(defn- make-agent-command-handler
  "Creates a slash command handler for a specific agent slug.
   The handler reads the agent's page content live, uses it as system prompt,
   reads the current block as user input, and inserts the response."
  [agent-slug]
  (fn [e]
    (let [block-uuid (.-uuid e)
          agent-info (get (:agents @state) agent-slug)]
      (if-not agent-info
        (do (js/logseq.App.showMsg
              (str "Agent '" agent-slug "' not found. Run /refresh-agents.") :error)
            (js/Promise.resolve nil))
        (-> (js/logseq.Editor.getBlock block-uuid)
            (.then (fn [block]
                     (let [user-input (.-content block)
                           page-name (:page-name agent-info)]
                       (js/console.log "Sub-agent invoked:" agent-slug "page:" page-name)
                       (-> (get-agent-system-prompt page-name)
                           (.then (fn [system-prompt]
                                    (if (str/blank? system-prompt)
                                      (js/Promise.reject
                                        (js/Error.
                                          (str "Agent page '" page-name
                                               "' has no content for system prompt.")))
                                      (do
                                        (js/console.log "System prompt length:" (count system-prompt))
                                        (enriched/call
                                          user-input
                                          :extra-system-prompt system-prompt)))))))))
            (.then (fn [response]
                     (if (and response (not= response ""))
                       (js/logseq.Editor.insertBlock block-uuid response)
                       (js/logseq.Editor.insertBlock block-uuid
                         "Error: Empty response from agent."))))
            (.catch (fn [err]
                      (js/console.error "Agent command error:" err)
                      (js/logseq.Editor.insertBlock block-uuid
                        (str "Error: " (.-message err))))))))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn register-agent-command!
  "Registers a slash command for a single agent, if not already registered.
   Returns true if a new command was registered, false if it already existed."
  [agent-slug agent-info]
  (if (contains? (:registered-commands @state) agent-slug)
    false
    (do
      (swap! state assoc-in [:agents agent-slug] agent-info)
      (swap! state update :registered-commands conj agent-slug)
      (js/logseq.Editor.registerSlashCommand
        (str "llm-" agent-slug)
        (make-agent-command-handler agent-slug))
      (js/console.log "Registered agent command: llm-" agent-slug)
      true)))

(defn refresh-agents!
  "Scans for agent pages and registers slash commands for any new agents found.
   Returns Promise resolving to the count of newly registered agents."
  []
  (-> (scan-agent-pages!)
      (.then (fn [agent-pages]
               (let [new-count (atom 0)]
                 (doseq [{:keys [page-name original-name]} agent-pages]
                   (let [display-name (last (str/split original-name #"/"))
                         slug (slugify display-name)
                         agent-info {:page-name page-name
                                     :original-name original-name}]
                     (when (and (not (str/blank? slug))
                                (register-agent-command! slug agent-info))
                       (swap! new-count inc))))
                 @new-count)))
      (.then (fn [n]
               (when (pos? n)
                 (js/logseq.App.showMsg
                   (str n " new agent(s) registered.") :success))
               n))))

;; ---------------------------------------------------------------------------
;; Slash Commands
;; ---------------------------------------------------------------------------

(defn handle-new-agent-command
  "Slash command handler for creating a new agent.
   Block text = agent name. Creates a page with default system prompt
   and tags:: logseq-ai-hub-agent, registers the slash command."
  [e]
  (let [block-uuid (.-uuid e)]
    (-> (js/logseq.Editor.getBlock block-uuid)
        (.then (fn [block]
                 (let [agent-name (str/trim (.-content block))]
                   (if (str/blank? agent-name)
                     (js/logseq.App.showMsg
                       "Write the agent name in the block, then invoke /new-agent"
                       :warning)
                     (let [slug (slugify agent-name)
                           default-prompt (str "You are " agent-name
                                               ", an AI assistant. Edit this page to customize your system prompt.\ntags:: logseq-ai-hub-agent")]
                       (-> (js/logseq.Editor.createPage
                             agent-name
                             #js {}
                             #js {:createFirstBlock false :redirect false})
                           (.catch (fn [_] nil)) ;; page may already exist
                           (.then (fn [_]
                                    (js/logseq.Editor.appendBlockInPage
                                      agent-name default-prompt)))
                           (.then (fn [_]
                                    (let [agent-info {:page-name (str/lower-case agent-name)
                                                      :original-name agent-name}]
                                      (register-agent-command! slug agent-info)
                                      (js/logseq.App.showMsg
                                        (str "Agent '" agent-name "' created! Use /llm-" slug)
                                        :success))))
                           (.catch (fn [err]
                                     (js/console.error "New agent error:" err)
                                     (js/logseq.App.showMsg
                                       (str "Error: " (.-message err)) :error)))))))))
        (.catch (fn [err]
                  (js/console.error "New agent command error:" err))))))

(defn register-commands!
  "Registers static slash commands: /new-agent and /refresh-agents."
  []
  (js/logseq.Editor.registerSlashCommand "new-agent" handle-new-agent-command)
  (js/logseq.Editor.registerSlashCommand "refresh-agents"
    (fn [_e] (refresh-agents!))))

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init!
  "Initializes the sub-agents module. Registers static commands and
   scans for existing agent pages to register their dynamic commands."
  []
  (register-commands!)
  (-> (refresh-agents!)
      (.then (fn [_]
               (js/console.log "Sub-agents module initialized")))
      (.catch (fn [err]
                (js/console.error "Sub-agents init error:" err)))))
