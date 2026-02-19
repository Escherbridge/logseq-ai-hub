(ns logseq-ai-hub.messaging
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce state
  (atom {:event-source nil
         :server-url nil
         :api-token nil
         :connected? false
         :message-handlers []}))

;; ---------------------------------------------------------------------------
;; Pure Helpers (public for testability)
;; ---------------------------------------------------------------------------

(defn- pad-zero [n]
  (if (< n 10) (str "0" n) (str n)))

(defn format-timestamp
  "Formats an ISO-8601 timestamp into 'YYYY-MM-DD HH:MM' local time."
  [iso-string]
  (let [d (js/Date. iso-string)]
    (str (.getFullYear d) "-"
         (pad-zero (inc (.getMonth d))) "-"
         (pad-zero (.getDate d)) " "
         (pad-zero (.getHours d)) ":"
         (pad-zero (.getMinutes d)))))

(defn parse-sse-data
  "Parses a raw SSE data string (JSON) into a keywordized map.
   Returns nil on parse failure."
  [raw-data]
  (try
    (js->clj (js/JSON.parse raw-data) :keywordize-keys true)
    (catch :default _
      nil)))

(defn- build-sse-url [server-url api-token]
  (str server-url "/events?token=" (js/encodeURIComponent api-token)))

(defn- platform-display [platform]
  (case platform
    "whatsapp" "WhatsApp"
    "telegram" "Telegram"
    platform))

(defn page-name-for-contact
  "Returns the Logseq page name for a given platform and contact display name."
  [platform display-name]
  (str "AI Hub/" (platform-display platform) "/" (or display-name "Unknown")))

(defn format-message-block
  "Formats a message map into a Logseq block content string with properties."
  [message]
  (let [{:keys [content platform direction contact createdAt id]} message
        display-name (or (:displayName contact) (:platformUserId contact) "Unknown")
        sender-id (or (:id contact) "unknown")]
    (str "**" display-name "** (" (platform-display platform) ") - " (format-timestamp createdAt) "\n"
         content "\n"
         "platform:: " platform "\n"
         "sender:: " sender-id "\n"
         "message-id:: " id "\n"
         "direction:: " direction)))

;; ---------------------------------------------------------------------------
;; Message Handlers
;; ---------------------------------------------------------------------------

(defn on-message
  "Registers a callback function to be invoked on incoming messages.
   The callback receives a parsed message map."
  [handler-fn]
  (swap! state update :message-handlers conj handler-fn))

(defn- notify-handlers [message]
  (doseq [handler (:message-handlers @state)]
    (try
      (handler message)
      (catch :default e
        (js/console.error "Message handler error:" e)))))

;; ---------------------------------------------------------------------------
;; Logseq Integration
;; ---------------------------------------------------------------------------

(defn ingest-message!
  "Creates or finds a Logseq page for the contact and appends the message as a block."
  [message]
  (let [{:keys [platform contact]} message
        display-name (or (:displayName contact) (:platformUserId contact) "Unknown")
        page-name (page-name-for-contact platform display-name)
        block-content (format-message-block message)]
    (-> (js/logseq.Editor.createPage
          page-name
          #js {}
          #js {:createFirstBlock false :redirect false})
        (.catch (fn [_] nil)) ;; page may already exist
        (.then (fn [_]
                 (js/logseq.Editor.appendBlockInPage page-name block-content))))))

;; ---------------------------------------------------------------------------
;; SSE Connection
;; ---------------------------------------------------------------------------

(defn- handle-sse-event [event-type raw-data]
  (when-let [data (parse-sse-data raw-data)]
    (case event-type
      "new_message"  (when-let [msg (:message data)]
                       (notify-handlers msg))
      "message_sent" (when-let [msg (:message data)]
                       (notify-handlers msg))
      "connected"    (js/console.log "SSE connected:" (pr-str data))
      "heartbeat"    nil
      (js/console.log "Unknown SSE event:" event-type))))

(defn disconnect!
  "Disconnects from the webhook server."
  []
  (when-let [es (:event-source @state)]
    (.close es))
  (swap! state assoc
         :event-source nil
         :connected? false))

(defn connect!
  "Connects to the webhook server via SSE.
   Returns the EventSource instance or nil if params are missing."
  ([] (connect! (:server-url @state) (:api-token @state)))
  ([server-url api-token]
   (when (and server-url api-token
              (not (str/blank? server-url))
              (not (str/blank? api-token)))
     (disconnect!)
     (let [url (build-sse-url server-url api-token)
           es  (js/EventSource. url)]
       ;; Register event listeners for each SSE event type
       (doseq [event-type ["new_message" "message_sent" "connected" "heartbeat"]]
         (.addEventListener es event-type
                            (fn [e]
                              (handle-sse-event event-type (.-data e)))))
       ;; Error handler
       (set! (.-onerror es)
             (fn [_e]
               (js/console.error "SSE connection error")
               (when (= (.-readyState es) 2) ;; CLOSED
                 (swap! state assoc :connected? false))))
       ;; Update state
       (swap! state assoc
              :event-source es
              :server-url server-url
              :api-token api-token
              :connected? true)
       es))))

;; ---------------------------------------------------------------------------
;; Send Message
;; ---------------------------------------------------------------------------

(defn send-message!
  "Sends a message via the webhook server API.
   Returns a Promise resolving to the API response map."
  [platform recipient content]
  (let [{:keys [server-url api-token]} @state]
    (if (or (str/blank? server-url) (str/blank? api-token))
      (js/Promise.reject (js/Error. "Not connected to server"))
      (-> (js/fetch (str server-url "/api/send")
                    (clj->js {:method "POST"
                              :headers {"Content-Type" "application/json"
                                        "Authorization" (str "Bearer " api-token)}
                              :body (js/JSON.stringify
                                     (clj->js {:platform platform
                                               :recipient recipient
                                               :content content}))}))
          (.then (fn [res]
                   (if (.-ok res)
                     (.json res)
                     (throw (js/Error. (str "API error: " (.-status res)))))))
          (.then (fn [data]
                   (js->clj data :keywordize-keys true)))))))

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init!
  "Initializes the messaging module. Reads settings and connects SSE.
   Registers the default ingest handler."
  []
  (let [settings js/logseq.settings
        server-url (aget settings "webhookServerUrl")
        api-token (aget settings "pluginApiToken")]
    (when (and server-url api-token
               (not (str/blank? server-url))
               (not (str/blank? api-token)))
      (on-message ingest-message!)
      (connect! server-url api-token)
      (js/console.log "Messaging module initialized"))))
