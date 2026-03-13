(ns logseq-ai-hub.event-hub.graph-watcher
  "Watches for Logseq graph changes and emits events to the Event Hub.
   Debounces per-page to avoid flooding the event bus with rapid edits."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Dynamic vars
;; ---------------------------------------------------------------------------

(def ^:dynamic *publish-fn*
  "Function to publish an event to the server's EventBus.
   Signature: (fn [{:keys [type source data metadata]}] Promise).
   Set during system init in event_hub/init.cljs."
  nil)

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce debounce-timers
  (atom {}))  ;; page-name -> timer-id

(defonce watcher-state
  (atom {:watching false}))

;; Debounce interval in milliseconds
(def ^:const debounce-ms 2000)

;; ---------------------------------------------------------------------------
;; Filtering
;; ---------------------------------------------------------------------------

(defn should-track?
  "Returns true if the page should be tracked for change events.
   Returns false for Events/* and Jobs/* pages to avoid infinite loops."
  [page-name]
  (when (and page-name (not (str/blank? page-name)))
    (not (or (str/starts-with? page-name "Events/")
             (str/starts-with? page-name "events/")
             (str/starts-with? page-name "Jobs/")
             (str/starts-with? page-name "jobs/")))))

;; ---------------------------------------------------------------------------
;; Debounced Emission
;; ---------------------------------------------------------------------------

(defn- debounced-emit!
  "Emits a graph change event after a debounce window.
   Cancels any pending emission for the same page."
  [page-name event-type data]
  (when *publish-fn*
    ;; Cancel existing timer for this page
    (when-let [existing-timer (get @debounce-timers page-name)]
      (js/clearTimeout existing-timer))
    ;; Set new timer
    (let [timer (js/setTimeout
                  (fn []
                    (swap! debounce-timers dissoc page-name)
                    (*publish-fn* {:type event-type
                                   :source "system:graph"
                                   :data data}))
                  debounce-ms)]
      (swap! debounce-timers assoc page-name timer))))

;; ---------------------------------------------------------------------------
;; Change Handler
;; ---------------------------------------------------------------------------

(defn- extract-page-names
  "Extracts unique page names from a DB change event.
   The change event contains :blocks, each of which may have a :page with :name."
  [changes]
  (let [blocks (or (:blocks changes) [])]
    (->> blocks
         (keep (fn [block]
                 (or (get-in block [:page :name])
                     (get-in block [:page :originalName])
                     (:page block))))
         (filter string?)
         distinct)))

(defn handle-change
  "Processes a Logseq DB change event.
   Extracts affected page names and emits debounced graph.page.updated events."
  [changes]
  (let [converted (if (map? changes)
                    changes
                    (js->clj changes :keywordize-keys true))
        page-names (extract-page-names converted)]
    (doseq [page-name page-names]
      (when (should-track? page-name)
        (debounced-emit! page-name "graph.page.updated"
                         {:page-name page-name
                          :change-type "modified"})))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Registers a Logseq DB.onChanged listener for graph change events.
   Safe to call multiple times (only registers once)."
  []
  (when-not (:watching @watcher-state)
    (when (and js/logseq (.-DB js/logseq) (.-onChanged (.-DB js/logseq)))
      (js/logseq.DB.onChanged
        (fn [e]
          (handle-change e)))
      (swap! watcher-state assoc :watching true)
      (js/console.log "[EventHub] Graph watcher started"))))

(defn stop!
  "Stops the graph watcher and clears all pending debounce timers."
  []
  ;; Clear all pending timers
  (doseq [[_ timer-id] @debounce-timers]
    (js/clearTimeout timer-id))
  (reset! debounce-timers {})
  (swap! watcher-state assoc :watching false)
  (js/console.log "[EventHub] Graph watcher stopped"))
