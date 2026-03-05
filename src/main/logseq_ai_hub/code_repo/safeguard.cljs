(ns logseq-ai-hub.code-repo.safeguard
  "Scans the Logseq graph for safeguard policy pages.
   Parses rules and provides bridge handlers for policy evaluation."
  (:require [clojure.string :as str]))

(def ^:private level-names
  {0 "unrestricted"
   1 "standard"
   2 "guarded"
   3 "supervised"
   4 "locked"})

(defn scan-safeguards!
  "Queries Logseq for pages tagged logseq-ai-hub-safeguard.
   Returns Promise<vector of {:page-name :original-name}>."
  []
  (let [query "[:find (pull ?p [:block/name :block/original-name])
               :where [?b :block/page ?p]
                      [?b :block/content ?c]
                      [(clojure.string/includes? ?c \"logseq-ai-hub-safeguard\")]]"]
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
                  (js/console.warn "Safeguard scan error:" err)
                  [])))))

(defn parse-safeguard-properties
  "Given block content string, extracts safeguard properties.
   Looks for key:: value lines matching known safeguard property keys.
   Returns a map with keyword keys."
  [content]
  (let [lines (str/split-lines content)
        prop-re #"^([a-z][a-z0-9-]*):: (.+)$"
        safeguard-keys #{"safeguard-name" "safeguard-project" "safeguard-level"
                         "safeguard-contact" "safeguard-escalation-contact"
                         "safeguard-review-interval" "safeguard-auto-deny-after"}]
    (into {}
      (keep (fn [line]
              (when-let [[_ k v] (re-matches prop-re (str/trim line))]
                (when (safeguard-keys k)
                  [(keyword k) (str/trim v)])))
            lines))))

(defn parse-safeguard-rules
  "Parses the ## Rules section from page body content.
   Each rule line format: - ACTION: description
   where ACTION is one of BLOCK, APPROVE, LOG, NOTIFY.
   Returns vector of {:action :description :pattern}."
  [content]
  (let [lines (str/split-lines content)
        rule-re #"^-\s*(BLOCK|APPROVE|LOG|NOTIFY):\s*(.+)$"]
    (vec (keep (fn [line]
                 (when-let [[_ action desc] (re-matches rule-re (str/trim line))]
                   (let [pattern (second (re-find #"matching\s+(\S+)" desc))]
                     {:action (str/lower-case action)
                      :description (str/trim desc)
                      :pattern pattern})))
               lines))))

(defn parse-safeguard-page
  "Given page-name and first-block content, returns {:valid true :entry {...}}
   or {:valid false :errors [...]}.
   Valid if safeguard-project and safeguard-level are present."
  [page-name content]
  (let [props (parse-safeguard-properties content)
        rules (parse-safeguard-rules content)
        sg-project (get props :safeguard-project)
        sg-level-str (get props :safeguard-level)
        sg-name (or (get props :safeguard-name) page-name)]
    (cond
      (str/blank? sg-project)
      {:valid false
       :errors [{:field :safeguard-project :message "safeguard-project is required"}]}

      (str/blank? sg-level-str)
      {:valid false
       :errors [{:field :safeguard-level :message "safeguard-level is required"}]}

      :else
      (let [level (js/parseInt sg-level-str 10)]
        {:valid true
         :entry {:id page-name
                 :type :safeguard
                 :name sg-name
                 :project sg-project
                 :level level
                 :contact (get props :safeguard-contact)
                 :escalation-contact (get props :safeguard-escalation-contact)
                 :review-interval (get props :safeguard-review-interval)
                 :auto-deny-after (get props :safeguard-auto-deny-after)
                 :rules rules
                 :properties props
                 :source :graph-page}}))))

(defn scan-and-parse-safeguards!
  "Chains scan-safeguards! -> read first block -> parse for each page.
   Filters out nils. Returns Promise<vector of safeguard entries>."
  []
  (-> (scan-safeguards!)
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
                                          (let [result (parse-safeguard-page display-name content)]
                                            (when (:valid result)
                                              (:entry result))))))))
                           (.catch (fn [err]
                                     (js/console.warn "Failed to parse safeguard page:" display-name err)
                                     nil)))))))))
      (.then (fn [results]
               (vec (filter some? (js->clj results)))))))

(defn- default-policy
  "Returns the default safeguard policy for a project."
  [project]
  {:name "default"
   :project project
   :level 1
   :levelName "standard"
   :rules []
   :isDefault true})

(defn handle-safeguard-policy-get
  "Bridge handler. Params: {\"project\" \"name\"}.
   Scans safeguards, finds one matching project.
   If found: returns full policy object with levelName.
   If not found: returns default policy with level 1 (standard)."
  [params]
  (let [project (get params "project" "")]
    (-> (scan-and-parse-safeguards!)
        (.then (fn [safeguards]
                 (if-let [match (first (filter #(= (:project %) project) safeguards))]
                   (assoc match :levelName (get level-names (:level match) "standard"))
                   (default-policy project)))))))

(defn handle-safeguard-audit-append
  "Bridge handler. Params: {\"project\" \"name\" \"operation\" \"...\"
                             \"agent\" \"...\" \"action\" \"allow|block|approve\"
                             \"details\" \"...\"}.
   Appends timestamped audit entry block to Projects/{project}/safeguard-log page.
   Creates page if it doesn't exist.
   Returns {:logged true :page \"Projects/{project}/safeguard-log\"}."
  [params]
  (let [project (get params "project" "")
        operation (get params "operation" "")
        agent (get params "agent" "")
        action (get params "action" "")
        details (get params "details" "")
        log-page (str "Projects/" project "/safeguard-log")
        timestamp (.toISOString (js/Date.))
        block-content (str "[" timestamp "] " action ": " operation
                           " by " agent " — " details)
        page-opts (clj->js {:redirect false :createFirstBlock false})]
    (-> (js/logseq.Editor.createPage log-page #js {} page-opts)
        (.catch (fn [_] nil))
        (.then (fn [_]
                 (js/logseq.Editor.appendBlockInPage log-page block-content)))
        (.then (fn [_]
                 {:logged true
                  :page log-page})))))
