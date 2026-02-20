(ns logseq-ai-hub.job-runner.cron
  (:require [clojure.string :as str]))

(def field-ranges
  {:minute [0 59]
   :hour [0 23]
   :day-of-month [1 31]
   :month [1 12]
   :day-of-week [0 6]})

(defn- parse-number [s]
  (try
    (let [n (js/parseInt s 10)]
      (if (js/isNaN n) nil n))
    (catch :default _ nil)))

(defn- expand-range [start end]
  (when (and start end (<= start end))
    (vec (range start (inc end)))))

(defn- expand-step [values step]
  (when (and (seq values) (pos? step))
    (vec (filter #(zero? (mod % step)) values))))

(defn- parse-field-part [part [min-val max-val]]
  (cond
    ;; Wildcard with step: */5
    (str/starts-with? part "*/")
    (let [step (parse-number (subs part 2))]
      (when (and step (pos? step))
        (vec (range 0 (inc max-val) step))))

    ;; Range with step: 1-10/2
    (and (str/includes? part "/") (str/includes? part "-"))
    (let [[range-part step-part] (str/split part #"/")
          [start-str end-str] (str/split range-part #"-")
          start (parse-number start-str)
          end (parse-number end-str)
          step (parse-number step-part)]
      (when (and start end step
                 (>= start min-val) (<= end max-val)
                 (<= start end)
                 (pos? step))
        (vec (range start (inc end) step))))

    ;; Range: 1-5
    (str/includes? part "-")
    (let [[start-str end-str] (str/split part #"-")]
      (when (= 2 (count (str/split part #"-")))
        (let [start (parse-number start-str)
              end (parse-number end-str)]
          (when (and start end
                     (>= start min-val) (<= end max-val)
                     (<= start end))
            (vec (range start (inc end)))))))

    ;; Wildcard: *
    (= part "*")
    (vec (range min-val (inc max-val)))

    ;; Specific value: 5
    :else
    (let [n (parse-number part)]
      (when (and n (>= n min-val) (<= n max-val))
        [n]))))

(defn- parse-field [field-str [min-val max-val]]
  (when field-str
    (let [parts (str/split field-str #",")
          parsed-parts (map #(parse-field-part % [min-val max-val]) parts)]
      (when (every? some? parsed-parts)
        (vec (sort (distinct (apply concat parsed-parts))))))))

(defn parse-cron
  "Parses a cron expression string into a structured map.
  Returns nil for invalid expressions.

  Example: (parse-cron \"30 9 * * *\")
  => {:minute [30] :hour [9] :day-of-month [1..31] :month [1..12] :day-of-week [0..6]}"
  [expr]
  (when expr
    (let [fields (str/split (str/trim expr) #"\s+")]
      (when (= 5 (count fields))
        (let [[minute hour day-of-month month day-of-week] fields
              parsed {:minute (parse-field minute (:minute field-ranges))
                      :hour (parse-field hour (:hour field-ranges))
                      :day-of-month (parse-field day-of-month (:day-of-month field-ranges))
                      :month (parse-field month (:month field-ranges))
                      :day-of-week (parse-field day-of-week (:day-of-week field-ranges))}]
          (when (every? some? (vals parsed))
            parsed))))))

(defn valid-cron?
  "Returns true if the expression is a valid cron expression."
  [expr]
  (some? (parse-cron expr)))

(defn matches-now?
  "Returns true if the given JS Date matches the parsed cron expression.

  Example:
    (matches-now? (parse-cron \"30 9 * * *\") (js/Date. 2026 1 19 9 30 0))
    => true"
  [parsed-cron js-date]
  (when (and parsed-cron js-date)
    (let [minute (.getMinutes js-date)
          hour (.getHours js-date)
          day-of-month (.getDate js-date)
          month (inc (.getMonth js-date))  ;; JS months are 0-based
          day-of-week (.getDay js-date)]   ;; 0=Sunday, 6=Saturday
      (and (some #(= minute %) (:minute parsed-cron))
           (some #(= hour %) (:hour parsed-cron))
           (some #(= day-of-month %) (:day-of-month parsed-cron))
           (some #(= month %) (:month parsed-cron))
           (some #(= day-of-week %) (:day-of-week parsed-cron))))))
