(ns logseq-ai-hub.code-repo.pi-agents
  "Pi.dev agent profile page scanner and bridge handlers.
   Agent profiles are pages tagged logseq-ai-hub-pi-agent."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Scan
;; ---------------------------------------------------------------------------

(defn scan-pi-agents!
  "Scans for pages tagged logseq-ai-hub-pi-agent.
   Returns Promise<vector of page maps with :page-name and :original-name>."
  []
  (let [dq "[:find (pull ?p [:block/name :block/original-name])
             :where [?b :block/page ?p]
                    [?b :block/content ?c]
                    [(clojure.string/includes? ?c \"logseq-ai-hub-pi-agent\")]]"]
    (-> (js/logseq.DB.datascriptQuery dq)
        (.then (fn [results]
                 (if (and results (pos? (.-length results)))
                   (let [converted (js->clj results :keywordize-keys true)]
                     (mapv (fn [r]
                             (let [page (first r)]
                               {:page-name     (:block/name page)
                                :original-name (or (:block/original-name page)
                                                   (:block/name page))}))
                           converted))
                   [])))
        (.catch (fn [err]
                  (js/console.warn "Pi-agent scan error:" err)
                  [])))))

;; ---------------------------------------------------------------------------
;; Property parsing
;; ---------------------------------------------------------------------------

(defn parse-pi-agent-properties
  "Extracts pi-agent properties from a page's properties map.
   Props is a plain ClojureScript map with string keys (from Logseq block/properties)."
  [props]
  {:name        (get props "pi-agent-name" "")
   :model       (get props "pi-agent-model" "anthropic/claude-sonnet-4")
   :project     (get props "pi-agent-project" "")
   :description (get props "pi-agent-description" "")})

;; ---------------------------------------------------------------------------
;; Section parsing from block tree
;; ---------------------------------------------------------------------------

(defn- section-heading?
  "Returns the canonical section key if block content starts with the heading, else nil."
  [content]
  (cond
    (str/starts-with? content "## System Instructions") :system-instructions
    (str/starts-with? content "## Skills")              :skills
    (str/starts-with? content "## Allowed Tools")       :allowed-tools
    (str/starts-with? content "## Restricted Operations") :restricted-operations
    :else nil))

(defn- child-content
  "Extracts text content from a block's children (JS block objects).
   Returns a joined string of children's :content fields."
  [block-clj]
  (let [children (:children block-clj)]
    (if (and children (seq children))
      (->> children
           (mapv #(or (:content %) ""))
           (filter (complement str/blank?))
           (str/join "\n"))
      "")))

(defn extract-agent-sections
  "Extracts System Instructions, Skills, Allowed Tools, and Restricted Operations
   sections from block tree content.
   Accepts a JS array of block objects (each with :content and optional :children).
   Returns a map with :system-instructions, :skills, :allowed-tools, :restricted-operations."
  [blocks]
  (if (or (nil? blocks) (zero? (.-length blocks)))
    {:system-instructions ""
     :skills ""
     :allowed-tools ""
     :restricted-operations ""}
    (let [clj-blocks (js->clj blocks :keywordize-keys true)]
      (reduce (fn [acc block]
                (let [content (or (:content block) "")
                      key (section-heading? content)]
                  (if key
                    (assoc acc key (child-content block))
                    acc)))
              {:system-instructions ""
               :skills ""
               :allowed-tools ""
               :restricted-operations ""}
              clj-blocks))))

;; ---------------------------------------------------------------------------
;; Full page parser
;; ---------------------------------------------------------------------------

(defn parse-pi-agent-page
  "Parses a pi-agent page into a complete profile map.
   page-name: string page name.
   props: map with string keys (Logseq page properties).
   blocks: JS array of block objects from getPageBlocksTree."
  [page-name props blocks]
  (let [base     (parse-pi-agent-properties props)
        sections (extract-agent-sections blocks)]
    (merge base
           {:page                page-name
            :system-instructions (:system-instructions sections)
            :skills              (:skills sections)
            :allowed-tools       (:allowed-tools sections)
            :restricted-operations (:restricted-operations sections)})))

;; ---------------------------------------------------------------------------
;; Internal: fetch full agent profile
;; ---------------------------------------------------------------------------

(defn- fetch-agent-profile!
  "Fetches and fully parses a pi-agent page.
   page-name: the lowercased Logseq page name.
   display-name: the original-cased page name for API calls.
   Returns Promise<agent-profile-map or nil>."
  [page-name display-name]
  (-> (js/logseq.Editor.getPage display-name)
      (.then (fn [page-obj]
               (if page-obj
                 (let [props (or (some-> page-obj
                                         (js->clj :keywordize-keys true)
                                         :block/properties)
                                 {})]
                   (-> (js/logseq.Editor.getPageBlocksTree display-name)
                       (.then (fn [blocks]
                                (parse-pi-agent-page display-name props blocks)))))
                 nil)))
      (.catch (fn [err]
                (js/console.warn "Failed to fetch pi-agent page:" display-name err)
                nil))))

;; ---------------------------------------------------------------------------
;; Bridge handlers
;; ---------------------------------------------------------------------------

(defn handle-pi-agent-list
  "Returns all pi.dev agent profiles. Params: {\"project\" (optional filter)}
   Returns Promise<{:agents [...] :count N}>."
  [params]
  (let [project-filter (get params "project")]
    (-> (scan-pi-agents!)
        (.then (fn [pages]
                 (js/Promise.all
                   (clj->js
                     (for [{:keys [page-name original-name]} pages]
                       (let [display-name (or original-name page-name)]
                         (fetch-agent-profile! page-name display-name)))))))
        (.then (fn [results]
                 (let [agents   (vec (filter some? (js->clj results)))
                       filtered (if (str/blank? project-filter)
                                  agents
                                  (filter #(= (:project %) project-filter) agents))]
                   {:agents (vec filtered)
                    :count  (count filtered)}))))))

(defn handle-pi-agent-get
  "Returns a single pi.dev agent profile by name. Params: {\"name\" \"agent-name\"}
   Returns Promise<agent-profile-map>."
  [params]
  (let [agent-name (str/trim (or (get params "name") ""))]
    (if (str/blank? agent-name)
      (js/Promise.reject (js/Error. "Missing required parameter: name"))
      (let [page-name (str "PI-Agents/" agent-name)]
        (-> (js/logseq.Editor.getPage page-name)
            (.then (fn [page-obj]
                     (if page-obj
                       (let [props (or (some-> page-obj
                                               (js->clj :keywordize-keys true)
                                               :block/properties)
                                       {})]
                         (-> (js/logseq.Editor.getPageBlocksTree page-name)
                             (.then (fn [blocks]
                                      (parse-pi-agent-page page-name props blocks)))))
                       (js/Promise.reject (js/Error. (str "Agent not found: " agent-name))))))
            (.catch (fn [err]
                      (js/Promise.reject err))))))))

(defn handle-pi-agent-create
  "Creates a new pi.dev agent profile page.
   Params: {\"name\" \"agent-name\" \"project\" \"proj\" \"model\" \"...\" \"description\" \"...\"
            \"systemInstructions\" \"...\" \"skills\" \"...\" \"allowedTools\" \"...\"
            \"restrictedOperations\" \"...\"}
   Returns Promise<{:page page-name :created true}>."
  [params]
  (let [agent-name  (str/trim (or (get params "name") ""))
        project     (str/trim (or (get params "project") ""))
        model       (str/trim (or (get params "model") "anthropic/claude-sonnet-4"))
        description (str/trim (or (get params "description") ""))
        sys-instr   (str/trim (or (get params "systemInstructions") ""))
        skills      (str/trim (or (get params "skills") ""))
        allowed     (str/trim (or (get params "allowedTools") ""))
        restricted  (str/trim (or (get params "restrictedOperations") ""))]
    (if (str/blank? agent-name)
      (js/Promise.reject (js/Error. "Missing required parameter: name"))
      (let [page-name  (str "PI-Agents/" agent-name)
            page-props (clj->js {"pi-agent-name"        agent-name
                                 "pi-agent-project"     project
                                 "pi-agent-model"       model
                                 "pi-agent-description" description
                                 "tags"                 "logseq-ai-hub-pi-agent"})
            page-opts  (clj->js {:redirect false :createFirstBlock false})
            sections   [["## System Instructions" sys-instr]
                        ["## Skills"              skills]
                        ["## Allowed Tools"       allowed]
                        ["## Restricted Operations" restricted]]]
        (-> (js/logseq.Editor.createPage page-name page-props page-opts)
            (.then (fn [_page]
                     (reduce (fn [promise-chain [heading body]]
                               (.then promise-chain
                                      (fn [_]
                                        (js/logseq.Editor.appendBlockInPage
                                          page-name
                                          (str heading "\n" body)))))
                             (js/Promise.resolve nil)
                             sections)))
            (.then (fn [_]
                     {:page    page-name
                      :created true})))))))

(defn handle-pi-agent-update
  "Updates an existing pi.dev agent profile page properties.
   Params: {\"name\" \"agent-name\" + optional fields: \"model\" \"description\" \"project\"}
   Returns Promise<{:name agent-name :updated true}>."
  [params]
  (let [agent-name (str/trim (or (get params "name") ""))]
    (if (str/blank? agent-name)
      (js/Promise.reject (js/Error. "Missing required parameter: name"))
      (let [page-name  (str "PI-Agents/" agent-name)
            update-map (cond-> {}
                         (get params "model")
                         (assoc "pi-agent-model" (get params "model"))

                         (get params "description")
                         (assoc "pi-agent-description" (get params "description"))

                         (get params "project")
                         (assoc "pi-agent-project" (get params "project")))]
        (-> (js/logseq.Editor.getPage page-name)
            (.then (fn [page-obj]
                     (if page-obj
                       (reduce (fn [promise-chain [prop-key prop-val]]
                                 (.then promise-chain
                                        (fn [_]
                                          (js/logseq.Editor.upsertBlockProperty
                                            (.-uuid page-obj)
                                            prop-key
                                            prop-val))))
                               (js/Promise.resolve nil)
                               update-map)
                       (js/Promise.reject (js/Error. (str "Agent not found: " agent-name))))))
            (.then (fn [_]
                     {:name    agent-name
                      :updated true}))
            (.catch (fn [err]
                      (js/Promise.reject err))))))))
