(ns logseq-ai-hub.event-hub.graph
  "Persists hub events to the Logseq graph as child blocks under Events/{Source} pages."
  (:require [clojure.string :as str]))

(defn- sanitize-source
  "Sanitizes a source string for use as a page name segment.
   Replaces colons with dashes, removes other special characters."
  [source]
  (-> (str source)
      (str/replace #":" "-")
      (str/replace #"[^a-zA-Z0-9_/.-]" "-")
      (str/replace #"-+" "-")
      (str/replace #"^-|-$" "")))

(defn- format-event-block
  "Formats a hub event into a Logseq block content string."
  [hub-event]
  (let [{:keys [id type source timestamp data metadata]} hub-event
        severity (or (:severity metadata) "info")
        data-str (try
                   (js/JSON.stringify (clj->js data) nil 2)
                   (catch :default _
                     (str data)))]
    (str "**" type "** - " (or timestamp (.toISOString (js/Date.))) "\n"
         "event-id:: " id "\n"
         "event-type:: " type "\n"
         "event-source:: " source "\n"
         "event-severity:: " severity "\n"
         "event-data:: " data-str)))

(defn persist-to-graph!
  "Persists a hub event to the Logseq graph.
   Creates page Events/{Source} if it doesn't exist, then appends the event as a child block.
   Returns a Promise."
  [hub-event]
  (let [source (sanitize-source (:source hub-event))
        page-name (str "Events/" source)]
    (-> (js/logseq.Editor.createPage
          page-name
          #js {}
          #js {:createFirstBlock false :redirect false})
        (.catch (fn [_] nil))  ;; page may already exist
        (.then (fn [_]
                 (let [block-content (format-event-block hub-event)]
                   (js/logseq.Editor.appendBlockInPage page-name block-content))))
        (.catch (fn [err]
                  (js/console.warn "[EventHub] Failed to persist event to graph:" err))))))
