(ns logseq-ai-hub.event-hub.webhook-source
  "Webhook source processing for the Event Hub.
   Provides utilities for extracting data from webhook payloads
   and applying source-specific configuration to events."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Path Extraction
;; ---------------------------------------------------------------------------

(defn extract-by-path
  "Extracts a value from a nested map using a dot-separated path string.
   E.g., (extract-by-path {:alert {:status \"firing\"}} \"alert.status\")
   => \"firing\"

   Returns nil if the path doesn't exist or data is nil."
  [data path-str]
  (when (and data path-str (not (str/blank? path-str)))
    (let [keys (str/split path-str #"\.")]
      (get-in data keys))))

;; ---------------------------------------------------------------------------
;; Source Config Application
;; ---------------------------------------------------------------------------

(defn apply-webhook-source
  "Applies a webhook source configuration to a hub event.

   Source config options:
   - :extract-paths  - seq of dot-path strings to extract from event data.
                       Replaces event data with only the extracted fields.
   - :page-prefix    - string to override the default Events/ page prefix.

   Returns the transformed event, or the original if no config is provided."
  [hub-event source-config]
  (if (or (nil? source-config) (empty? source-config))
    hub-event
    (let [;; Apply extract-paths: replace data with extracted subset
          with-extracted
          (if-let [paths (:extract-paths source-config)]
            (let [data (:data hub-event)
                  extracted (into {}
                              (for [path paths
                                    :let [v (extract-by-path data path)]
                                    :when (some? v)]
                                [path v]))]
              (assoc hub-event :data extracted))
            hub-event)

          ;; Apply page-prefix override
          with-prefix
          (if-let [prefix (:page-prefix source-config)]
            (assoc-in with-extracted [:metadata :page-prefix] prefix)
            with-extracted)]

      with-prefix)))
