(ns logseq-ai-hub.code-repo.lessons
  "Lesson-learned memory integration for coding sessions.
   Stores lessons as pages under AI-Memory/lessons/{project}/{category}/{title}."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn escape-datalog-string
  "Escapes special characters for safe interpolation into Datalog query strings."
  [s]
  (str/replace s "\"" "\\\""))

(defn slugify
  "Converts a title string to a URL/page-name friendly slug.
   Lowercases, replaces non-alphanumeric chars with hyphens, trims edge hyphens."
  [s]
  (-> s
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn lesson-page-name
  "Builds the full Logseq page name for a lesson."
  [project category title]
  (str "AI-Memory/lessons/" project "/" category "/" (slugify title)))

;; ---------------------------------------------------------------------------
;; Storage
;; ---------------------------------------------------------------------------

(defn handle-lesson-store
  "Bridge handler to store a lesson-learned as a Logseq page.
   Params map: {\"project\" \"name\" \"category\" \"bug-fix\" \"title\" \"Fix X\" \"content\" \"...\"}
   Returns Promise resolving to {:page :project :category :title :stored true}."
  [params]
  (let [project  (str/trim (or (get params "project") ""))
        category (str/trim (or (get params "category") ""))
        title    (str/trim (or (get params "title") ""))
        content  (str/trim (or (get params "content") ""))]
    (cond
      (str/blank? project)
      (js/Promise.reject (js/Error. "Missing required parameter: project"))

      (str/blank? category)
      (js/Promise.reject (js/Error. "Missing required parameter: category"))

      (str/blank? title)
      (js/Promise.reject (js/Error. "Missing required parameter: title"))

      (str/blank? content)
      (js/Promise.reject (js/Error. "Missing required parameter: content"))

      :else
      (let [page-name (lesson-page-name project category title)
            props     #js {"lesson-project"  project
                           "lesson-category" category
                           "lesson-date"     (.toISOString (js/Date.))
                           "lesson-title"    title
                           "tags"            "logseq-ai-hub-lesson"}
            opts      #js {:createFirstBlock false :redirect false}]
        (-> (js/logseq.Editor.createPage page-name props opts)
            (.catch (fn [_] nil)) ;; page may already exist
            (.then (fn [_]
                     (js/logseq.Editor.appendBlockInPage page-name content)))
            (.then (fn [_]
                     {:page     page-name
                      :project  project
                      :category category
                      :title    title
                      :stored   true})))))))

;; ---------------------------------------------------------------------------
;; Search / Retrieval
;; ---------------------------------------------------------------------------

(defn- extract-lesson-meta
  "Parses project, category, title from a lesson page name.
   Page name format (lowercased by Logseq): ai-memory/lessons/{project}/{category}/{slug}
   Returns {:project :category :slug} or nil if format does not match."
  [page-name]
  (let [prefix "ai-memory/lessons/"
        rest   (when (str/starts-with? page-name prefix)
                 (subs page-name (count prefix)))
        parts  (when rest (str/split rest #"/"))]
    (when (and parts (>= (count parts) 3))
      {:project  (nth parts 0)
       :category (nth parts 1)
       :slug     (str/join "/" (drop 2 parts))})))

(defn- blocks->content
  "Extracts a single string of content from a blocks array (JS array of block objects)."
  [blocks]
  (when (and blocks (pos? (.-length blocks)))
    (->> (js->clj blocks :keywordize-keys true)
         (mapv :content)
         (filter some?)
         (str/join "\n"))))

(defn- read-lesson-page!
  "Reads blocks from a lesson page. Returns Promise<string content>."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.then blocks->content)
      (.catch (fn [err]
                (js/console.warn "Error reading lesson page:" page-name err)
                nil))))

(defn handle-lesson-search
  "Bridge handler to search stored lessons.
   Params map: {\"query\" \"search term\" \"project\" \"name\" (optional) \"category\" \"bug-fix\" (optional)}
   Returns Promise resolving to {:results [{:page :project :category :title :content :date}] :count N :query query}."
  [params]
  (let [query-str (str/trim (or (get params "query") ""))
        project   (when-let [p (get params "project")] (str/trim p))
        category  (when-let [c (get params "category")] (str/trim c))]
    (if (str/blank? query-str)
      (js/Promise.reject (js/Error. "Missing required parameter: query"))
      (let [prefix "ai-memory/lessons/"
            dq     (str "[:find (pull ?p [:block/name :block/original-name]) "
                        ":where [?p :block/name ?name] "
                        "[(clojure.string/starts-with? ?name \"" (escape-datalog-string prefix) "\")]]")]
        (-> (js/logseq.DB.datascriptQuery dq)
            (.then (fn [results]
                     (if (and results (pos? (.-length results)))
                       (let [converted   (js->clj results :keywordize-keys true)
                             page-names  (mapv (fn [r] (:block/name (first r))) converted)
                             ;; Apply project/category filters on page name
                             filtered    (cond->> page-names
                                           (and project (not (str/blank? project)))
                                           (filter #(str/includes? % (str "/" (str/lower-case project) "/")))

                                           (and category (not (str/blank? category)))
                                           (filter #(str/includes? % (str "/" (str/lower-case category) "/"))))]
                         ;; For each filtered page, read blocks and search content
                         (js/Promise.all
                           (clj->js
                             (mapv (fn [pn]
                                     (-> (read-lesson-page! pn)
                                         (.then (fn [content]
                                                  (when (and content
                                                             (str/includes? (str/lower-case content)
                                                                            (str/lower-case query-str)))
                                                    (let [meta (extract-lesson-meta pn)]
                                                      {:page     pn
                                                       :project  (:project meta)
                                                       :category (:category meta)
                                                       :title    (:slug meta)
                                                       :content  content
                                                       :date     nil}))))))
                                   filtered))))
                       (js/Promise.resolve #js []))))
            (.then (fn [raw-results]
                     (let [results (vec (filter some? (js->clj raw-results)))]
                       {:results results
                        :count   (count results)
                        :query   query-str})))
            (.catch (fn [err]
                      (js/console.error "Lesson search error:" err)
                      {:results [] :count 0 :query query-str})))))))
