(ns logseq-ai-hub.code-repo.adr
  "Scans the Logseq graph for ADR pages tagged logseq-ai-hub-adr.
   ADRs are linked to projects via the adr-project property."
  (:require [clojure.string :as str]))

(defn scan-adrs!
  "Queries Logseq for pages tagged logseq-ai-hub-adr.
   Returns Promise<vector of {:page-name :original-name}>."
  []
  (let [query "[:find (pull ?p [:block/name :block/original-name])
               :where [?b :block/page ?p]
                      [?b :block/content ?c]
                      [(clojure.string/includes? ?c \"logseq-ai-hub-adr\")]]"]
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
                  (js/console.warn "ADR scan error:" err)
                  [])))))

(defn parse-adr-properties
  "Given block content string, extracts ADR properties.
   Looks for key:: value lines matching known ADR property keys.
   Returns a map with keyword keys."
  [content]
  (let [lines (str/split-lines content)
        prop-re #"^([a-z][a-z0-9-]*):: (.+)$"
        adr-keys #{"adr-project" "adr-status" "adr-date" "adr-title"}]
    (into {}
      (keep (fn [line]
              (when-let [[_ k v] (re-matches prop-re (str/trim line))]
                (when (adr-keys k)
                  [(keyword k) (str/trim v)])))
            lines))))

(defn extract-adr-sections
  "Given block content string, extracts ## Context, ## Decision, ## Consequences sections.
   Returns a map with :context, :decision, :consequences keys.
   Each value is the trimmed text between headings."
  [content]
  (if (str/blank? content)
    {:context "" :decision "" :consequences ""}
    (let [;; Split on ## heading markers, keeping the heading name
          parts (str/split content #"(?m)^## ")
          ;; Each part is either pre-heading text or "Heading\n...content..."
          section-map (reduce (fn [acc part]
                                (let [newline-idx (str/index-of part "\n")]
                                  (if newline-idx
                                    (let [heading (str/trim (subs part 0 newline-idx))
                                          body (str/trim (subs part (inc newline-idx)))]
                                      (assoc acc (str/lower-case heading) body))
                                    acc)))
                              {}
                              (rest parts))]
      {:context (get section-map "context" "")
       :decision (get section-map "decision" "")
       :consequences (get section-map "consequences" "")})))

(defn parse-adr-page
  "Given page-name and first-block content, returns {:valid true :entry {...}}
   or {:valid false :errors [...]}.
   Valid if :adr-project and :adr-title are present."
  [page-name content]
  (let [props (parse-adr-properties content)
        sections (extract-adr-sections content)
        adr-project (get props :adr-project)
        adr-title (get props :adr-title)]
    (cond
      (str/blank? adr-project)
      {:valid false
       :errors [{:field :adr-project :message "adr-project is required"}]}

      (str/blank? adr-title)
      {:valid false
       :errors [{:field :adr-title :message "adr-title is required"}]}

      :else
      {:valid true
       :entry {:id page-name
               :type :adr
               :name adr-title
               :description ""
               :project adr-project
               :status (get props :adr-status "")
               :date (get props :adr-date "")
               :sections sections
               :properties props
               :source :graph-page}})))

(defn scan-and-parse-adrs!
  "Chains scan-adrs! -> read first block -> parse for each page.
   Filters out nils. Returns Promise<vector of ADR entries>."
  []
  (-> (scan-adrs!)
      (.then (fn [pages]
               (js/Promise.all
                 (clj->js
                   (for [{:keys [page-name original-name]} pages]
                     (let [display-name (or original-name page-name)]
                       (-> (js/logseq.Editor.getPageBlocksTree display-name)
                           (.then (fn [blocks]
                                    (when (and blocks (pos? (.-length blocks)))
                                      (let [first-block (aget blocks 0)
                                            content (.-content first-block)]
                                        (when content
                                          (let [result (parse-adr-page display-name content)]
                                            (when (:valid result)
                                              (:entry result))))))))
                           (.catch (fn [err]
                                     (js/console.warn "Failed to parse ADR page:" display-name err)
                                     nil)))))))))
      (.then (fn [results]
               (vec (filter some? (js->clj results)))))))

(defn handle-adr-list
  "Bridge handler. Params: {\"project\" \"project-name\"}.
   Scans all ADRs, filters by project.
   Returns {:adrs [...] :count N}."
  [params]
  (let [project-filter (get params "project")]
    (-> (scan-and-parse-adrs!)
        (.then (fn [adrs]
                 (let [filtered (if (str/blank? project-filter)
                                  adrs
                                  (filter #(= (:project %) project-filter) adrs))
                       formatted (mapv (fn [adr]
                                         {:title (:name adr)
                                          :project (:project adr)
                                          :status (:status adr)
                                          :date (:date adr)
                                          :sections {:context (get-in adr [:sections :context] "")
                                                     :decision (get-in adr [:sections :decision] "")
                                                     :consequences (get-in adr [:sections :consequences] "")}})
                                       filtered)]
                   {:adrs formatted
                    :count (count formatted)}))))))

(defn- slugify-title
  "Converts a title string to a URL-friendly slug."
  [title]
  (-> title
      (str/lower-case)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn- zero-pad
  "Returns n as a zero-padded string of width 3."
  [n]
  (let [num-str (str n)]
    (str (apply str (repeat (- 3 (count num-str)) "0")) num-str)))

(defn- extract-adr-number
  "Given a page-name like ADR/project/ADR-001-some-title, returns 1.
   Returns 0 if no number found."
  [page-name]
  (if-let [[_ num-str] (re-find #"ADR-(\d{3})" page-name)]
    (js/parseInt num-str 10)
    0))

(defn handle-adr-create
  "Bridge handler. Params: {\"project\" \"name\" \"title\" \"ADR title\"
                             \"context\" \"...\" \"decision\" \"...\"
                             \"consequences\" \"...\" \"status\" \"accepted\"}.
   Auto-numbers ADR, creates page and appends body blocks.
   Returns {:page pageName :adrNumber N :title title}."
  [params]
  (let [project (get params "project" "")
        title (get params "title" "")
        context (get params "context" "")
        decision (get params "decision" "")
        consequences (get params "consequences" "")
        status (get params "status" "proposed")]
    (-> (scan-and-parse-adrs!)
        (.then (fn [existing-adrs]
                 (let [project-adrs (filter #(= (:project %) project) existing-adrs)
                       max-num (reduce (fn [acc adr]
                                         (max acc (extract-adr-number (:id adr))))
                                       0
                                       project-adrs)
                       next-num (inc max-num)
                       padded-num (zero-pad next-num)
                       slug (slugify-title title)
                       page-name (str "ADR/" project "/ADR-" padded-num "-" slug)
                       page-properties (clj->js {"adr-project" project
                                                 "adr-title" title
                                                 "adr-status" status
                                                 "adr-date" (.toISOString (js/Date.))})
                       page-opts (clj->js {:redirect false :createFirstBlock false})]
                   (-> (js/logseq.Editor.createPage page-name page-properties page-opts)
                       (.then (fn [_page]
                                (let [sections [["## Context" context]
                                                ["## Decision" decision]
                                                ["## Consequences" consequences]]]
                                  (reduce (fn [promise-chain [heading body]]
                                            (.then promise-chain
                                                   (fn [_]
                                                     (js/logseq.Editor.appendBlockInPage
                                                       page-name
                                                       (str heading "\n" body)))))
                                          (js/Promise.resolve nil)
                                          sections))))
                       (.then (fn [_]
                                {:page page-name
                                 :adrNumber next-num
                                 :title title})))))))))
