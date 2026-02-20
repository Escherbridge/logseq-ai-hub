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

(defn- extract-block-content
  "Extracts content from a block tree structure.
   Returns a tuple of [first-block-content child-contents]"
  [blocks]
  (when (and blocks (pos? (.-length blocks)))
    (let [first-block (aget blocks 0)
          content (.-content first-block)
          children (.-children first-block)
          child-contents (when children
                          (js->clj
                            (.map children #(.-content %))))]
      [content child-contents])))

(defn read-job-page
  "Reads and parses a job definition from a Logseq page.
   Returns a Promise resolving to parsed job definition or nil if page doesn't exist."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.then (fn [blocks]
               (if-let [[content children] (extract-block-content blocks)]
                 (parser/parse-job-definition page-name content children)
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
                 (parser/parse-skill-definition page-name content children)
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
                         (read-job-page (:block/original-name page-info))))))))
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
                         (read-skill-page (:block/original-name page-info))))))))
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
  "Appends a log entry as a child block with timestamp.
   Uses the write queue to prevent conflicts."
  [page-name log-entry]
  (queue-write!
    (fn []
      (let [now (.toISOString (js/Date.))
            content (str log-entry " [" now "]")]
        (js/logseq.Editor.appendBlockInPage page-name content)))))
