(ns logseq-ai-hub.code-repo.init
  "Initializes code repository integration.
   Scans for project pages and sets up DB watchers for auto-discovery."
  (:require [logseq-ai-hub.code-repo.project :as project]))

(defonce ^:private initialized? (atom false))
(defonce ^:private scan-debounce-timer (atom nil))

(def ^:private DEBOUNCE_MS 2000)

(defn- debounced-rescan!
  "Debounced project rescan triggered by DB changes.
   Waits DEBOUNCE_MS after last change before rescanning."
  []
  (when-let [timer @scan-debounce-timer]
    (js/clearTimeout timer))
  (reset! scan-debounce-timer
    (js/setTimeout
      (fn []
        (reset! scan-debounce-timer nil)
        (-> (project/scan-and-parse-projects!)
            (.then (fn [projects]
                     (js/console.log "Code-repo: rescanned" (count projects) "projects")))
            (.catch (fn [err]
                      (js/console.warn "Code-repo: rescan error:" err)))))
      DEBOUNCE_MS)))

(defn- setup-db-watcher!
  "Registers a Logseq DB change watcher for project-tagged pages.
   Triggers debounced rescan on relevant page changes."
  []
  (when js/logseq.DB
    (js/logseq.DB.onChanged
      (fn [event]
        (let [data (js->clj event :keywordize-keys true)
              blocks (or (:blocks data) [])
              ;; Check if any changed block mentions project tag
              relevant? (some (fn [b]
                                (let [content (or (:content b) "")]
                                  (or (.includes content "logseq-ai-hub-project")
                                      (.includes content "logseq-ai-hub-adr")
                                      (.includes content "logseq-ai-hub-safeguard")
                                      (.includes content "logseq-ai-hub-pi-agent"))))
                              blocks)]
          (when relevant?
            (debounced-rescan!)))))))

(defn init!
  "Initializes code repository integration.
   Scans for existing project pages and sets up DB watcher.
   Returns Promise that resolves when initial scan completes."
  []
  (if @initialized?
    (js/Promise.resolve {:status "already-initialized"})
    (-> (project/scan-and-parse-projects!)
        (.then (fn [projects]
                 (reset! initialized? true)
                 (setup-db-watcher!)
                 (js/console.log "Code-repo: initialized with" (count projects) "projects")
                 {:status "initialized" :projects (count projects)}))
        (.catch (fn [err]
                  (js/console.warn "Code-repo: initialization error:" err)
                  {:status "error" :error (str err)})))))

(defn shutdown!
  "Cleanup function for code-repo module."
  []
  (when-let [timer @scan-debounce-timer]
    (js/clearTimeout timer))
  (reset! scan-debounce-timer nil)
  (reset! initialized? false))
