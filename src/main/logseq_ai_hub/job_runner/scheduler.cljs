(ns logseq-ai-hub.job-runner.scheduler
  "Job scheduler for cron-based scheduled jobs."
  (:require [logseq-ai-hub.job-runner.cron :as cron]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Forward Declarations - Dynamic Vars for Dependencies
;; ---------------------------------------------------------------------------

(def ^:dynamic runner-enqueue-job! nil)
(def ^:dynamic graph-read-job-page nil)
(def ^:dynamic graph-scan-job-pages nil)

;; ---------------------------------------------------------------------------
;; Scheduler State
;; ---------------------------------------------------------------------------

(defonce scheduler-state
  (atom {:status :stopped  ;; :stopped | :running
         :registered {}  ;; job-id -> {:cron-expr "..." :parsed-cron {...} :last-fired nil}
         :timer-id nil}))

;; ---------------------------------------------------------------------------
;; Schedule Registration
;; ---------------------------------------------------------------------------

(defn register-schedule!
  "Registers a schedule for a job. Parses the cron expression and stores it.
   Returns true if successful, false if cron expression is invalid."
  [job-id cron-expr]
  (if-let [parsed (cron/parse-cron cron-expr)]
    (do
      (swap! scheduler-state assoc-in [:registered job-id]
             {:cron-expr cron-expr
              :parsed-cron parsed
              :last-fired nil})
      true)
    false))

(defn unregister-schedule!
  "Removes a registered schedule."
  [job-id]
  (swap! scheduler-state update :registered dissoc job-id))

(defn list-schedules
  "Returns all registered schedules as a map of job-id -> schedule info."
  []
  (:registered @scheduler-state))

;; ---------------------------------------------------------------------------
;; Schedule Checking and Job Instance Creation
;; ---------------------------------------------------------------------------

(defn- format-minute-key
  "Formats a JS Date into a unique key for the current minute.
   Format: YYYY-M-D-H-M"
  [js-date]
  (str (.getFullYear js-date) "-"
       (.getMonth js-date) "-"
       (.getDate js-date) "-"
       (.getHours js-date) "-"
       (.getMinutes js-date)))

(defn- format-timestamp
  "Formats a JS Date into a timestamp string for job instance naming.
   Format: YYYYMMDD-HHMM"
  [js-date]
  (let [year (.getFullYear js-date)
        month (inc (.getMonth js-date))
        day (.getDate js-date)
        hour (.getHours js-date)
        minute (.getMinutes js-date)
        pad2 (fn [n] (str (when (< n 10) "0") n))]
    (str year (pad2 month) (pad2 day) "-" (pad2 hour) (pad2 minute))))

(defn- create-job-instance!
  "Creates a new job instance page from a template job.
   Returns Promise<instance-page-name>."
  [template-job-id now-date]
  (let [timestamp (format-timestamp now-date)
        ;; Extract template name without "Jobs/" prefix
        template-name (if (str/starts-with? template-job-id "Jobs/")
                       (subs template-job-id 5)
                       template-job-id)
        instance-name (str "Jobs/" template-name "-" timestamp)]
    (-> (graph-read-job-page template-job-id)
        (.then (fn [job-def]
                 (if-not job-def
                   (js/Promise.reject (str "Template job not found: " template-job-id))
                   ;; Create new page with skill reference
                   ;; Access properties from the job-def structure (which may have :properties nested or flat)
                   (let [props (or (:properties job-def) job-def)
                         skill-ref (:job-skill props)
                         properties #js {:job-type "scheduled-instance"
                                       :job-status "queued"
                                       :job-skill skill-ref
                                       :job-template template-job-id
                                       :job-created-at (.toISOString now-date)}
                         opts #js {:createFirstBlock false
                                 :properties properties}]
                     (-> (js/logseq.Editor.createPage instance-name properties opts)
                         (.then (fn [_page]
                                  instance-name))))))))))

(defn check-schedules!
  "Checks all registered schedules and fires matching ones.
   Returns Promise<nil>."
  [now-date]
  (let [current-minute (format-minute-key now-date)
        registered (:registered @scheduler-state)
        promises (for [[job-id schedule] registered
                       :let [parsed (:parsed-cron schedule)
                             last-fired (:last-fired schedule)]
                       :when (and (cron/matches-now? parsed now-date)
                                 (not= last-fired current-minute))]
                   (-> (create-job-instance! job-id now-date)
                       (.then (fn [instance-name]
                                ;; Enqueue the new instance
                                (when runner-enqueue-job!
                                  (runner-enqueue-job! instance-name))
                                ;; Update last-fired
                                (swap! scheduler-state assoc-in
                                       [:registered job-id :last-fired]
                                       current-minute)
                                (js/console.log "Scheduled job fired:" job-id "->" instance-name)))
                       (.catch (fn [err]
                                 (js/console.error "Failed to fire scheduled job:" job-id err)))))]
    (if (empty? promises)
      (js/Promise.resolve nil)
      (-> (js/Promise.all (clj->js promises))
          (.then (fn [_] nil))))))

;; ---------------------------------------------------------------------------
;; Scheduler Lifecycle
;; ---------------------------------------------------------------------------

(defn- scheduler-tick!
  "Performs one scheduler tick. Self-schedules next tick if still running."
  []
  (when (= :running (:status @scheduler-state))
    (-> (check-schedules! (js/Date.))
        (.then (fn [_]
                 ;; Schedule next tick (60 seconds)
                 (when (= :running (:status @scheduler-state))
                   (let [timer-id (js/setTimeout scheduler-tick! 60000)]
                     (swap! scheduler-state assoc :timer-id timer-id)))))
        (.catch (fn [err]
                  (js/console.error "Scheduler tick error:" err)
                  ;; Continue ticking even on error
                  (when (= :running (:status @scheduler-state))
                    (let [timer-id (js/setTimeout scheduler-tick! 60000)]
                      (swap! scheduler-state assoc :timer-id timer-id))))))))

(defn start-scheduler!
  "Starts the scheduler. Begins 60-second tick loop."
  []
  (when (not= :running (:status @scheduler-state))
    (swap! scheduler-state assoc :status :running)
    (js/console.log "Job scheduler starting...")
    (scheduler-tick!)))

(defn stop-scheduler!
  "Stops the scheduler. Clears the timeout and sets status to stopped."
  []
  (let [timer-id (:timer-id @scheduler-state)]
    (when timer-id
      (js/clearTimeout timer-id))
    (swap! scheduler-state assoc :status :stopped :timer-id nil)
    (js/console.log "Job scheduler stopped")))

;; ---------------------------------------------------------------------------
;; Integration with Runner
;; ---------------------------------------------------------------------------

(defn scan-and-register-schedules!
  "Scans for scheduled jobs in the graph and registers their schedules.
   Returns Promise<count of registered schedules>."
  [job-prefix]
  (-> (graph-scan-job-pages job-prefix)
      (.then (fn [job-defs]
               (let [scheduled-jobs (filter #(let [props (or (:properties %) %)]
                                              (and (= :scheduled (:job-type props))
                                                   (some? (:job-schedule props))))
                                           job-defs)
                     registered-count (atom 0)]
                 (doseq [job-def scheduled-jobs]
                   (let [job-id (:job-id job-def)
                         props (or (:properties job-def) job-def)
                         cron-expr (:job-schedule props)]
                     (when (register-schedule! job-id cron-expr)
                       (swap! registered-count inc))))
                 (js/console.log "Registered" @registered-count "scheduled jobs")
                 @registered-count)))
      (.catch (fn [err]
                (js/console.error "Failed to scan and register schedules:" err)
                (js/Promise.reject err)))))

;; ---------------------------------------------------------------------------
;; Module Initialization
;; ---------------------------------------------------------------------------

(defn ^:export init-scheduler!
  "Initializes the scheduler with module dependencies.
   Call this once when the plugin loads."
  [deps]
  (when-let [runner (:runner deps)]
    (set! runner-enqueue-job! (:enqueue-job! runner)))
  (when-let [graph (:graph deps)]
    (set! graph-read-job-page (:read-job-page graph))
    (set! graph-scan-job-pages (:scan-job-pages graph))))
