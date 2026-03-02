(ns logseq-ai-hub.registry.watcher
  "Watches for Logseq DB changes and triggers registry refresh with debounce."
  (:require [logseq-ai-hub.registry.scanner :as scanner]))

;; =============================================================================
;; State
;; =============================================================================

(defonce watcher-state
  (atom {:timer-id nil
         :watching false}))

;; Debounce interval in milliseconds
(def ^:const debounce-ms 2000)

;; =============================================================================
;; Debounced Refresh
;; =============================================================================

(defn- debounced-refresh!
  "Triggers a registry refresh after a debounce period.
  Cancels any pending refresh if called again within the window."
  []
  (let [{:keys [timer-id]} @watcher-state]
    ;; Cancel existing timer
    (when timer-id
      (js/clearTimeout timer-id))
    ;; Set new timer
    (let [new-timer (js/setTimeout
                      (fn []
                        (swap! watcher-state assoc :timer-id nil)
                        (-> (scanner/refresh-registry!)
                            (.then (fn [counts]
                                     (let [total (reduce + (vals counts))]
                                       (when (pos? total)
                                         (js/console.log "Registry auto-refreshed:" total "entries")))))
                            (.catch (fn [err]
                                      (js/console.error "Registry auto-refresh error:" err)))))
                      debounce-ms)]
      (swap! watcher-state assoc :timer-id new-timer))))

;; =============================================================================
;; Watcher Lifecycle
;; =============================================================================

(defn watch-changes!
  "Registers a Logseq DB change listener that triggers debounced registry refresh.
  Only responds to changes that might affect registry pages (block content changes)."
  []
  (when-not (:watching @watcher-state)
    (js/logseq.DB.onChanged
      (fn [e]
        ;; Only refresh on block changes, not on every DB event
        (let [converted (js->clj e :keywordize-keys true)
              blocks (:blocks converted)]
          (when (and blocks (pos? (count blocks)))
            (debounced-refresh!)))))
    (swap! watcher-state assoc :watching true)
    (js/console.log "Registry DB watcher started")))

(defn stop-watching!
  "Stops the DB change watcher and cancels any pending refresh."
  []
  (when-let [timer-id (:timer-id @watcher-state)]
    (js/clearTimeout timer-id))
  (swap! watcher-state assoc :timer-id nil :watching false)
  (js/console.log "Registry DB watcher stopped"))
