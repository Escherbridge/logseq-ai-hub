(ns logseq-ai-hub.mcp.on-demand
  "On-demand MCP connection lifecycle for ad-hoc LLM tool-use.
   Provides ephemeral connect-use-disconnect flow, unlike the job runner's
   persistent connections."
  (:require [logseq-ai-hub.mcp.client :as client]
            [logseq-ai-hub.mcp.page-reader :as page-reader]
            [logseq-ai-hub.util.errors :as errors]))

(defn connect-from-page!
  "Reads MCP config from a Logseq page and connects the server.
   Returns Promise<server-id> on success."
  [page-name]
  (-> (page-reader/read-mcp-page page-name)
      (.then (fn [config]
               (if config
                 (-> (client/connect-server! config)
                     (.then (fn [_] (:id config))))
                 (js/Promise.reject
                   (errors/make-error :mcp-page-not-found
                     (str "MCP page not found or missing mcp-url: " page-name))))))))

(defn connect-servers-from-refs!
  "Given a vector of MCP page names, connects all of them in parallel.
   Returns Promise<vector-of-server-ids>."
  [page-names]
  (if (empty? page-names)
    (js/Promise.resolve [])
    (-> (js/Promise.all
          (clj->js (mapv connect-from-page! page-names)))
        (.then (fn [ids] (vec (js->clj ids)))))))

(defn disconnect-servers!
  "Disconnects a vector of server IDs. Synchronous cleanup."
  [server-ids]
  (doseq [sid server-ids]
    (client/disconnect-server! sid)))

(defn collect-tools
  "Fetches all tools from the given server IDs.
   Returns Promise<vector-of-{:server-id :tools [...]}>."
  [server-ids]
  (if (empty? server-ids)
    (js/Promise.resolve [])
    (-> (js/Promise.all
          (clj->js
            (mapv (fn [sid]
                    (-> (client/list-tools sid)
                        (.then (fn [tools]
                                 {:server-id sid :tools tools}))))
                  server-ids)))
        (.then (fn [results] (vec (js->clj results)))))))
