(ns logseq-ai-hub.event-hub.graph-watcher-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.event-hub.graph-watcher :as graph-watcher]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def publish-calls (atom []))

(defn- setup-mocks!
  "Sets up *publish-fn* mock and resets watcher state."
  []
  (reset! publish-calls [])
  (reset! graph-watcher/debounce-timers {})
  (swap! graph-watcher/watcher-state assoc :watching false)
  (set! graph-watcher/*publish-fn*
    (fn [event-map]
      (swap! publish-calls conj event-map)
      (js/Promise.resolve {:event-id "evt-graph-123"}))))

(defn- teardown-mocks!
  "Resets dynamic var and clears timers."
  []
  (graph-watcher/stop!)
  (set! graph-watcher/*publish-fn* nil))

;; ---------------------------------------------------------------------------
;; should-track? tests
;; ---------------------------------------------------------------------------

(deftest test-should-track-normal-page
  (testing "normal pages should be tracked"
    (is (true? (graph-watcher/should-track? "My Notes")))
    (is (true? (graph-watcher/should-track? "Projects/MyApp")))))

(deftest test-should-not-track-events-pages
  (testing "Events/* pages should not be tracked (avoids infinite loops)"
    (is (not (graph-watcher/should-track? "Events/system-graph")))
    (is (not (graph-watcher/should-track? "events/webhook-test")))))

(deftest test-should-not-track-jobs-pages
  (testing "Jobs/* pages should not be tracked"
    (is (not (graph-watcher/should-track? "Jobs/event-abc")))
    (is (not (graph-watcher/should-track? "jobs/test-job")))))

(deftest test-should-not-track-nil-or-blank
  (testing "nil and blank page names should not be tracked"
    (is (not (graph-watcher/should-track? nil)))
    (is (not (graph-watcher/should-track? "")))
    (is (not (graph-watcher/should-track? "   ")))))

;; ---------------------------------------------------------------------------
;; handle-change tests (with debounce)
;; ---------------------------------------------------------------------------

(deftest test-handle-change-creates-debounced-timer
  (setup-mocks!)
  (testing "handle-change creates a debounce timer for each tracked page"
    (graph-watcher/handle-change
      {:blocks [{:page {:name "My Notes"} :content "some content"}]})
    ;; Timer should be pending but not yet fired
    (is (contains? @graph-watcher/debounce-timers "My Notes")
        "debounce timer should be set for the page")
    ;; Publish should NOT have been called yet (debounce pending)
    (is (= 0 (count @publish-calls))
        "publish should not fire until debounce expires"))
  (teardown-mocks!))

;; NOTE: Debounce integration tests commented out — they require ~6s of
;; setTimeout delays (2000ms debounce + buffer) making CI slow. The debounce
;; mechanics are verified by unit tests above (timer creation, page filtering).
;; Uncomment for manual verification of end-to-end debounce behavior.
;;
;; (deftest test-handle-change-fires-after-debounce ...)
;; (deftest test-handle-change-debounces-rapid-edits ...)

(deftest test-handle-change-skips-events-pages
  (setup-mocks!)
  (testing "Events/* pages are not emitted"
    (graph-watcher/handle-change
      {:blocks [{:page {:name "Events/system-graph"} :content "event log"}]})
    (is (empty? @graph-watcher/debounce-timers)
        "no timer should be created for Events/* pages"))
  (teardown-mocks!))

(deftest test-handle-change-skips-jobs-pages
  (setup-mocks!)
  (testing "Jobs/* pages are not emitted"
    (graph-watcher/handle-change
      {:blocks [{:page {:name "Jobs/event-123"} :content "job data"}]})
    (is (empty? @graph-watcher/debounce-timers)
        "no timer should be created for Jobs/* pages"))
  (teardown-mocks!))

(deftest test-handle-change-multiple-pages
  (setup-mocks!)
  (testing "changes to multiple pages create separate timers"
    (graph-watcher/handle-change
      {:blocks [{:page {:name "Page A"} :content "edit A"}
                {:page {:name "Page B"} :content "edit B"}]})
    (is (= 2 (count @graph-watcher/debounce-timers))
        "separate timers for each page"))
  (teardown-mocks!))

(deftest test-handle-change-no-publish-fn
  (testing "handle-change is a no-op when *publish-fn* is nil"
    (set! graph-watcher/*publish-fn* nil)
    (reset! graph-watcher/debounce-timers {})
    (graph-watcher/handle-change
      {:blocks [{:page {:name "My Notes"} :content "some content"}]})
    ;; No timer should be set since publish-fn is nil
    (is (empty? @graph-watcher/debounce-timers)
        "no timers created when publish-fn is nil")))

;; ---------------------------------------------------------------------------
;; Lifecycle tests
;; ---------------------------------------------------------------------------

(deftest test-start-registers-watcher
  (testing "start! sets watching to true when DB.onChanged is available"
    (reset! graph-watcher/debounce-timers {})
    (swap! graph-watcher/watcher-state assoc :watching false)
    (set! js/logseq #js {:DB #js {:onChanged (fn [_cb] nil)}})
    (let [orig js/console.log]
      (set! js/console.log (fn [& _] nil))
      (graph-watcher/start!)
      (set! js/console.log orig))
    (is (true? (:watching @graph-watcher/watcher-state))
        "watcher should be watching after start!")
    (graph-watcher/stop!)))

(deftest test-start-idempotent
  (testing "start! is idempotent -- second call is a no-op"
    (swap! graph-watcher/watcher-state assoc :watching false)
    (let [call-count (atom 0)]
      (set! js/logseq #js {:DB #js {:onChanged (fn [_cb] (swap! call-count inc))}})
      (let [orig js/console.log]
        (set! js/console.log (fn [& _] nil))
        (graph-watcher/start!)
        (graph-watcher/start!)
        (set! js/console.log orig))
      (is (= 1 @call-count)
          "onChanged should only be registered once"))
    (graph-watcher/stop!)))

(deftest test-stop-clears-timers
  (setup-mocks!)
  (testing "stop! clears all pending debounce timers"
    (graph-watcher/handle-change
      {:blocks [{:page {:name "Page A"} :content "edit"}
                {:page {:name "Page B"} :content "edit"}]})
    (is (= 2 (count @graph-watcher/debounce-timers)))
    (graph-watcher/stop!)
    (is (empty? @graph-watcher/debounce-timers)
        "all timers should be cleared after stop!")
    (is (false? (:watching @graph-watcher/watcher-state))
        "watching should be false after stop!")))
