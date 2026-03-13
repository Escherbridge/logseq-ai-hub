(ns logseq-ai-hub.event-hub.commands
  "Slash commands for the Event Hub system.
   Provides event:recent, event:sources, event:test, and event:list commands."
  (:require [logseq-ai-hub.registry.store :as store]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Dynamic vars for dependency injection
;; ---------------------------------------------------------------------------

(def ^:dynamic *fetch-recent-fn*
  "Function to fetch recent events. (fn [] -> Promise<[event-map ...]>)"
  nil)

(def ^:dynamic *fetch-sources-fn*
  "Function to fetch active event sources. (fn [] -> Promise<[source-string ...]>)"
  nil)

(def ^:dynamic *publish-fn*
  "Function to publish an event. (fn [event-map] -> Promise)"
  nil)

;; ---------------------------------------------------------------------------
;; Formatting Helpers
;; ---------------------------------------------------------------------------

(defn- format-recent-events
  "Formats a sequence of event maps into a Logseq table string."
  [events]
  (if (empty? events)
    "No recent events"
    (str "| Type | Source | Severity | Time |\n"
         "| --- | --- | --- | --- |\n"
         (str/join "\n"
           (map (fn [evt]
                  (str "| " (or (:type evt) "?") " "
                       "| " (or (:source evt) "?") " "
                       "| " (or (get-in evt [:metadata :severity])
                                (get-in evt [:metadata "severity"])
                                "info") " "
                       "| " (or (get-in evt [:metadata :timestamp])
                                (get-in evt [:metadata "timestamp"])
                                "?") " |"))
                events)))))

(defn- format-sources
  "Formats a sequence of source strings into a bulleted list."
  [sources]
  (if (empty? sources)
    "No active event sources"
    (str/join "\n"
      (map (fn [s] (str "- " s)) sources))))

(defn- format-subscriptions
  "Formats event subscriptions from the registry into a bulleted list."
  [subscriptions]
  (if (empty? subscriptions)
    "No event subscriptions registered"
    (str/join "\n"
      (map (fn [sub]
             (str "- **" (or (:name sub) (:id sub)) "**: "
                  "pattern=`" (or (:event-pattern sub) "*") "` "
                  "action=" (name (or (:event-action sub) :unknown))
                  (when-let [sev (:event-severity-filter sub)]
                    (str " severity=" (str/join "," (map name sev))))))
           subscriptions))))

;; ---------------------------------------------------------------------------
;; Command Handlers
;; ---------------------------------------------------------------------------

(defn handle-event-recent
  "Handler for event:recent slash command.
   Fetches recent events via *fetch-recent-fn* and inserts a table block."
  [e]
  (let [block-uuid (.-uuid e)]
    (if *fetch-recent-fn*
      (-> (*fetch-recent-fn*)
          (.then (fn [events]
                   (let [content (format-recent-events events)]
                     (js/logseq.Editor.insertBlock
                       block-uuid content
                       (clj->js {:sibling false})))))
          (.catch (fn [err]
                    (js/logseq.Editor.insertBlock
                      block-uuid (str "Error fetching recent events: " (.-message err))
                      (clj->js {:sibling false})))))
      (js/logseq.Editor.insertBlock
        block-uuid "Event fetching not available (server not connected)"
        (clj->js {:sibling false})))))

(defn handle-event-sources
  "Handler for event:sources slash command.
   Fetches active sources via *fetch-sources-fn* and inserts a list block."
  [e]
  (let [block-uuid (.-uuid e)]
    (if *fetch-sources-fn*
      (-> (*fetch-sources-fn*)
          (.then (fn [sources]
                   (let [content (format-sources sources)]
                     (js/logseq.Editor.insertBlock
                       block-uuid content
                       (clj->js {:sibling false})))))
          (.catch (fn [err]
                    (js/logseq.Editor.insertBlock
                      block-uuid (str "Error fetching sources: " (.-message err))
                      (clj->js {:sibling false})))))
      (js/logseq.Editor.insertBlock
        block-uuid "Source fetching not available (server not connected)"
        (clj->js {:sibling false})))))

(defn handle-event-test
  "Handler for event:test slash command.
   Publishes a test event via *publish-fn* and inserts a confirmation block."
  [e]
  (let [block-uuid (.-uuid e)]
    (if *publish-fn*
      (-> (*publish-fn* {:type "test.manual"
                         :source "user:slash-command"
                         :data {:triggered-by "user"}
                         :metadata {:severity "info"}})
          (.then (fn [result]
                   (let [event-id (or (:event-id result) "unknown")]
                     (js/logseq.Editor.insertBlock
                       block-uuid
                       (str "Test event published (id: " event-id ")")
                       (clj->js {:sibling false})))))
          (.catch (fn [err]
                    (js/logseq.Editor.insertBlock
                      block-uuid (str "Error publishing test event: " (.-message err))
                      (clj->js {:sibling false})))))
      (js/logseq.Editor.insertBlock
        block-uuid "Event publishing not available (server not connected)"
        (clj->js {:sibling false})))))

(defn handle-event-list
  "Handler for event:list slash command.
   Reads event subscriptions from the registry store and inserts a formatted block."
  [e]
  (let [block-uuid (.-uuid e)
        subscriptions (store/list-entries :event-subscription)
        content (format-subscriptions subscriptions)]
    (js/logseq.Editor.insertBlock
      block-uuid content
      (clj->js {:sibling false}))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn register-commands!
  "Registers all event:* slash commands in Logseq."
  []
  (js/logseq.Editor.registerSlashCommand "event:recent" handle-event-recent)
  (js/logseq.Editor.registerSlashCommand "event:sources" handle-event-sources)
  (js/logseq.Editor.registerSlashCommand "event:test" handle-event-test)
  (js/logseq.Editor.registerSlashCommand "event:list" handle-event-list)
  (js/console.log "[EventHub] Slash commands registered"))
