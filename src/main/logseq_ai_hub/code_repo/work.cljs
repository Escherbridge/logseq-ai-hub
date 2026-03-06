(ns logseq-ai-hub.code-repo.work
  "Work log bridge handler for project activity logging.
   Appends timestamped entries to Projects/{project}/log pages."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn work-log-page-name
  "Builds the Logseq page name for a project's work log."
  [project]
  (str "Projects/" project "/log"))

(defn build-block-content
  "Builds formatted block content for a work log entry."
  [action details]
  (let [timestamp (.toISOString (js/Date.))
        details-str (if (str/blank? details) "" (str " — " details))]
    (str "**" action "**" details-str "\n_" timestamp "_")))

;; ---------------------------------------------------------------------------
;; Handler
;; ---------------------------------------------------------------------------

(defn handle-work-log
  "Bridge handler to append a work log entry to a project's log page.
   Creates the page if it doesn't exist.
   Params map: {\"project\" \"name\" \"action\" \"description\" \"details\" \"extra info\"}
   Returns Promise resolving to {:project :action :logged true :page page-name}."
  [params]
  (let [project (str/trim (or (get params "project") ""))
        action  (str/trim (or (get params "action") ""))
        details (str/trim (or (get params "details") ""))]
    (cond
      (str/blank? project)
      (js/Promise.reject (js/Error. "Missing required parameter: project"))

      (str/blank? action)
      (js/Promise.reject (js/Error. "Missing required parameter: action"))

      :else
      (let [page-name     (work-log-page-name project)
            block-content (build-block-content action details)
            props         #js {"tags" "logseq-ai-hub-work-log"}
            opts          #js {:createFirstBlock false :redirect false}]
        (-> (js/logseq.Editor.createPage page-name props opts)
            (.catch (fn [_] nil)) ;; page may already exist
            (.then (fn [_]
                     (js/logseq.Editor.appendBlockInPage page-name block-content)))
            (.then (fn [_]
                     {:project project
                      :action  action
                      :logged  true
                      :page    page-name})))))))
