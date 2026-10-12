(ns logseq-ai-hub.job-runner.graph
  (:require [logseq-ai-hub.job-runner.parser :as parser]
            [logseq-ai-hub.util.errors :as errors]
            [clojure.string :as str]))

;; Write queue to serialize graph writes
(defonce write-queue-state (atom {:promise (js/Promise.resolve nil)}))

(defn queue-write!
  "Enqueues a write operation to prevent concurrent conflicts.
   write-fn should be a function that returns a Promise."
  [write-fn]
  (let [new-promise (-> (:promise @write-queue-state)
                        (.then (fn [_] (write-fn)))
                        (.catch (fn [err]
                                  (js/console.error "Write queue error:" err)
                                  nil)))]
    (swap! write-queue-state assoc :promise new-promise)
    new-promise))

(defn- step-block?
  "True for a block with a step-action:: property line."
  [block]
  (boolean (re-find #"(?m)^\s*step-action::" (or (.-content block) ""))))

(defn- extract-block-content
  "[first-block-content step-contents]. Steps are the first block's step children, else
   the later top-level step blocks (see AGENTS.md §job-runner-shape)."
  [blocks]
  (when (and blocks (pos? (.-length blocks)))
    (let [first-block (aget blocks 0)
          child-steps (filter step-block? (array-seq (or (.-children first-block) #js [])))
          step-blocks (if (seq child-steps)
                        child-steps
                        (filter step-block? (rest (array-seq blocks))))]
      [(.-content first-block) (mapv #(.-content %) step-blocks)])))

(defn strip-page-link
  "\"[[Skills/x]]\" -> \"Skills/x\"; anything else is returned trimmed."
  [s]
  (when (string? s)
    (let [t (str/trim s)]
      (if-let [[_ inner] (re-matches #"^\[\[(.+)\]\]$" t)] inner t))))

(defn- flatten-definition
  "Merges :properties into the top level, keeping :properties (AGENTS.md §job-runner-shape)."
  [definition]
  (when definition
    (merge (:properties definition) definition)))

(defn- normalize-job-skill
  [job-def]
  (if-let [skill (:job-skill job-def)]
    (let [page (strip-page-link (str skill))]
      (-> job-def
          (assoc :job-skill page)
          (assoc-in [:properties :job-skill] page)))
    job-def))

(defn read-job-page
  "Reads and parses a job definition from a Logseq page.
   Returns a Promise resolving to parsed job definition or nil if page doesn't exist."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.then (fn [blocks]
               (if-let [[content children] (extract-block-content blocks)]
                 (-> (parser/parse-job-definition content children page-name)
                     flatten-definition
                     normalize-job-skill)
                 nil)))
      (.catch (fn [err]
                (js/console.error "Error reading job page:" err)
                nil))))

(defn read-skill-page
  "Reads and parses a skill definition from a Logseq page.
   Returns a Promise resolving to parsed skill definition or nil if page doesn't exist."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.then (fn [blocks]
               (if-let [[content children] (extract-block-content blocks)]
                 (flatten-definition
                   (parser/parse-skill-definition content children page-name))
                 nil)))
      (.catch (fn [err]
                (js/console.error "Error reading skill page:" err)
                nil))))

(defn scan-job-pages
  "Scans for all job pages starting with the given prefix.
   Returns a Promise resolving to a vector of parsed job definitions."
  [prefix]
  (let [query (str "[:find (pull ?p [:block/name :block/original-name]) "
                   ":where [?p :block/name ?name] "
                   "[(clojure.string/starts-with? ?name \""
                   (str/lower-case prefix) "\")]]")]
    (-> (js/Promise.resolve (js/logseq.DB.datascriptQuery query))
        (.then (fn [results]
                 (let [pages (js->clj results :keywordize-keys true)]
                   (js/Promise.all
                     (clj->js
                       (for [[page-info] pages]
                         (read-job-page (:original-name page-info))))))))
        (.then (fn [job-defs]
                 (vec (filter some? (js->clj job-defs))))))))

(defn scan-skill-pages
  "Scans for all skill pages starting with the given prefix.
   Returns a Promise resolving to a vector of parsed skill definitions."
  [prefix]
  (let [query (str "[:find (pull ?p [:block/name :block/original-name]) "
                   ":where [?p :block/name ?name] "
                   "[(clojure.string/starts-with? ?name \""
                   (str/lower-case prefix) "\")]]")]
    (-> (js/Promise.resolve (js/logseq.DB.datascriptQuery query))
        (.then (fn [results]
                 (let [pages (js->clj results :keywordize-keys true)]
                   (js/Promise.all
                     (clj->js
                       (for [[page-info] pages]
                         (read-skill-page (:original-name page-info))))))))
        (.then (fn [skill-defs]
                 (vec (filter some? (js->clj skill-defs))))))))

(defn- get-page-block-uuid
  "Gets the UUID of the first block on a page."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.then (fn [blocks]
               (when (and blocks (pos? (.-length blocks)))
                 (.-uuid (aget blocks 0)))))))

(defn update-job-status!
  "Updates the job-status property of a job page.
   Uses the write queue to prevent conflicts."
  [page-name status]
  (queue-write!
    (fn []
      (-> (get-page-block-uuid page-name)
          (.then (fn [uuid]
                   (when uuid
                     (js/logseq.Editor.upsertBlockProperty uuid "job-status" status))))))))

(defn update-job-property!
  "Updates an arbitrary property on a job page.
   Uses the write queue to prevent conflicts."
  [page-name property-key value]
  (queue-write!
    (fn []
      (-> (get-page-block-uuid page-name)
          (.then (fn [uuid]
                   (when uuid
                     (js/logseq.Editor.upsertBlockProperty uuid property-key value))))))))

(defn append-job-log!
  "Appends a log entry as a top-level block with timestamp.
   Uses the write queue to prevent conflicts. \"::\" collapses to \":\" so a log line
   (often an error message) can never parse as a property or a step."
  [page-name log-entry]
  (queue-write!
    (fn []
      (let [now (.toISOString (js/Date.))
            content (str (str/replace (str log-entry) #"::+" ":") " [" now "]")]
        (js/logseq.Editor.appendBlockInPage page-name content)))))
