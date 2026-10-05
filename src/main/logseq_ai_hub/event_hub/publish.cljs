(ns logseq-ai-hub.event-hub.publish
  "Publishes events to the server's EventBus via authenticated HTTP."
  (:require [logseq-ai-hub.auth :as auth]
            [clojure.string :as str]))

(defn- unauthorized-detail [status]
  (str "Server responded " status
       " -- the Plugin API Token does not match the server's PLUGIN_API_TOKEN. "
       "Check Settings -> 'Plugin API Token'."))

(defn publish-to-server!
  "Publishes an event to the server's EventBus.
   Fire-and-forget for lifecycle callers (job runner, emit, graph-watcher) --
   ALWAYS RESOLVES, never rejects, so it stays safe to call without a .catch.
   Returns Promise<{:ok true :event-id string} |
                    {:ok false :reason :no-server|:unauthorized|:unreachable|:rejected
                     :detail string}> -- never a bare nil/id, so a caller that
   DOES inspect the result (event:test) can tell a real publish from every
   failure mode."
  [{:keys [type source data metadata]}]
  (let [server-url (auth/get-server-url)
        token (auth/get-auth-token)]
    (if (and (not (str/blank? server-url)) (not (str/blank? token)))
      (-> (js/fetch (str server-url "/api/events/publish")
                    (clj->js {:method "POST"
                              :headers {"Content-Type" "application/json"
                                        "Authorization" (str "Bearer " token)}
                              :body (js/JSON.stringify
                                      (clj->js {:type type
                                                :source source
                                                :data data
                                                :metadata metadata}))}))
          (.then (fn [res]
                   (if (.-ok res)
                     (-> (.json res)
                         (.then (fn [json]
                                  (let [result (js->clj json :keywordize-keys true)]
                                    (if (:success result)
                                      {:ok true :event-id (:eventId result)}
                                      {:ok false :reason :rejected
                                       :detail (or (:error result)
                                                   "Server did not report success.")})))))
                     (let [status (.-status res)]
                       (js/Promise.resolve
                         (if (contains? #{401 403} status)
                           {:ok false :reason :unauthorized :detail (unauthorized-detail status)}
                           {:ok false :reason :rejected
                            :detail (str "Server responded with HTTP " status ".")}))))))
          (.catch (fn [err]
                    (js/console.warn "[EventHub] Failed to publish event:" err)
                    {:ok false :reason :unreachable
                     :detail (str "Could not reach " server-url " (" (.-message err) ")")})))
      (do
        (js/console.warn "[EventHub] Server URL or API token not configured")
        (js/Promise.resolve
          {:ok false :reason :no-server
           :detail "Set Settings -> 'Webhook Server URL' and 'Plugin API Token' first."})))))
