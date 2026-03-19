(ns logseq-ai-hub.event-hub.publish
  "Publishes events to the server's EventBus via authenticated HTTP."
  (:require [logseq-ai-hub.auth :as auth]))

(defn- get-server-url []
  (aget js/logseq "settings" "webhookServerUrl"))

(defn- get-api-token []
  (auth/get-auth-token))

(defn publish-to-server!
  "Publishes an event to the server's EventBus.
   Fire-and-forget -- logs errors but does not throw.
   Returns Promise<{:event-id string} | nil>."
  [{:keys [type source data metadata]}]
  (let [server-url (get-server-url)
        token (get-api-token)]
    (if (and server-url token)
      (-> (js/fetch (str server-url "/api/events/publish")
                    (clj->js {:method "POST"
                              :headers {"Content-Type" "application/json"
                                        "Authorization" (str "Bearer " token)}
                              :body (js/JSON.stringify
                                      (clj->js {:type type
                                                :source source
                                                :data data
                                                :metadata metadata}))}))
          (.then (fn [res] (.json res)))
          (.then (fn [json]
                   (let [result (js->clj json :keywordize-keys true)]
                     (when (:success result)
                       {:event-id (:eventId result)}))))
          (.catch (fn [err]
                    (js/console.warn "[EventHub] Failed to publish event:" err)
                    nil)))
      (do
        (js/console.warn "[EventHub] Server URL or API token not configured")
        (js/Promise.resolve nil)))))
