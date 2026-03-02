(ns logseq-ai-hub.registry.commands
  "Slash command handlers for registry management."
  (:require [logseq-ai-hub.registry.scanner :as scanner]
            [logseq-ai-hub.registry.store :as store]))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn- show-msg
  "Shows a message to the user."
  [msg & {:keys [status] :or {status "success"}}]
  (js/logseq.App.showMsg msg status))

(defn- insert-child-block
  "Inserts a child block under the given parent UUID."
  [parent-uuid content]
  (js/logseq.Editor.insertBlock parent-uuid content
    #js {:sibling false}))

;; =============================================================================
;; Command Handlers
;; =============================================================================

(defn handle-registry-refresh
  "Handler for /registry:refresh slash command.
  Triggers a full registry rescan and shows results."
  [e]
  (let [block-uuid (.-uuid e)]
    (show-msg "Scanning registry..." :status "info")
    (-> (scanner/refresh-registry!)
        (.then (fn [counts]
                 (let [total (reduce + (vals counts))
                       msg (str "Registry refreshed: " total " entries found"
                                "\n  Tools: " (:tools counts 0)
                                ", Skills: " (:skills counts 0)
                                ", Prompts: " (:prompts counts 0)
                                ", Agents: " (:agents counts 0)
                                ", Procedures: " (:procedures counts 0))]
                   (show-msg msg :status "success"))))
        (.catch (fn [err]
                  (js/console.error "registry:refresh error:" err)
                  (show-msg (str "Error: " (.-message err))
                           :status "error"))))))

(defn handle-registry-list
  "Handler for /registry:list slash command.
  Lists all registered entries as a child block."
  [e]
  (let [block-uuid (.-uuid e)
        snapshot (store/get-snapshot)]
    (try
      (let [all-entries (store/list-entries)
            entry-text (if (empty? all-entries)
                         "Registry is empty. Use /registry:refresh to scan."
                         (str "Registry (v" (:version snapshot) ", "
                              (count all-entries) " entries):\n"
                              (apply str
                                (map (fn [entry]
                                       (str "- [" (name (:type entry)) "] "
                                            (:name entry)
                                            ": " (:description entry) "\n"))
                                     all-entries))))]
        (insert-child-block block-uuid entry-text))
      (catch js/Error err
        (js/console.error "registry:list error:" err)
        (show-msg (str "Error: " (.-message err))
                 :status "error")))))

;; =============================================================================
;; Command Registration
;; =============================================================================

(def command-registry
  "Map of slash command names to handler functions."
  {"registry:refresh" handle-registry-refresh
   "registry:list" handle-registry-list})

(defn register-commands!
  "Registers all registry slash commands with Logseq."
  []
  (doseq [[cmd-name handler] command-registry]
    (js/logseq.Editor.registerSlashCommand cmd-name handler)
    (js/console.log "Registered slash command:" cmd-name)))
