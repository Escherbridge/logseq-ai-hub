(ns logseq-ai-hub.job-runner.queue)

(defn make-queue
  "Returns an empty queue (empty vector)."
  []
  [])

(defn- compare-jobs
  "Comparator for sorting jobs by priority (lower first) then created-at (earlier first)."
  [job1 job2]
  (let [priority-cmp (compare (:priority job1) (:priority job2))]
    (if (zero? priority-cmp)
      (compare (:created-at job1) (:created-at job2))
      priority-cmp)))

(defn enqueue
  "Insert job into queue maintaining sort by [:priority :created-at].
  Lower priority number comes first, then earlier created-at within same priority.
  Returns new queue."
  [queue job-entry]
  (vec (sort compare-jobs (conj queue job-entry))))

(defn- job-eligible?
  "Returns true if job is eligible to run based on dependencies and concurrency limits."
  [job status-map max-concurrent running-set]
  (let [deps-met? (or (empty? (:depends-on job))
                      (every? #(= :completed (get status-map %))
                              (:depends-on job)))
        under-limit? (< (count running-set) max-concurrent)]
    (and deps-met? under-limit?)))

(defn dequeue
  "Returns [next-eligible-entry remaining-queue] or [nil queue] if none eligible.
  A job is eligible if:
  - Its :depends-on set is empty OR all dependencies have :completed status in status-map
  - The running-set count is less than max-concurrent
  Returns the highest-priority eligible job."
  [queue status-map max-concurrent running-set]
  (if (empty? queue)
    [nil queue]
    (let [eligible-idx (first (keep-indexed
                               (fn [idx job]
                                 (when (job-eligible? job status-map max-concurrent running-set)
                                   idx))
                               queue))]
      (if eligible-idx
        (let [job (nth queue eligible-idx)
              remaining (vec (concat (subvec queue 0 eligible-idx)
                                     (subvec queue (inc eligible-idx))))]
          [job remaining])
        [nil queue]))))

(defn remove-from-queue
  "Remove entry by job-id. Returns new queue."
  [queue job-id]
  (vec (remove #(= (:job-id %) job-id) queue)))

(defn queue-size
  "Returns count of jobs in queue."
  [queue]
  (count queue))

(defn find-in-queue
  "Returns the job entry with matching job-id or nil."
  [queue job-id]
  (first (filter #(= (:job-id %) job-id) queue)))
