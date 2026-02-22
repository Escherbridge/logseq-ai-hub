(ns logseq-ai-hub.mcp.on-demand-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.mcp.on-demand :as on-demand]
            [logseq-ai-hub.mcp.client :as client]))

(defn- setup-mock-logseq!
  "Sets up minimal logseq mock for page reader."
  [page-blocks-map]
  (set! js/logseq
        #js {:Editor #js {:getPageBlocksTree
                          (fn [page-name]
                            (if-let [blocks (get page-blocks-map page-name)]
                              (js/Promise.resolve (clj->js blocks))
                              (js/Promise.resolve nil)))}
             :settings #js {}
             :DB #js {:datascriptQuery (fn [_] #js [])}}))

(defn- make-mock-transport [responses-atom]
  {:type :mock
   :send! (fn [message]
            (js/Promise.
             (fn [resolve reject]
               (let [method (:method message)
                     id (:id message)
                     response (get @responses-atom method)]
                 (if response
                   (resolve (assoc response :id id))
                   (reject (js/Error. (str "No mock: " method))))))))
   :close! (fn [] nil)})

(deftest connect-from-page-test
  (testing "connect-from-page! reads page config and connects server via client"
    (async done
      (reset! client/servers {})
      (setup-mock-logseq!
        {"MCP/test-server"
         [{:content "mcp-url:: http://test.example.com/mcp"}]})

      (let [responses (atom {"initialize" {:result {:protocolVersion "2025-03-26"
                                                     :capabilities {:tools {}}
                                                     :serverInfo {:name "Test"}}}
                             "notifications/initialized" {:result {}}
                             "tools/list" {:result {:tools []}}})]
        ;; Inject mock transport factory by providing :make-transport-fn in the
        ;; server config. We do this by wrapping connect-from-page! to intercept
        ;; and merge the factory before connect-server! is called.
        (let [original-connect client/connect-server!]
          (set! client/connect-server!
                (fn [config]
                  (original-connect
                    (assoc config :make-transport-fn
                           (fn [_] (make-mock-transport responses))))))
          (-> (on-demand/connect-from-page! "MCP/test-server")
              (.then (fn [server-id]
                       (is (= "test-server" server-id))
                       (is (= :connected (client/server-status "test-server")))
                       (set! client/connect-server! original-connect)
                       (done)))
              (.catch (fn [err]
                        (set! client/connect-server! original-connect)
                        (is false (str "Should have connected: " err))
                        (done)))))))))

(deftest connect-from-page-missing-test
  (testing "connect-from-page! rejects for missing page"
    (async done
      (reset! client/servers {})
      (setup-mock-logseq! {})
      (-> (on-demand/connect-from-page! "MCP/nonexistent")
          (.then (fn [_]
                   (is false "Should have rejected")
                   (done)))
          (.catch (fn [err]
                    (is (some? err))
                    (done)))))))

(deftest disconnect-servers-test
  (testing "disconnect-servers! disconnects all provided IDs"
    (let [close-calls (atom [])]
      (reset! client/servers
              {"s1" {:id "s1" :status :connected
                     :transport {:close! (fn [] (swap! close-calls conj "s1"))}}
               "s2" {:id "s2" :status :connected
                     :transport {:close! (fn [] (swap! close-calls conj "s2"))}}})
      (on-demand/disconnect-servers! ["s1" "s2"])
      (is (= ["s1" "s2"] @close-calls)))))

(deftest collect-tools-test
  (testing "collect-tools fetches tools from all servers"
    (async done
      (reset! client/servers
              {"s1" {:id "s1" :status :connected
                     :tools [{:name "tool-a"}]
                     :transport {:send! (fn [_] (js/Promise.resolve {}))}}
               "s2" {:id "s2" :status :connected
                     :tools [{:name "tool-b"}]
                     :transport {:send! (fn [_] (js/Promise.resolve {}))}}})
      (-> (on-demand/collect-tools ["s1" "s2"])
          (.then (fn [results]
                   (is (= 2 (count results)))
                   (is (= "s1" (:server-id (first results))))
                   (is (= [{:name "tool-a"}] (:tools (first results))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest collect-tools-empty-test
  (testing "collect-tools returns empty for no servers"
    (async done
      (-> (on-demand/collect-tools [])
          (.then (fn [results]
                   (is (= [] results))
                   (done)))))))
