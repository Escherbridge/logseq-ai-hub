(ns logseq-ai-hub.job-runner.cron-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.job-runner.cron :as cron]))

(deftest parse-cron-test
  (testing "wildcard - every minute"
    (let [result (cron/parse-cron "* * * * *")]
      (is (= (range 0 60) (:minute result)))
      (is (= (range 0 24) (:hour result)))
      (is (= (range 1 32) (:day-of-month result)))
      (is (= (range 1 13) (:month result)))
      (is (= (range 0 7) (:day-of-week result)))))

  (testing "specific values"
    (let [result (cron/parse-cron "30 9 15 6 1")]
      (is (= [30] (:minute result)))
      (is (= [9] (:hour result)))
      (is (= [15] (:day-of-month result)))
      (is (= [6] (:month result)))
      (is (= [1] (:day-of-week result)))))

  (testing "ranges"
    (let [result (cron/parse-cron "0 9-17 * * *")]
      (is (= [0] (:minute result)))
      (is (= [9 10 11 12 13 14 15 16 17] (:hour result)))
      (is (= (range 1 32) (:day-of-month result)))
      (is (= (range 1 13) (:month result)))
      (is (= (range 0 7) (:day-of-week result)))))

  (testing "lists"
    (let [result (cron/parse-cron "0 9,12,18 * * *")]
      (is (= [0] (:minute result)))
      (is (= [9 12 18] (:hour result)))))

  (testing "step values - every 15 minutes"
    (let [result (cron/parse-cron "*/15 * * * *")]
      (is (= [0 15 30 45] (:minute result)))
      (is (= (range 0 24) (:hour result)))))

  (testing "step values - every 5 minutes"
    (let [result (cron/parse-cron "*/5 * * * *")]
      (is (= [0 5 10 15 20 25 30 35 40 45 50 55] (:minute result)))))

  (testing "step values - every 2 hours"
    (let [result (cron/parse-cron "0 */2 * * *")]
      (is (= [0] (:minute result)))
      (is (= [0 2 4 6 8 10 12 14 16 18 20 22] (:hour result)))))

  (testing "range with step - every 2 from 1-10"
    (let [result (cron/parse-cron "1-10/2 * * * *")]
      (is (= [1 3 5 7 9] (:minute result)))))

  (testing "range with step - every 3 hours from 9-17"
    (let [result (cron/parse-cron "0 9-17/3 * * *")]
      (is (= [0] (:minute result)))
      (is (= [9 12 15] (:hour result)))))

  (testing "weekdays - Monday to Friday"
    (let [result (cron/parse-cron "0 9 * * 1-5")]
      (is (= [0] (:minute result)))
      (is (= [9] (:hour result)))
      (is (= [1 2 3 4 5] (:day-of-week result)))))

  (testing "invalid - too few fields"
    (is (nil? (cron/parse-cron "* * *"))))

  (testing "invalid - too many fields"
    (is (nil? (cron/parse-cron "* * * * * *"))))

  (testing "invalid - out of range minute"
    (is (nil? (cron/parse-cron "60 * * * *"))))

  (testing "invalid - out of range hour"
    (is (nil? (cron/parse-cron "* 24 * * *"))))

  (testing "invalid - out of range day-of-month"
    (is (nil? (cron/parse-cron "* * 32 * *"))))

  (testing "invalid - zero day-of-month"
    (is (nil? (cron/parse-cron "* * 0 * *"))))

  (testing "invalid - out of range month"
    (is (nil? (cron/parse-cron "* * * 13 *"))))

  (testing "invalid - zero month"
    (is (nil? (cron/parse-cron "* * * 0 *"))))

  (testing "invalid - out of range day-of-week"
    (is (nil? (cron/parse-cron "* * * * 7"))))

  (testing "invalid - negative value"
    (is (nil? (cron/parse-cron "-1 * * * *"))))

  (testing "invalid - malformed range"
    (is (nil? (cron/parse-cron "1-5-10 * * * *"))))

  (testing "invalid - non-numeric value"
    (is (nil? (cron/parse-cron "foo * * * *")))))

(deftest valid-cron-test
  (testing "valid expressions"
    (is (cron/valid-cron? "* * * * *"))
    (is (cron/valid-cron? "30 9 * * *"))
    (is (cron/valid-cron? "0 9-17 * * *"))
    (is (cron/valid-cron? "*/15 * * * *"))
    (is (cron/valid-cron? "0 9 * * 1-5")))

  (testing "invalid expressions"
    (is (not (cron/valid-cron? "* * *")))
    (is (not (cron/valid-cron? "60 * * * *")))
    (is (not (cron/valid-cron? "foo bar baz qux quux")))))

(deftest matches-now-test
  (testing "every minute matches any time"
    (let [parsed (cron/parse-cron "* * * * *")
          date (js/Date. 2026 1 19 10 30 0)]
      (is (cron/matches-now? parsed date))))

  (testing "specific time matches exactly"
    (let [parsed (cron/parse-cron "30 9 * * *")
          ;; JS Date: month is 0-based, so 1 = February
          date (js/Date. 2026 1 19 9 30 0)]
      (is (cron/matches-now? parsed date))))

  (testing "specific time does not match wrong minute"
    (let [parsed (cron/parse-cron "30 9 * * *")
          date (js/Date. 2026 1 19 9 31 0)]
      (is (not (cron/matches-now? parsed date)))))

  (testing "specific time does not match wrong hour"
    (let [parsed (cron/parse-cron "30 9 * * *")
          date (js/Date. 2026 1 19 10 30 0)]
      (is (not (cron/matches-now? parsed date)))))

  (testing "hour range matches within range"
    (let [parsed (cron/parse-cron "0 9-17 * * *")
          date (js/Date. 2026 1 19 12 0 0)]
      (is (cron/matches-now? parsed date))))

  (testing "hour range does not match outside range"
    (let [parsed (cron/parse-cron "0 9-17 * * *")
          date (js/Date. 2026 1 19 8 0 0)]
      (is (not (cron/matches-now? parsed date)))))

  (testing "list matches any value in list"
    (let [parsed (cron/parse-cron "0 9,12,18 * * *")
          date1 (js/Date. 2026 1 19 9 0 0)
          date2 (js/Date. 2026 1 19 12 0 0)
          date3 (js/Date. 2026 1 19 18 0 0)]
      (is (cron/matches-now? parsed date1))
      (is (cron/matches-now? parsed date2))
      (is (cron/matches-now? parsed date3))))

  (testing "list does not match value not in list"
    (let [parsed (cron/parse-cron "0 9,12,18 * * *")
          date (js/Date. 2026 1 19 10 0 0)]
      (is (not (cron/matches-now? parsed date)))))

  (testing "step values match at intervals"
    (let [parsed (cron/parse-cron "*/15 * * * *")
          date1 (js/Date. 2026 1 19 10 0 0)
          date2 (js/Date. 2026 1 19 10 15 0)
          date3 (js/Date. 2026 1 19 10 30 0)
          date4 (js/Date. 2026 1 19 10 45 0)]
      (is (cron/matches-now? parsed date1))
      (is (cron/matches-now? parsed date2))
      (is (cron/matches-now? parsed date3))
      (is (cron/matches-now? parsed date4))))

  (testing "step values do not match off intervals"
    (let [parsed (cron/parse-cron "*/15 * * * *")
          date (js/Date. 2026 1 19 10 7 0)]
      (is (not (cron/matches-now? parsed date)))))

  (testing "weekday range - Monday (1) matches"
    (let [parsed (cron/parse-cron "0 9 * * 1-5")
          ;; February 17, 2026 is a Tuesday (day 2)
          date (js/Date. 2026 1 17 9 0 0)]
      (is (cron/matches-now? parsed date))))

  (testing "weekday range - Saturday (6) does not match"
    (let [parsed (cron/parse-cron "0 9 * * 1-5")
          ;; February 21, 2026 is a Saturday (day 6)
          date (js/Date. 2026 1 21 9 0 0)]
      (is (not (cron/matches-now? parsed date)))))

  (testing "weekday range - Sunday (0) does not match"
    (let [parsed (cron/parse-cron "0 9 * * 1-5")
          ;; February 22, 2026 is a Sunday (day 0)
          date (js/Date. 2026 1 22 9 0 0)]
      (is (not (cron/matches-now? parsed date)))))

  (testing "specific day of month matches"
    (let [parsed (cron/parse-cron "0 9 15 * *")
          date (js/Date. 2026 1 15 9 0 0)]
      (is (cron/matches-now? parsed date))))

  (testing "specific day of month does not match wrong day"
    (let [parsed (cron/parse-cron "0 9 15 * *")
          date (js/Date. 2026 1 16 9 0 0)]
      (is (not (cron/matches-now? parsed date)))))

  (testing "specific month matches"
    (let [parsed (cron/parse-cron "0 9 * 6 *")
          ;; JS Date: month 5 = June (0-based), cron month 6 = June (1-based)
          date (js/Date. 2026 5 15 9 0 0)]
      (is (cron/matches-now? parsed date))))

  (testing "specific month does not match wrong month"
    (let [parsed (cron/parse-cron "0 9 * 6 *")
          ;; JS Date: month 6 = July (0-based)
          date (js/Date. 2026 6 15 9 0 0)]
      (is (not (cron/matches-now? parsed date))))))
