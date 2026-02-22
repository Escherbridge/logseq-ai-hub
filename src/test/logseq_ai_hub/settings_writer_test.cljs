(ns logseq-ai-hub.settings-writer-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.settings-writer :as sw]))

(defn setup! []
  (sw/reset-queue!))

(deftest test-single-write
  (setup!)
  (testing "single write resolves successfully"
    (async done
      (let [calls (atom [])]
        (-> (sw/queue-settings-write!
              (fn []
                (swap! calls conj :write-1)
                (js/Promise.resolve nil)))
            (.then (fn [_]
                     (is (= [:write-1] @calls))
                     (done))))))))

(deftest test-serialized-order
  (setup!)
  (testing "writes execute in order"
    (async done
      (let [order (atom [])]
        ;; Queue 3 writes — they should execute sequentially
        (sw/queue-settings-write!
          (fn []
            (js/Promise. (fn [resolve _]
                           (js/setTimeout (fn []
                                            (swap! order conj :first)
                                            (resolve nil))
                                          10)))))
        (sw/queue-settings-write!
          (fn []
            (swap! order conj :second)
            (js/Promise.resolve nil)))
        (-> (sw/queue-settings-write!
              (fn []
                (swap! order conj :third)
                (js/Promise.resolve nil)))
            (.then (fn [_]
                     (is (= [:first :second :third] @order))
                     (done))))))))

(deftest test-error-recovery
  (setup!)
  (testing "error in one write doesn't break the queue"
    (async done
      (let [calls (atom [])]
        (sw/queue-settings-write!
          (fn []
            (swap! calls conj :before-error)
            (js/Promise.resolve nil)))
        (sw/queue-settings-write!
          (fn []
            (throw (js/Error. "Simulated error"))))
        (-> (sw/queue-settings-write!
              (fn []
                (swap! calls conj :after-error)
                (js/Promise.resolve nil)))
            (.then (fn [_]
                     (is (= [:before-error :after-error] @calls))
                     (done))))))))

(deftest test-promise-rejection-recovery
  (setup!)
  (testing "rejected promise doesn't break the queue"
    (async done
      (let [calls (atom [])]
        (sw/queue-settings-write!
          (fn []
            (js/Promise.reject (js/Error. "Rejected"))))
        (-> (sw/queue-settings-write!
              (fn []
                (swap! calls conj :recovered)
                (js/Promise.resolve nil)))
            (.then (fn [_]
                     (is (= [:recovered] @calls))
                     (done))))))))

(deftest test-synchronous-write
  (setup!)
  (testing "synchronous write functions work"
    (async done
      (let [called? (atom false)]
        (-> (sw/queue-settings-write!
              (fn []
                (reset! called? true)))
            (.then (fn [_]
                     (is @called?)
                     (done))))))))
