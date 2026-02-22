(ns logseq-ai-hub.job-runner.runner
  "Job runner core with queue management, polling, and job lifecycle."
  (:require [logseq-ai-hub.util.errors :as errors]
            [clojure.string :as str]))

;; Forward declarations for module dependencies
;; These will be provided by other modules or redefined in tests
(def ^:dynamic graph-read-job-page nil)
(def ^:dynamic graph-read-skill-page nil)
(def ^:dynamic graph-scan-job-pages nil)
(def ^:dynamic graph-update-job-status! nil)
(def ^:dynamic graph-update-job-property! nil)
(def ^:dynamic graph-append-job-log! nil)
(def ^:dynamic graph-queue-write! nil)

(def ^:dynamic engine-execute-skill-with-retries nil)

(def ^:dynamic queue-make-queue nil)
(def ^:dynamic queue-enqueue nil)
(def ^:dynamic queue-dequeue nil)
(def ^:dynamic queue-remove-from-queue nil)
(def ^:dynamic queue-queue-size nil)
(def ^:dynamic queue-find-in-queue nil)

;; ---------------------------------------------------------------------------
;; Runner State
;; ---------------------------------------------------------------------------

(defonce runner-state
  (atom {:status :stopped  ;; :stopped | :running
         :queue nil  ;; priority queue (will be initialized)
         :running #{}  ;; set of currently running job IDs
         :completed {}  ;; job-id -> result
         :failed {}  ;; job-id -> error
         :config {:max-concurrent 3
                  :poll-interval-ms 5000
                  :default-timeout-ms 300000
                  :job-prefix "Jobs/"
                  :skill-prefix "Skills/"}
         :timer-id nil}))

(defn reset-runner!
  "Resets runner state to initial values. Used for testing."
  []
  (let [queue (if queue-make-queue
                (queue-make-queue)
                {:items (atom [])})]  ;; Fallback for testing
    (reset! runner-state
            {:status :stopped
             :queue queue
             :running #{}
             :completed {}
             :failed {}
             :config {:max-concurrent 3
                      :poll-interval-ms 5000
                      :default-timeout-ms 300000
                      :job-prefix "Jobs/"
                      :skill-prefix "Skills/"}
             :timer-id nil})))

;; ---------------------------------------------------------------------------
;; Status and Config
;; ---------------------------------------------------------------------------

(defn runner-status
  "Returns current runner status with queue and job counts."
  []
  (let [s @runner-state
        queue-size-fn (or queue-queue-size (fn [q] (count @(:items q))))]
    {:status (:status s)
     :queued (queue-size-fn (:queue s))
     :running (count (:running s))
     :completed (count (:completed s))
     :failed (count (:failed s))}))

(defn update-config!
  "Updates runner configuration. Merges with existing config."
  [config-map]
  (swap! runner-state update :config merge config-map))

(defn build-status-map
  "Builds a status map from current runner state.
   Maps job-id -> status (:running | :completed | :failed)"
  []
  (let [s @runner-state]
    (merge
     (into {} (map (fn [id] [id :running]) (:running s)))
     (into {} (map (fn [[id _]] [id :completed]) (:completed s)))
     (into {} (map (fn [[id _]] [id :failed]) (:failed s))))))

;; ---------------------------------------------------------------------------
;; Job Lifecycle
;; ---------------------------------------------------------------------------

(defn enqueue-job!
  "Enqueues a job for execution.
   Reads job page, validates it, creates queue entry, and updates status."
  [job-id]
  (-> (graph-read-job-page job-id)
      (.then (fn [job-def]
               (if-not job-def
                 (js/Promise.reject
                  (errors/make-error :job-not-found
                                    (str "Job page not found: " job-id)))
                 (let [queue-entry {:job-id job-id
                                   :priority (or (:job-priority job-def) 3)
                                   :created-at (.toISOString (js/Date.))
                                   :depends-on (or (:job-depends-on job-def) #{})}
                       enqueue-fn (or queue-enqueue
                                     (fn [q entry]
                                       (swap! (:items q) conj entry)))]
                   (enqueue-fn (:queue @runner-state) queue-entry)
                   (graph-update-job-status! job-id "queued")))))
      (.catch (fn [err]
                (js/console.error "Failed to enqueue job:" err)
                (js/Promise.reject err)))))

(defn cancel-job!
  "Cancels a job. Removes from queue and updates status to cancelled."
  [job-id]
  (let [remove-fn (or queue-remove-from-queue
                     (fn [q id]
                       (swap! (:items q) (fn [items]
                                          (vec (remove #(= (:job-id %) id) items))))))]
    (remove-fn (:queue @runner-state) job-id)
    (swap! runner-state update :running disj job-id)
    (graph-update-job-status! job-id "cancelled")))

(defn pause-job!
  "Pauses a job. Removes from queue and updates status to paused."
  [job-id]
  (let [remove-fn (or queue-remove-from-queue
                     (fn [q id]
                       (swap! (:items q) (fn [items]
                                          (vec (remove #(= (:job-id %) id) items))))))]
    (remove-fn (:queue @runner-state) job-id)
    (graph-update-job-status! job-id "paused")))

(defn resume-job!
  "Resumes a paused job. Re-enqueues it."
  [job-id]
  (enqueue-job! job-id))

;; ---------------------------------------------------------------------------
;; Job Execution
;; ---------------------------------------------------------------------------

(defn execute-job!
  "Executes a job by:
   1. Reading job and skill pages
   2. Updating status to running
   3. Executing skill with engine
   4. Writing results and logs
   5. Removing from running set"
  [job-id]
  (let [start-time (js/Date.)
        start-iso (.toISOString start-time)]
    (-> (graph-read-job-page job-id)
        (.then (fn [job-def]
                 (if-not job-def
                   (js/Promise.reject
                    (errors/make-error :job-not-found
                                      (str "Job not found: " job-id)))
                   (let [skill-name (:job-skill job-def)]
                     (if-not skill-name
                       (js/Promise.reject
                        (errors/make-error :job-invalid
                                          (str "Job missing skill reference: " job-id)))
                       (js/Promise.all
                        #js [job-def
                             (graph-read-skill-page skill-name)]))))))
        (.then (fn [[job-def skill-def]]
                 (if-not skill-def
                   (js/Promise.reject
                    (errors/make-error :skill-not-found
                                      (str "Skill not found: " (:job-skill job-def))))
                   (do
                     ;; Mark as running
                     (swap! runner-state update :running conj job-id)
                     (graph-update-job-status! job-id "running")
                     (graph-update-job-property! job-id "job-started-at" start-iso)
                     (graph-append-job-log! job-id "Job started")

                     ;; Execute skill
                     (let [inputs (or (:job-input job-def) {})
                           max-retries (or (:job-max-retries job-def) 0)
                           on-progress (fn [step result retry-count]
                                        (graph-append-job-log!
                                         job-id
                                         (str "Step " (:step-order step)
                                              " completed (retry: " retry-count ")")))]
                       (-> (engine-execute-skill-with-retries
                            skill-def inputs job-id max-retries on-progress)
                           (.then (fn [result]
                                    [job-def result]))))))))
        (.then (fn [[job-def result]]
                 (let [completed-at (.toISOString (js/Date.))]
                   (if (= :completed (:status result))
                     (do
                       ;; Success
                       (swap! runner-state update :completed assoc job-id result)
                       (graph-update-job-status! job-id "completed")
                       (graph-update-job-property! job-id "job-completed-at" completed-at)
                       (graph-update-job-property! job-id "job-result"
                                                  (str (:result result)))
                       (graph-append-job-log! job-id "Job completed successfully"))
                     (do
                       ;; Failed
                       (swap! runner-state update :failed assoc job-id result)
                       (graph-update-job-status! job-id "failed")
                       (graph-update-job-property! job-id "job-completed-at" completed-at)
                       (graph-update-job-property! job-id "job-error"
                                                  (str (:error result)))
                       (graph-append-job-log! job-id
                                             (str "Job failed: " (:error result))))))))
        (.catch (fn [err]
                  (js/console.error "Job execution error:" err)
                  (swap! runner-state update :failed assoc job-id {:error err})
                  (graph-update-job-status! job-id "failed")
                  (graph-update-job-property! job-id "job-error" (str err))
                  (graph-append-job-log! job-id (str "Job failed with error: " err))))
        (.finally (fn []
                    ;; Always remove from running set
                    (swap! runner-state update :running disj job-id))))))

;; ---------------------------------------------------------------------------
;; Polling Loop
;; ---------------------------------------------------------------------------

(defn poll-tick!
  "Performs one polling tick:
   1. Builds status map
   2. Dequeues eligible job
   3. Executes job (async, don't await)
   4. Schedules next tick"
  []
  (when (= :running (:status @runner-state))
    (let [state @runner-state
          status-map (build-status-map)
          max-concurrent (get-in state [:config :max-concurrent])
          running-set (:running state)
          dequeue-fn (or queue-dequeue
                        (fn [queue status-map max-concurrent running-set]
                          (let [items @(:items queue)
                                eligible (first (filter
                                                 (fn [entry]
                                                   (and
                                                    (not (contains? running-set (:job-id entry)))
                                                    (not (contains? status-map (:job-id entry)))
                                                    (every? #(= :completed (get status-map %))
                                                           (:depends-on entry))))
                                                 items))]
                            (when eligible
                              (swap! (:items queue)
                                    (fn [items]
                                      (vec (remove #(= (:job-id %) (:job-id eligible)) items)))))
                            eligible)))]

      ;; Try to dequeue if we have capacity
      (when (< (count running-set) max-concurrent)
        (when-let [eligible (dequeue-fn (:queue state) status-map max-concurrent running-set)]
          ;; Execute job asynchronously (don't await)
          (execute-job! (:job-id eligible))))

      ;; Schedule next tick
      (let [interval (get-in state [:config :poll-interval-ms])
            timer-id (js/setTimeout poll-tick! interval)]
        (swap! runner-state assoc :timer-id timer-id)))))

(defn start-runner!
  "Starts the job runner:
   1. Scans graph for existing jobs to rebuild queue
   2. Starts polling loop
   3. Sets status to :running"
  []
  (let [job-prefix (get-in @runner-state [:config :job-prefix])]
    (-> (graph-scan-job-pages job-prefix)
        (.then (fn [job-defs]
                 ;; Enqueue jobs that are in queued status
                 (doseq [job-def job-defs]
                   (when (= :queued (:job-status job-def))
                     (let [enqueue-fn (or queue-enqueue
                                         (fn [q entry]
                                           (swap! (:items q) conj entry)))
                           queue-entry {:job-id (:job-id job-def)
                                       :priority (or (:job-priority job-def) 3)
                                       :created-at (or (:job-created-at job-def)
                                                      (.toISOString (js/Date.)))
                                       :depends-on (or (:job-depends-on job-def) #{})}]
                       (enqueue-fn (:queue @runner-state) queue-entry))))

                 ;; Start polling
                 (swap! runner-state assoc :status :running)
                 (poll-tick!)

                 (js/console.log "Job runner started")))
        (.catch (fn [err]
                  (js/console.error "Failed to start runner:" err)
                  (js/Promise.reject err))))))

(defn stop-runner!
  "Stops the job runner:
   1. Clears timeout
   2. Sets status to :stopped"
  []
  (let [timer-id (:timer-id @runner-state)]
    (when timer-id
      (js/clearTimeout timer-id))
    (swap! runner-state assoc :status :stopped :timer-id nil)
    (js/console.log "Job runner stopped")))

;; ---------------------------------------------------------------------------
;; Module Initialization
;; ---------------------------------------------------------------------------

(defn ^:export init-runner!
  "Initializes the runner with module dependencies.
   Call this once when the plugin loads."
  [deps]
  ;; Set dynamic vars from dependencies
  (when-let [graph (:graph deps)]
    (set! graph-read-job-page (:read-job-page graph))
    (set! graph-read-skill-page (:read-skill-page graph))
    (set! graph-scan-job-pages (:scan-job-pages graph))
    (set! graph-update-job-status! (:update-job-status! graph))
    (set! graph-update-job-property! (:update-job-property! graph))
    (set! graph-append-job-log! (:append-job-log! graph))
    (set! graph-queue-write! (:queue-write! graph)))

  (when-let [engine (:engine deps)]
    (set! engine-execute-skill-with-retries (:execute-skill-with-retries engine)))

  (when-let [queue (:queue deps)]
    (set! queue-make-queue (:make-queue queue))
    (set! queue-enqueue (:enqueue queue))
    (set! queue-dequeue (:dequeue queue))
    (set! queue-remove-from-queue (:remove-from-queue queue))
    (set! queue-queue-size (:queue-size queue))
    (set! queue-find-in-queue (:find-in-queue queue)))

  ;; Initialize queue
  (swap! runner-state assoc :queue (queue-make-queue)))
