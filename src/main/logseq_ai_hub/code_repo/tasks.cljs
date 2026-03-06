(ns logseq-ai-hub.code-repo.tasks
  "Track and task page management for conductor-style project coordination.
   Tracks are pages at Projects/{project}/tracks/{trackId}.
   Tasks are TODO/DOING/DONE blocks within track pages."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn track-page-name
  "Builds the Logseq page name for a project track."
  [project track-id]
  (str "Projects/" project "/tracks/" track-id))

(defn status->marker
  "Converts a status string to a Logseq TODO marker prefix."
  [status]
  (case (str/lower-case (or status "todo"))
    "doing" "DOING "
    "done"  "DONE "
    "TODO "))

(defn extract-task-status
  "Extracts the TODO/DOING/DONE status from a block content string."
  [content]
  (cond
    (str/starts-with? content "DONE ")  "done"
    (str/starts-with? content "DOING ") "doing"
    (str/starts-with? content "TODO ")  "todo"
    :else nil))

(defn extract-task-description
  "Strips the TODO/DOING/DONE marker from block content."
  [content]
  (-> content
      (str/replace #"^(TODO|DOING|DONE) " "")))

(defn is-task-block?
  "Returns true if block content starts with a TODO marker."
  [content]
  (boolean (re-find #"^(TODO|DOING|DONE) " (or content ""))))

(defn get-page-blocks!
  "Returns Promise<js blocks array> for the given page-name."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.catch (fn [err]
                (js/console.warn "Error getting page blocks:" page-name err)
                #js []))))

;; ---------------------------------------------------------------------------
;; handle-track-create
;; ---------------------------------------------------------------------------

(defn handle-track-create
  "Creates a track page at Projects/{project}/tracks/{trackId}.
   Params: {\"project\" \"name\" \"trackId\" \"feature-auth\" \"description\" \"Add auth\"
            \"type\" \"feature\" \"priority\" \"high\" \"branch\" \"track/feature-auth\"
            \"assignedAgent\" \"claude\"}
   Returns Promise resolving to {:project :trackId :page :status :created true}."
  [params]
  (let [project      (str/trim (or (get params "project") ""))
        track-id     (str/trim (or (get params "trackId") ""))
        description  (str/trim (or (get params "description") ""))
        track-type   (str/trim (or (get params "type") ""))
        priority     (str/trim (or (get params "priority") ""))
        branch       (str/trim (or (get params "branch") ""))
        agent        (str/trim (or (get params "assignedAgent") ""))]
    (cond
      (str/blank? project)
      (js/Promise.reject (js/Error. "Missing required parameter: project"))

      (str/blank? track-id)
      (js/Promise.reject (js/Error. "Missing required parameter: trackId"))

      :else
      (let [page-name (track-page-name project track-id)
            props     (clj->js (cond-> {"track-status"      "planned"
                                        "tags"              "logseq-ai-hub-track"}
                                 (not (str/blank? track-type)) (assoc "track-type" track-type)
                                 (not (str/blank? priority))   (assoc "track-priority" priority)
                                 (not (str/blank? branch))     (assoc "track-branch" branch)
                                 (not (str/blank? agent))      (assoc "track-assigned-agent" agent)
                                 (not (str/blank? description)) (assoc "track-description" description)))
            opts      (clj->js {:redirect false :createFirstBlock false})]
        (-> (js/logseq.Editor.createPage page-name props opts)
            (.then (fn [page]
                     (if (str/blank? description)
                       (js/Promise.resolve page)
                       (-> (js/logseq.Editor.appendBlockInPage page-name description)
                           (.then (fn [_] page))))))
            (.then (fn [_]
                     {:project  project
                      :trackId  track-id
                      :page     page-name
                      :status   "planned"
                      :created  true})))))))

;; ---------------------------------------------------------------------------
;; handle-track-list
;; ---------------------------------------------------------------------------

(defn handle-track-list
  "Lists all tracks for a project.
   Params: {\"project\" \"name\" \"status\" \"active\" (opt) \"type\" \"feature\" (opt)}
   Returns Promise resolving to {:tracks [...] :count N :project project}."
  [params]
  (let [project       (str/trim (or (get params "project") ""))
        status-filter (get params "status")
        type-filter   (get params "type")]
    (if (str/blank? project)
      (js/Promise.reject (js/Error. "Missing required parameter: project"))
      (let [prefix        (str "projects/" project "/tracks/")
            query         (str "[:find (pull ?p [:block/name :block/original-name])"
                               " :where [?p :block/name ?n]"
                               "        [(clojure.string/starts-with? ?n \"" prefix "\")]]")]
        (-> (js/logseq.DB.datascriptQuery query)
            (.then (fn [results]
                     (if (and results (pos? (.-length results)))
                       (let [converted (js->clj results :keywordize-keys true)
                             pages     (mapv (fn [r]
                                               (let [page (first r)]
                                                 {:page-name      (:block/name page)
                                                  :original-name  (or (:block/original-name page)
                                                                       (:block/name page))}))
                                             converted)]
                         (js/Promise.all
                           (clj->js
                             (for [{:keys [original-name]} pages]
                               (-> (js/logseq.Editor.getPage original-name)
                                   (.then (fn [page]
                                            (when page
                                              (let [page-map   (js->clj page :keywordize-keys true)
                                                    props      (:properties page-map {})
                                                    trk-status (str (get props :trackStatus
                                                                     (get props :track-status "")))
                                                    trk-type   (str (get props :trackType
                                                                     (get props :track-type "")))]
                                                {:page           original-name
                                                 :trackId        (last (str/split original-name #"/"))
                                                 :status         trk-status
                                                 :type           trk-type
                                                 :priority       (str (get props :trackPriority
                                                                       (get props :track-priority "")))
                                                 :branch         (str (get props :trackBranch
                                                                       (get props :track-branch "")))
                                                 :assignedAgent  (str (get props :trackAssignedAgent
                                                                       (get props :track-assigned-agent "")))
                                                 :description    (str (get props :trackDescription
                                                                       (get props :track-description "")))}))))
                                   (.catch (fn [err]
                                             (js/console.warn "Failed to read track page:" original-name err)
                                             nil)))))))
                       (js/Promise.resolve (clj->js [])))))
            (.then (fn [track-results]
                     (let [tracks   (vec (filter some? (js->clj track-results)))
                           filtered (cond->> tracks
                                      (not (str/blank? status-filter))
                                      (filter #(= (:status %) status-filter))

                                      (not (str/blank? type-filter))
                                      (filter #(= (:type %) type-filter)))]
                       {:tracks  filtered
                        :count   (count filtered)
                        :project project})))
            (.catch (fn [err]
                      (js/console.warn "handle-track-list error:" err)
                      {:tracks [] :count 0 :project project})))))))

;; ---------------------------------------------------------------------------
;; handle-track-update
;; ---------------------------------------------------------------------------

(defn handle-track-update
  "Updates properties on a track page.
   Params: {\"project\" \"name\" \"trackId\" \"id\" \"status\" \"active\" (opt)
            \"priority\" \"high\" (opt) \"branch\" \"...\" (opt) \"assignedAgent\" \"...\" (opt)}
   Returns Promise resolving to {:project :trackId :updated true}."
  [params]
  (let [project  (str/trim (or (get params "project") ""))
        track-id (str/trim (or (get params "trackId") ""))
        status   (get params "status")
        priority (get params "priority")
        branch   (get params "branch")
        agent    (get params "assignedAgent")]
    (cond
      (str/blank? project)
      (js/Promise.reject (js/Error. "Missing required parameter: project"))

      (str/blank? track-id)
      (js/Promise.reject (js/Error. "Missing required parameter: trackId"))

      :else
      (let [page-name (track-page-name project track-id)
            updates   (cond-> {}
                        (not (nil? status))   (assoc "track-status" status)
                        (not (nil? priority)) (assoc "track-priority" priority)
                        (not (nil? branch))   (assoc "track-branch" branch)
                        (not (nil? agent))    (assoc "track-assigned-agent" agent))]
        (-> (get-page-blocks! page-name)
            (.then (fn [blocks]
                     (if (and blocks (pos? (.-length blocks)))
                       (let [first-block (aget blocks 0)
                             block-uuid  (.-uuid first-block)]
                         (reduce (fn [promise-chain [k v]]
                                   (.then promise-chain
                                          (fn [_]
                                            (js/logseq.Editor.upsertBlockProperty block-uuid k v))))
                                 (js/Promise.resolve nil)
                                 updates))
                       (js/Promise.resolve nil))))
            (.then (fn [_]
                     {:project project
                      :trackId track-id
                      :updated true})))))))

;; ---------------------------------------------------------------------------
;; handle-task-add
;; ---------------------------------------------------------------------------

(defn handle-task-add
  "Appends a TODO task block to a track page.
   Params: {\"project\" \"name\" \"trackId\" \"id\" \"description\" \"Do X\" \"agent\" \"claude\" (opt)}
   Returns Promise resolving to {:project :trackId :description :added true}."
  [params]
  (let [project     (str/trim (or (get params "project") ""))
        track-id    (str/trim (or (get params "trackId") ""))
        description (str/trim (or (get params "description") ""))
        agent       (str/trim (or (get params "agent") ""))]
    (cond
      (str/blank? project)
      (js/Promise.reject (js/Error. "Missing required parameter: project"))

      (str/blank? track-id)
      (js/Promise.reject (js/Error. "Missing required parameter: trackId"))

      (str/blank? description)
      (js/Promise.reject (js/Error. "Missing required parameter: description"))

      :else
      (let [page-name     (track-page-name project track-id)
            block-content (if (str/blank? agent)
                            (str "TODO " description)
                            (str "TODO " description " #agent-" agent))]
        (-> (js/logseq.Editor.appendBlockInPage page-name block-content)
            (.then (fn [_]
                     {:project     project
                      :trackId     track-id
                      :description description
                      :added       true})))))))

;; ---------------------------------------------------------------------------
;; handle-task-update
;; ---------------------------------------------------------------------------

(defn handle-task-update
  "Updates a task block's status on a track page.
   Params: {\"project\" \"name\" \"trackId\" \"id\" \"taskIndex\" 0 \"status\" \"doing\" (opt)
            \"agent\" \"claude\" (opt)}
   Returns Promise resolving to {:project :trackId :taskIndex :updated true}."
  [params]
  (let [project    (str/trim (or (get params "project") ""))
        track-id   (str/trim (or (get params "trackId") ""))
        task-index (or (get params "taskIndex") 0)
        new-status (get params "status")
        agent      (get params "agent")]
    (cond
      (str/blank? project)
      (js/Promise.reject (js/Error. "Missing required parameter: project"))

      (str/blank? track-id)
      (js/Promise.reject (js/Error. "Missing required parameter: trackId"))

      :else
      (let [page-name (track-page-name project track-id)]
        (-> (get-page-blocks! page-name)
            (.then (fn [blocks]
                     (if (and blocks (pos? (.-length blocks)))
                       (let [all-blocks (js->clj blocks :keywordize-keys true)
                             ;; skip the first block which holds page properties
                             task-blocks (vec (rest all-blocks))
                             target      (nth task-blocks task-index nil)]
                         (if target
                           (let [old-content (:content target "")
                                 description (extract-task-description old-content)
                                 marker      (if new-status
                                               (status->marker new-status)
                                               (str (or (extract-task-status old-content) "todo")
                                                    " "))
                                 new-content (if (and agent (not (str/blank? agent)))
                                               (if (re-find #"#agent-" description)
                                                 (str marker (str/replace description #"#agent-\S+" (str "#agent-" agent)))
                                                 (str marker description " #agent-" agent))
                                               (str marker description))
                                 block-uuid  (:uuid target)]
                             (js/logseq.Editor.updateBlock block-uuid new-content))
                           (js/Promise.reject (js/Error. (str "Task index out of range: " task-index)))))
                       (js/Promise.reject (js/Error. "No blocks found on track page")))))
            (.then (fn [_]
                     {:project   project
                      :trackId   track-id
                      :taskIndex task-index
                      :updated   true})))))))

;; ---------------------------------------------------------------------------
;; handle-task-list
;; ---------------------------------------------------------------------------

(defn handle-task-list
  "Lists tasks on a track page.
   Params: {\"project\" \"name\" \"trackId\" \"id\" \"status\" \"todo\" (opt)}
   Returns Promise resolving to {:tasks [{:index N :status \"todo\" :description \"...\" :content \"raw\"}]
                                  :count N}."
  [params]
  (let [project       (str/trim (or (get params "project") ""))
        track-id      (str/trim (or (get params "trackId") ""))
        status-filter (get params "status")]
    (cond
      (str/blank? project)
      (js/Promise.reject (js/Error. "Missing required parameter: project"))

      (str/blank? track-id)
      (js/Promise.reject (js/Error. "Missing required parameter: trackId"))

      :else
      (let [page-name (track-page-name project track-id)]
        (-> (get-page-blocks! page-name)
            (.then (fn [blocks]
                     (if (and blocks (pos? (.-length blocks)))
                       (let [all-blocks  (js->clj blocks :keywordize-keys true)
                             ;; skip the first property block
                             task-blocks (vec (rest all-blocks))
                             parsed      (keep-indexed
                                           (fn [idx block]
                                             (let [content (:content block "")
                                                   status  (extract-task-status content)]
                                               (when status
                                                 {:index       idx
                                                  :status      status
                                                  :description (extract-task-description content)
                                                  :content     content})))
                                           task-blocks)
                             filtered    (if (str/blank? status-filter)
                                           parsed
                                           (filter #(= (:status %) (str/lower-case status-filter)) parsed))]
                         {:tasks (vec filtered)
                          :count (count filtered)})
                       {:tasks [] :count 0}))))))))

;; ---------------------------------------------------------------------------
;; handle-project-dashboard
;; ---------------------------------------------------------------------------

(defn handle-project-dashboard
  "Returns a summary dashboard for a project.
   Params: {\"project\" \"name\"}
   Returns Promise resolving to {:project :tracks {:total N :by-status {...}}
                                  :tasks {:total N :by-status {...}} :completion-pct N}."
  [params]
  (let [project (str/trim (or (get params "project") ""))]
    (if (str/blank? project)
      (js/Promise.reject (js/Error. "Missing required parameter: project"))
      (-> (handle-track-list params)
          (.then (fn [track-result]
                   (let [tracks (:tracks track-result [])]
                     ;; Fetch tasks for every track in parallel
                     (-> (js/Promise.all
                           (clj->js
                             (for [track tracks]
                               (handle-task-list {"project" project
                                                  "trackId" (:trackId track)}))))
                         (.then (fn [task-results]
                                  (let [all-task-lists (js->clj task-results)
                                        ;; Aggregate track stats
                                        track-by-status (reduce (fn [acc track]
                                                                   (let [s (or (:status track) "planned")]
                                                                     (update acc s (fnil inc 0))))
                                                                 {}
                                                                 tracks)
                                        ;; Aggregate task stats
                                        all-tasks       (mapcat :tasks all-task-lists)
                                        task-by-status  (reduce (fn [acc task]
                                                                   (let [s (or (:status task) "todo")]
                                                                     (update acc s (fnil inc 0))))
                                                                 {}
                                                                 all-tasks)
                                        total-tasks     (count all-tasks)
                                        done-tasks      (get task-by-status "done" 0)
                                        completion-pct  (if (pos? total-tasks)
                                                          (js/Math.round (* 100 (/ done-tasks total-tasks)))
                                                          0)]
                                    {:project        project
                                     :tracks         {:total     (count tracks)
                                                      :by-status track-by-status}
                                     :tasks          {:total     total-tasks
                                                      :by-status task-by-status}
                                     :completion-pct completion-pct})))))))))))
