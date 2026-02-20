(ns logseq-ai-hub.job-runner.engine
  "Skill execution engine with context management, step orchestration, and retry logic."
  (:require [logseq-ai-hub.util.errors :as errors]))

;; Forward declaration for executor namespace functions
;; These will be provided by the executor module
(def ^:dynamic executor-execute-step nil)

;; ---------------------------------------------------------------------------
;; Context Management
;; ---------------------------------------------------------------------------

(defn make-context
  "Creates an execution context for a job.
   Contains job-id, inputs, step-results, and built-in variables."
  [job-id inputs]
  (let [now (js/Date.)
        iso-str (.toISOString now)
        date-str (.substring iso-str 0 10)]  ; Extract just the date part
    {:job-id job-id
     :inputs inputs
     :step-results {}
     :variables {:today date-str
                 :now iso-str
                 :job-id job-id}}))

(defn update-context
  "Updates context with a step result."
  [ctx step-order result]
  (assoc-in ctx [:step-results step-order] result))

(defn resolve-input
  "Resolves an input reference from the context.
   - 'step-N-result' -> looks up step N in :step-results
   - 'key' -> looks up key in :inputs"
  [ctx input-ref]
  (if-let [[_ n] (re-matches #"step-(\d+)-result" input-ref)]
    (get-in ctx [:step-results (js/parseInt n)])
    (get-in ctx [:inputs input-ref])))

;; ---------------------------------------------------------------------------
;; Step Execution
;; ---------------------------------------------------------------------------

(defn run-steps
  "Executes steps sequentially, handling conditional jumps.
   Returns a Promise resolving to the final context.

   Steps are executed in order by :step-order. If a step returns a
   {:directive :jump :target-step N} result, execution jumps to step N.

   on-step-complete is called with [step result] after each step."
  [steps context on-step-complete]
  (let [sorted-steps (vec (sort-by :step-order steps))
        step-count (count sorted-steps)]
    (letfn [(run-from [idx ctx]
              (if (>= idx step-count)
                (js/Promise.resolve ctx)
                (let [step (nth sorted-steps idx)]
                  (-> (executor-execute-step step ctx)
                      (.then (fn [result]
                               (let [new-ctx (update-context ctx (:step-order step) result)]
                                 (when on-step-complete
                                   (on-step-complete step result))
                                 ;; Check for conditional jump
                                 (if (and (map? result) (= :jump (:directive result)))
                                   ;; Find index of target step
                                   (let [target (:target-step result)
                                         target-idx (first (keep-indexed
                                                            (fn [i s] (when (= (:step-order s) target) i))
                                                            sorted-steps))]
                                     (if target-idx
                                       (run-from target-idx new-ctx)
                                       (js/Promise.reject
                                        (errors/make-error :execution-error
                                                          (str "Jump target step " target " not found")))))
                                   (run-from (inc idx) new-ctx)))))
                      (.catch (fn [err]
                                (js/Promise.reject
                                 {:error true
                                  :type :step-execution-error
                                  :failed-step (:step-order step)
                                  :message (if (instance? js/Error err)
                                            (.-message err)
                                            (str err))
                                  :context ctx})))))))]
      (run-from 0 context))))

;; ---------------------------------------------------------------------------
;; Skill Execution
;; ---------------------------------------------------------------------------

(defn execute-skill
  "Executes a skill with given inputs and job-id.
   Returns a Promise resolving to:
   - {:status :completed :result <last-step-result> :context <ctx> :duration-ms <N>}
   - {:status :failed :error <error-map> :failed-step <N> :context <ctx> :duration-ms <N>}

   on-progress is called with [step result] after each step completes."
  [skill-def inputs job-id on-progress]
  (let [start-time (js/Date.now)
        ctx (make-context job-id inputs)
        steps (:steps skill-def)]
    (-> (run-steps steps ctx on-progress)
        (.then (fn [final-ctx]
                 (let [duration (- (js/Date.now) start-time)
                       last-step-order (apply max (map :step-order steps))
                       last-result (get-in final-ctx [:step-results last-step-order])]
                   {:status :completed
                    :result last-result
                    :context final-ctx
                    :duration-ms duration})))
        (.catch (fn [err]
                  (let [duration (- (js/Date.now) start-time)]
                    (js/Promise.resolve
                     {:status :failed
                      :error err
                      :failed-step (:failed-step err)
                      :context (:context err)
                      :duration-ms duration})))))))

(defn execute-skill-with-retries
  "Executes a skill with retry logic.
   On failure, retries up to max-retries times.

   on-progress is called with [step result retry-count] after each step.
   Returns the final result after success or all retries exhausted."
  [skill-def inputs job-id max-retries on-progress]
  (letfn [(attempt [retry-count]
            (let [wrapped-progress (when on-progress
                                    (fn [step result]
                                      (on-progress step result retry-count)))]
              (-> (execute-skill skill-def inputs job-id wrapped-progress)
                  (.then (fn [result]
                           (if (and (= :failed (:status result))
                                   (< retry-count max-retries))
                             ;; Retry
                             (attempt (inc retry-count))
                             ;; Success or retries exhausted
                             result))))))]
    (attempt 0)))

;; ---------------------------------------------------------------------------
;; Wire executor integration
;; ---------------------------------------------------------------------------

;; This will be set by the executor module when it loads
;; For now, we provide a placeholder that will be replaced
(defn ^:export set-executor-execute-step! [fn]
  (set! executor-execute-step fn))
