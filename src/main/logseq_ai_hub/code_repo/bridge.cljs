(ns logseq-ai-hub.code-repo.bridge
  "Bridge operation handlers for project registry.
   These are called by the server via the Agent Bridge SSE/callback pattern."
  (:require [logseq-ai-hub.code-repo.project :as project]
            [clojure.string :as str]))

(defn- entry->project-map
  "Maps a parsed project entry to the public-facing project map."
  [entry]
  (let [props (:properties entry)]
    {:name        (get props :project-name)
     :repo        (get props :project-repo)
     :localPath   (get props :project-local-path)
     :branchMain  (get props :project-branch-main)
     :techStack   (get props :project-tech-stack)
     :description (get props :project-description)
     :status      (get props :project-status)}))

(defn handle-project-list
  "Lists all project entries, optionally filtered by status.
   Params: {\"status\" \"active|archived\" (optional)}
   Returns: Promise of {:projects [...] :count N}"
  [params]
  (let [status-filter (get params "status")]
    (-> (project/scan-and-parse-projects!)
        (.then (fn [entries]
                 (let [filtered (if status-filter
                                  (filter (fn [e]
                                            (= status-filter
                                               (get (:properties e) :project-status)))
                                          entries)
                                  entries)
                       projects (mapv entry->project-map filtered)]
                   {:projects projects
                    :count    (count projects)}))))))

(defn- extract-body-text
  "Extracts non-property block content as context text from a blocks array.
   Skips blocks that are purely property definitions (key:: value lines only)."
  [blocks]
  (let [prop-only-re #"(?m)^[a-z][a-z0-9-]*:: .+$"
        blocks-clj (js->clj blocks :keywordize-keys true)]
    (->> blocks-clj
         (map :content)
         (filter (fn [content]
                   (when content
                     ;; Keep blocks that have lines beyond property definitions
                     (let [lines (str/split-lines content)
                           non-prop-lines (remove (fn [l]
                                                    (re-matches #"^[a-z][a-z0-9-]*:: .+$"
                                                                (str/trim l)))
                                                  lines)
                           non-blank (filter (fn [l] (not (str/blank? l))) non-prop-lines)]
                       (seq non-blank)))))
         (str/join "\n\n"))))

(defn handle-project-get
  "Gets a single project by name, including page body.
   Params: {\"name\" \"project-name-value\"}
   Returns: Promise of full project map with :body field, or rejects with error string."
  [params]
  (let [name-param (get params "name")]
    (if (str/blank? name-param)
      (js/Promise.reject "Missing required param: name")
      (-> (project/scan-and-parse-projects!)
          (.then (fn [entries]
                   (let [found (first (filter (fn [e]
                                                (= name-param
                                                   (get (:properties e) :project-name)))
                                              entries))]
                     (if found
                       ;; Read full block tree for body context
                       (-> (project/read-page-blocks (:id found))
                           (.then (fn [blocks]
                                    (let [body (extract-body-text blocks)
                                          project-map (entry->project-map found)]
                                      (assoc project-map :body body)))))
                       (js/Promise.reject (str "Project not found: " name-param))))))))))
