(ns logseq-ai-hub.event-hub.emit
  "Emit event step executor for the job runner.
   Provides :emit-event step action that publishes custom events
   to the EventBus via the publish module."
  (:require [logseq-ai-hub.job-runner.interpolation :as interpolation]
            [logseq-ai-hub.util.errors :as errors]))

;; Dynamic var for publish function -- wired to publish/publish-to-server! during init.
;; Uses dynamic var to avoid circular dependency between emit and publish modules.
(def ^:dynamic *publish-event-fn*
  "Function to publish an event to the server's EventBus.
   Signature: (fn [{:keys [type source data metadata]}] Promise<{:event-id ...}>).
   Set during system init in event_hub/init.cljs."
  nil)

;; =============================================================================
;; Interpolation Helpers
;; =============================================================================

(defn- interpolate-data-map
  "Recursively interpolates all string values in a data map."
  [m context]
  (when m
    (into {}
      (for [[k v] m]
        [k (cond
             (string? v) (interpolation/interpolate v context)
             (map? v) (interpolate-data-map v context)
             :else v)]))))

;; =============================================================================
;; Emit Event Executor
;; =============================================================================

(defn emit-event-executor
  "Step executor for :emit-event action.
   Reads step-config for type, data, metadata.
   Interpolates type and all string values in data.
   Sets source to \"skill:{job-id}\" from context.
   Increments chain_depth from context metadata (or starts at 1).
   Calls *publish-event-fn* and returns {:event-id ... :published true}."
  [step context]
  (if-not *publish-event-fn*
    (js/Promise.reject
      (errors/make-error :emit-not-initialized "Publish event function not initialized"))
    (let [config (:step-config step)
          ;; Build interpolation context matching what interpolation/interpolate expects
          interp-ctx {:inputs (:inputs context)
                      :step-results (:step-results context)
                      :variables (:variables context)}
          ;; Read and interpolate config values
          raw-type (get config "type")
          event-type (when raw-type
                       (interpolation/interpolate raw-type interp-ctx))
          raw-data (get config "data" {})
          data (interpolate-data-map raw-data interp-ctx)
          raw-metadata (get config "metadata" {})
          ;; Source from job context
          job-id (or (:job-id context) "unknown")
          source (str "skill:" job-id)
          ;; Chain depth management
          context-metadata (:metadata context)
          current-depth (or (get context-metadata :chain_depth)
                            (get context-metadata "chain_depth")
                            0)
          next-depth (inc current-depth)
          ;; Merge chain_depth into metadata
          metadata (assoc raw-metadata "chain_depth" next-depth)]
      (cond
        ;; No event type provided
        (or (nil? event-type) (= "" event-type))
        (js/Promise.reject
          (errors/make-error :emit-invalid-type "No event type provided in step config"))

        ;; Execute the publish
        :else
        (-> (*publish-event-fn* {:type event-type
                                 :source source
                                 :data data
                                 :metadata metadata})
            (.then (fn [result]
                     {:event-id (or (:event-id result) "unknown")
                      :published true})))))))
