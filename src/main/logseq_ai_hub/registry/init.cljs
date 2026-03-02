(ns logseq-ai-hub.registry.init
  "Initialization module for the knowledge base registry."
  (:require [logseq-ai-hub.registry.store :as store]
            [logseq-ai-hub.registry.commands :as commands]
            [logseq-ai-hub.registry.scanner :as scanner]
            [logseq-ai-hub.registry.watcher :as watcher]))

(defn init!
  "Initializes the registry module:
  1. Resets the store to a clean state
  2. Registers slash commands
  3. Performs initial scan
  4. Starts DB change watcher"
  []
  (store/init-store!)
  (commands/register-commands!)
  (-> (scanner/refresh-registry!)
      (.then (fn [counts]
               (let [total (reduce + (vals counts))]
                 (js/console.log "Registry initialized:" total "entries found")
                 (watcher/watch-changes!))))
      (.catch (fn [err]
                (js/console.error "Registry init error:" err)
                ;; Still start watcher even if initial scan fails
                (watcher/watch-changes!)))))
