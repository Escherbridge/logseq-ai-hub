(ns logseq-ai-hub.settings-writer
  "Serializes all writes to logseq.settings to prevent concurrent write races.
   Uses a Promise-chain queue identical to the graph write queue pattern.")

;; ---------------------------------------------------------------------------
;; Write Queue State
;; ---------------------------------------------------------------------------

(defonce queue-state (atom {:promise (js/Promise.resolve nil)}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn queue-settings-write!
  "Enqueues a settings write operation to prevent concurrent conflicts.
   `write-fn` should be a zero-arg function that performs the settings write
   and optionally returns a Promise.
   Returns a Promise that resolves when the write completes.
   Errors in write-fn are caught and logged but don't break the queue."
  [write-fn]
  (let [new-promise (-> (:promise @queue-state)
                        (.then (fn [_] (write-fn)))
                        (.catch (fn [err]
                                  (js/console.error "Settings write error:" err)
                                  nil)))]
    (swap! queue-state assoc :promise new-promise)
    new-promise))

(defn reset-queue!
  "Resets the write queue. Useful for testing."
  []
  (reset! queue-state {:promise (js/Promise.resolve nil)}))
