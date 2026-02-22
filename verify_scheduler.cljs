(ns verify-scheduler
  (:require [logseq-ai-hub.job-runner.scheduler :as scheduler]
            [logseq-ai-hub.job-runner.cron :as cron]))

;; Simple verification that the namespace loads and basic functions exist
(println "Scheduler namespace loaded successfully")
(println "State:" @scheduler/scheduler-state)
(println "Register test:" (scheduler/register-schedule! "Jobs/Test" "0 9 * * *"))
(println "List schedules:" (scheduler/list-schedules))
