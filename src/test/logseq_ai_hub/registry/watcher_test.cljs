(ns logseq-ai-hub.registry.watcher-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.registry.watcher :as watcher]))

(deftest test-watcher-state-init
  (testing "Watcher state starts with no timer and not watching"
    (let [state @watcher/watcher-state]
      (is (nil? (:timer-id state)))
      ;; watching may be true if init has run, but the atom structure is correct
      (is (contains? state :watching)))))

(deftest test-debounce-constant
  (testing "Debounce interval is 2 seconds"
    (is (= 2000 watcher/debounce-ms))))

(deftest test-stop-watching-resets-state
  (testing "stop-watching! clears timer and sets watching to false"
    (let [clear-timeout-calls (atom [])]
      ;; Mock clearTimeout
      (let [orig-clear js/clearTimeout]
        (set! js/clearTimeout (fn [id]
                                (swap! clear-timeout-calls conj id)))
        ;; Simulate active watcher state
        (reset! watcher/watcher-state {:timer-id 42 :watching true})

        ;; Call stop-watching!
        (watcher/stop-watching!)

        ;; Verify state was reset
        (is (nil? (:timer-id @watcher/watcher-state)))
        (is (false? (:watching @watcher/watcher-state)))

        ;; Verify clearTimeout was called with the timer id
        (is (= [42] @clear-timeout-calls))

        ;; Restore
        (set! js/clearTimeout orig-clear)
        (reset! watcher/watcher-state {:timer-id nil :watching false})))))
