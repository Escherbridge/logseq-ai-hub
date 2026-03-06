(ns logseq-ai-hub.code-repo.project
  "Scans the Logseq graph for project registry pages tagged logseq-ai-hub-project."
  (:require [clojure.string :as str]))

(defn scan-projects!
  "Queries Logseq for pages tagged logseq-ai-hub-project.
   Returns Promise<vector of {:page-name :original-name}>."
  []
  (let [query "[:find (pull ?p [:block/name :block/original-name])
               :where [?b :block/page ?p]
                      [?b :block/content ?c]
                      [(clojure.string/includes? ?c \"logseq-ai-hub-project\")]]"]
    (-> (js/logseq.DB.datascriptQuery query)
        (.then (fn [results]
                 (if (and results (pos? (.-length results)))
                   (let [converted (js->clj results :keywordize-keys true)]
                     (mapv (fn [r]
                             (let [page (first r)]
                               {:page-name (:block/name page)
                                :original-name (or (:block/original-name page)
                                                   (:block/name page))}))
                           converted))
                   [])))
        (.catch (fn [err]
                  (js/console.warn "Project scan error:" err)
                  [])))))

(defn parse-project-properties
  "Given block content string, extracts project properties.
   Looks for key:: value lines matching known project property keys.
   Returns a map with keyword keys."
  [content]
  (let [lines (str/split-lines content)
        prop-re #"^([a-z][a-z0-9-]*):: (.+)$"
        project-keys #{"project-name" "project-repo" "project-local-path"
                       "project-branch-main" "project-tech-stack"
                       "project-description" "project-status"}]
    (into {}
      (keep (fn [line]
              (when-let [[_ k v] (re-matches prop-re (str/trim line))]
                (when (project-keys k)
                  [(keyword k) (str/trim v)])))
            lines))))

(defn read-page-blocks
  "Reads a page's block tree via js/logseq.Editor.getPageBlocksTree.
   Returns Promise<blocks array>."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.catch (fn [err]
                (js/console.warn "Error reading page blocks:" page-name err)
                #js []))))

(defn parse-project-page
  "Given page-name and first-block content, returns {:valid true :entry {...}}
   or {:valid false :errors [...]}.
   Valid if :project-name is present."
  [page-name content]
  (let [props (parse-project-properties content)]
    (if (str/blank? (get props :project-name))
      {:valid false
       :errors [{:field :project-name :message "project-name is required"}]}
      {:valid true
       :entry {:id page-name
               :type :project
               :name (get props :project-name)
               :description (or (get props :project-description) "")
               :properties props
               :source :graph-page}})))

(defn scan-and-parse-projects!
  "Chains scan-projects! -> read first block -> parse for each page.
   Filters out nils. Returns Promise<vector of project entries>."
  []
  (-> (scan-projects!)
      (.then (fn [pages]
               (js/Promise.all
                 (clj->js
                   (for [{:keys [page-name original-name]} pages]
                     (let [display-name (or original-name page-name)]
                       (-> (read-page-blocks display-name)
                           (.then (fn [blocks]
                                    (when (and blocks (pos? (.-length blocks)))
                                      (let [first-block (aget blocks 0)
                                            content (.-content first-block)]
                                        (when content
                                          (let [result (parse-project-page display-name content)]
                                            (when (:valid result)
                                              (:entry result))))))))
                           (.catch (fn [err]
                                     (js/console.warn "Failed to parse project page:" display-name err)
                                     nil)))))))))
      (.then (fn [results]
               (vec (filter some? (js->clj results)))))))
