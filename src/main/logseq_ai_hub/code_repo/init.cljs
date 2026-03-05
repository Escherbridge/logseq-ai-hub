(ns logseq-ai-hub.code-repo.init
  "Initializes code repository integration.
   Scans for project pages and sets up DB watchers for auto-discovery."
  (:require [logseq-ai-hub.code-repo.project :as project]
            [logseq-ai-hub.code-repo.templates :as templates]))

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

(defn- prompt-for-input
  "Thin wrapper around js/prompt so it can be stubbed in tests."
  [msg]
  (js/prompt msg))

(defn- register-slash-commands!
  "Registers code-repo slash commands in the Logseq editor."
  []
  ;; /code-repo:create-project
  (js/logseq.Editor.registerSlashCommand
    "code-repo:create-project"
    (fn []
      (let [project-name (prompt-for-input "Project name:")]
        (when (and project-name (not (empty? project-name)))
          (-> (js/logseq.Editor.createPage
                (str "Projects/" project-name)
                (clj->js {:tags              "logseq-ai-hub-project"
                          :project-name      project-name
                          :project-status    "active"})
                #js {:redirect true})
              (.then (fn [_]
                       (js/logseq.UI.showMsg (str "Created project page: Projects/" project-name))))
              (.catch (fn [err]
                        (js/console.warn "Code-repo: create-project error:" err)
                        (js/logseq.UI.showMsg "Failed to create project page" "error"))))))))

  ;; /code-repo:create-adr
  (js/logseq.Editor.registerSlashCommand
    "code-repo:create-adr"
    (fn []
      (let [project-name (prompt-for-input "Project name:")
            adr-title    (when (and project-name (not (empty? project-name)))
                           (prompt-for-input "ADR title:"))]
        (when (and adr-title (not (empty? adr-title)))
          (let [page-name (str "ADR/" project-name "/" adr-title)]
            (-> (js/logseq.Editor.createPage
                  page-name
                  (clj->js {:tags        "logseq-ai-hub-adr"
                            :adr-project project-name
                            :adr-status  "proposed"})
                  #js {:redirect true})
                (.then (fn [_]
                         (js/logseq.UI.showMsg (str "Created ADR page: " page-name))))
                (.catch (fn [err]
                          (js/console.warn "Code-repo: create-adr error:" err)
                          (js/logseq.UI.showMsg "Failed to create ADR page" "error")))))))))

  ;; /code-repo:create-review-skill
  (js/logseq.Editor.registerSlashCommand
    "code-repo:create-review-skill"
    (fn []
      (let [project-name (prompt-for-input "Project name:")]
        (when (and project-name (not (empty? project-name)))
          (-> (templates/create-code-review-skill! project-name)
              (.then (fn [result]
                       (if (= "created" (:status result))
                         (js/logseq.UI.showMsg (str "Created skill page: " (:page-name result)))
                         (js/logseq.UI.showMsg "Failed to create skill page" "error"))))
              (.catch (fn [err]
                        (js/console.warn "Code-repo: create-review-skill error:" err)
                        (js/logseq.UI.showMsg "Failed to create skill page" "error"))))))))

  ;; /code-repo:create-deploy-procedure
  (js/logseq.Editor.registerSlashCommand
    "code-repo:create-deploy-procedure"
    (fn []
      (let [project-name (prompt-for-input "Project name:")]
        (when (and project-name (not (empty? project-name)))
          (let [contact (prompt-for-input "Approval contact (email, leave blank to skip):")]
            (-> (templates/create-deployment-procedure!
                  project-name
                  {:contact (or contact "")})
                (.then (fn [result]
                         (if (= "created" (:status result))
                           (js/logseq.UI.showMsg (str "Created procedure page: " (:page-name result)))
                           (js/logseq.UI.showMsg "Failed to create procedure page" "error"))))
                (.catch (fn [err]
                          (js/console.warn "Code-repo: create-deploy-procedure error:" err)
                          (js/logseq.UI.showMsg "Failed to create procedure page" "error"))))))))))

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
                 (register-slash-commands!)
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
