(ns logseq-ai-hub.messaging-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.messaging :as messaging]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def mock-es-instances (atom []))
(def fetch-calls (atom []))
(def created-pages (atom []))
(def appended-blocks (atom []))

(defn setup-mocks!
  "Resets all mock state and installs global mocks for EventSource, fetch, and logseq."
  []
  ;; Reset mock tracking
  (reset! mock-es-instances [])
  (reset! fetch-calls [])
  (reset! created-pages [])
  (reset! appended-blocks [])

  ;; Reset messaging module state
  (reset! messaging/state
    {:event-source nil
     :server-url nil
     :api-token nil
     :connected? false
     :message-handlers []})

  ;; Mock EventSource constructor — returns a plain JS object.
  ;; When `new F()` returns an object, JS uses that object instead of `this`.
  (set! js/EventSource
    (fn [url]
      (let [handlers (atom {})
            obj #js {:url url
                     :readyState 1
                     :onerror nil}]
        (set! (.-addEventListener obj)
              (fn [type handler]
                (swap! handlers update type (fnil conj []) handler)))
        (set! (.-close obj)
              (fn [] (set! (.-readyState obj) 2)))
        (set! (.-_handlers obj) handlers)
        (swap! mock-es-instances conj obj)
        obj)))

  ;; Mock fetch
  (set! js/fetch
    (fn [url opts]
      (swap! fetch-calls conj {:url url :opts (js->clj opts :keywordize-keys true)})
      (js/Promise.resolve
        #js {:ok true
             :json (fn [] (js/Promise.resolve
                            #js {:success true
                                 :data #js {:messageId 1 :externalId "ext-123"}}))})))

  ;; Mock logseq
  (set! js/logseq
    #js {:Editor #js {:createPage
                      (fn [name _props _opts]
                        (swap! created-pages conj name)
                        (js/Promise.resolve #js {:name name}))
                      :appendBlockInPage
                      (fn [name content]
                        (swap! appended-blocks conj {:page name :content content})
                        (js/Promise.resolve #js {:uuid "mock-uuid"}))}
         :settings #js {"webhookServerUrl" "http://localhost:3000"
                        "pluginApiToken" "test-token"}}))

;; ---------------------------------------------------------------------------
;; Pure Function Tests
;; ---------------------------------------------------------------------------

(deftest test-parse-sse-data
  (setup-mocks!)
  (testing "parses valid JSON into keywordized map"
    (let [result (messaging/parse-sse-data "{\"type\":\"new_message\",\"message\":{\"id\":1}}")]
      (is (= "new_message" (:type result)))
      (is (= 1 (get-in result [:message :id])))))

  (testing "returns nil for invalid JSON"
    (is (nil? (messaging/parse-sse-data "not json"))))

  (testing "returns nil for empty string"
    (is (nil? (messaging/parse-sse-data "")))))

(deftest test-format-timestamp
  (setup-mocks!)
  (testing "formats ISO timestamp into readable local time"
    (let [result (messaging/format-timestamp "2026-02-11T14:30:00.000Z")]
      (is (string? result))
      (is (.includes result "2026")))))

(deftest test-format-message-block
  (setup-mocks!)
  (testing "formats message with all fields"
    (let [msg {:id 42
               :content "Hello from WhatsApp!"
               :platform "whatsapp"
               :direction "incoming"
               :contact {:id "whatsapp:15551234567"
                         :displayName "John Doe"
                         :platformUserId "15551234567"}
               :createdAt "2026-02-11T14:30:00.000Z"}
          result (messaging/format-message-block msg)]
      (is (.includes result "John Doe"))
      (is (.includes result "WhatsApp"))
      (is (.includes result "Hello from WhatsApp!"))
      (is (.includes result "platform:: whatsapp"))
      (is (.includes result "sender:: whatsapp:15551234567"))
      (is (.includes result "message-id:: 42"))
      (is (.includes result "direction:: incoming"))))

  (testing "falls back to platformUserId when displayName is nil"
    (let [msg {:id 1
               :content "Test"
               :platform "telegram"
               :direction "incoming"
               :contact {:id "telegram:999"
                         :displayName nil
                         :platformUserId "999"}
               :createdAt "2026-01-01T00:00:00.000Z"}
          result (messaging/format-message-block msg)]
      (is (.includes result "999"))
      (is (.includes result "Telegram")))))

(deftest test-page-name-for-contact
  (setup-mocks!)
  (testing "generates correct page name"
    (is (= "AI Hub/WhatsApp/John Doe"
           (messaging/page-name-for-contact "whatsapp" "John Doe"))))

  (testing "uses Unknown for nil display name"
    (is (= "AI Hub/Telegram/Unknown"
           (messaging/page-name-for-contact "telegram" nil)))))

;; ---------------------------------------------------------------------------
;; Handler Registration Tests
;; ---------------------------------------------------------------------------

(deftest test-on-message
  (setup-mocks!)
  (testing "registers message handlers in state"
    (let [h1 (fn [_] nil)
          h2 (fn [_] nil)]
      (messaging/on-message h1)
      (messaging/on-message h2)
      (is (= 2 (count (:message-handlers @messaging/state)))))))

;; ---------------------------------------------------------------------------
;; Connection Tests
;; ---------------------------------------------------------------------------

(deftest test-connect-creates-event-source
  (setup-mocks!)
  (testing "connect! creates EventSource and updates state"
    (messaging/connect! "http://localhost:3000" "test-token")
    (is (:connected? @messaging/state))
    (is (some? (:event-source @messaging/state)))
    (is (= "http://localhost:3000" (:server-url @messaging/state)))
    (is (= "test-token" (:api-token @messaging/state)))
    (is (= 1 (count @mock-es-instances)))
    (let [es (first @mock-es-instances)]
      (is (= "http://localhost:3000/events?token=test-token" (.-url es))))))

(deftest test-connect-ignores-blank-params
  (setup-mocks!)
  (testing "connect! returns nil for blank server URL"
    (is (nil? (messaging/connect! "" "token"))))
  (testing "connect! returns nil for blank token"
    (is (nil? (messaging/connect! "http://localhost" ""))))
  (testing "connect! returns nil for nil params"
    (is (nil? (messaging/connect! nil nil)))))

(deftest test-disconnect-closes-event-source
  (setup-mocks!)
  (testing "disconnect! closes EventSource and resets state"
    (messaging/connect! "http://localhost:3000" "test-token")
    (let [es (:event-source @messaging/state)]
      (messaging/disconnect!)
      (is (not (:connected? @messaging/state)))
      (is (nil? (:event-source @messaging/state)))
      (is (= 2 (.-readyState es)))))) ;; CLOSED

;; ---------------------------------------------------------------------------
;; Send Message Tests
;; ---------------------------------------------------------------------------

(deftest test-send-message
  (setup-mocks!)
  (testing "send-message! calls fetch with correct params"
    (async done
      (swap! messaging/state assoc
             :server-url "http://localhost:3000"
             :api-token "test-token")
      (-> (messaging/send-message! "whatsapp" "15551234567" "Hello!")
          (.then (fn [result]
                   (is (:success result))
                   (let [{:keys [url opts]} (first @fetch-calls)]
                     (is (= "http://localhost:3000/api/send" url))
                     (is (= "POST" (:method opts)))
                     (is (= "Bearer test-token" (get-in opts [:headers :Authorization]))))
                   (done)))))))

(deftest test-send-message-not-connected
  (setup-mocks!)
  (testing "send-message! rejects when not connected"
    (async done
      (-> (messaging/send-message! "whatsapp" "15551234567" "Hello!")
          (.then (fn [_] (is false "Should have rejected") (done)))
          (.catch (fn [err]
                    (is (.includes (.-message err) "Not connected"))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Ingest Message Tests
;; ---------------------------------------------------------------------------

(deftest test-ingest-message
  (setup-mocks!)
  (testing "ingest-message! creates page and appends block"
    (async done
      (let [msg {:id 42
                 :content "Hello!"
                 :platform "whatsapp"
                 :direction "incoming"
                 :contact {:id "whatsapp:15551234567"
                           :displayName "John Doe"
                           :platformUserId "15551234567"}
                 :createdAt "2026-02-11T14:30:00.000Z"}]
        (-> (messaging/ingest-message! msg)
            (.then (fn [_]
                     (is (some #(= "AI Hub/WhatsApp/John Doe" %) @created-pages))
                     (is (= 1 (count @appended-blocks)))
                     (let [{:keys [page content]} (first @appended-blocks)]
                       (is (= "AI Hub/WhatsApp/John Doe" page))
                       (is (.includes content "Hello!"))
                       (is (.includes content "platform:: whatsapp")))
                     (done))))))))

;; ---------------------------------------------------------------------------
;; Init Tests
;; ---------------------------------------------------------------------------

(deftest test-init-connects-when-settings-present
  (setup-mocks!)
  (testing "init! connects SSE and registers ingest handler when settings are present"
    (messaging/init!)
    (is (:connected? @messaging/state))
    (is (= "http://localhost:3000" (:server-url @messaging/state)))
    (is (= 1 (count (:message-handlers @messaging/state))))
    (is (= 1 (count @mock-es-instances)))))

(deftest test-init-skips-when-settings-missing
  (setup-mocks!)
  (testing "init! does nothing when webhookServerUrl is blank"
    (aset js/logseq "settings" #js {"webhookServerUrl" "" "pluginApiToken" "token"})
    (messaging/init!)
    (is (not (:connected? @messaging/state)))
    (is (= 0 (count @mock-es-instances)))))
