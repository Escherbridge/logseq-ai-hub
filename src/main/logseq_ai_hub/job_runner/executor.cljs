(ns logseq-ai-hub.job-runner.executor
  (:require [logseq-ai-hub.job-runner.interpolation :as interpolation]
            [logseq-ai-hub.job-runner.graph :as graph]
            [logseq-ai-hub.agent :as agent]
            [logseq-ai-hub.util.errors :as errors]
            [logseq-ai-hub.event-hub.http :as http]
            [logseq-ai-hub.event-hub.emit :as emit]
            [clojure.string :as str]))

;; Dynamic vars to avoid circular dependencies
(def ^:dynamic *agent-process-input-fn* nil)
(def ^:dynamic *execute-skill-fn* nil)
(def ^:dynamic *call-mcp-tool-fn* nil)
(def ^:dynamic *read-mcp-resource-fn* nil)
(def ^:dynamic *run-legacy-task-fn* nil)
(def ^:dynamic *ask-human-fn* nil)

;; Step executor registry
(defonce step-executors (atom {}))

(defn register-executor!
  "Registers a step executor for a given action type.
   handler-fn should be (fn [step context] ...) returning a Promise."
  [action-type handler-fn]
  (swap! step-executors assoc action-type handler-fn))

(defn execute-step
  "Executes a step by dispatching to the registered handler.
   Returns a Promise resolving to the step result."
  [step context]
  (let [action-type (:step-action step)
        handler (get @step-executors action-type)]
    (if handler
      (handler step context)
      (js/Promise.reject
        (errors/make-error
          :unknown-executor
          (str "No executor registered for action type: " action-type))))))

;; Helper functions
(defn- interpolate-config
  "Interpolates all string values in step config using context."
  [config context]
  (let [interpolation-context (merge
                                 (:variables context)
                                 (:inputs context)
                                 (:step-results context))]
    (into {}
      (for [[k v] config]
        [k (if (string? v)
             (interpolation/interpolate v interpolation-context)
             v)]))))

(defn- get-step-result
  "Gets a result from a previous step by reference like 'step-1-result'."
  [input-ref step-results]
  (if (string? input-ref)
    (if-let [[_ step-num] (re-matches #"step-(\d+)-result" input-ref)]
      (get step-results (js/parseInt step-num))
      input-ref)
    input-ref))

(defn- resolve-input
  "Resolves an input value, handling step result references."
  [input-value context]
  (if (string? input-value)
    (let [step-results (:step-results context)]
      (get-step-result input-value step-results))
    input-value))

;; Executor implementations

(defn- graph-query-executor
  [step context]
  (let [config (interpolate-config (:step-config step) context)
        query (get config "query")]
    (js/Promise.resolve
      (js/logseq.DB.datascriptQuery query))))

(defn- llm-call-executor
  [step context]
  (let [template (:step-prompt-template step)
        model (or (:step-model step)
                 (.-selectedModel (.-settings js/logseq)))
        interpolation-context (merge
                               (:variables context)
                               (:inputs context)
                               (:step-results context))
        prompt (interpolation/interpolate template interpolation-context)
        process-fn (or *agent-process-input-fn* agent/process-input)]
    (process-fn prompt model)))

(defn- block-insert-executor
  [step context]
  (let [config (interpolate-config (:step-config step) context)
        page (get config "page")
        content (get config "content")]
    (js/logseq.Editor.appendBlockInPage page content)))

(defn- block-update-executor
  [step context]
  (let [config (interpolate-config (:step-config step) context)
        uuid (get config "uuid")
        content (get config "content")]
    (js/logseq.Editor.updateBlock uuid content)))

(defn- page-create-executor
  [step context]
  (let [config (interpolate-config (:step-config step) context)
        name (get config "name")
        content (get config "content")]
    (js/logseq.Editor.createPage name (clj->js {}) (clj->js {}))))

(defn- transform-executor
  [step context]
  (let [config (:step-config step)
        op (get config "op")
        input-ref (get config "input")
        input-value (resolve-input input-ref context)]
    (case op
      "get-in"
      (let [path (get config "path")]
        (js/Promise.resolve (get-in input-value path)))

      "join"
      (let [separator (get config "separator" "")]
        (js/Promise.resolve (str/join separator input-value)))

      "split"
      (let [separator (get config "separator")]
        (js/Promise.resolve (clj->js (str/split input-value (re-pattern separator)))))

      "count"
      (js/Promise.resolve (count input-value))

      "filter"
      (let [predicate (get config "predicate")]
        (js/Promise.resolve
          (clj->js
            (case predicate
              "not-empty" (filter #(and (some? %) (not= "" %)) input-value)
              (vec input-value)))))

      (js/Promise.reject
        (errors/make-error :invalid-transform-op (str "Unknown transform operation: " op))))))

(defn- evaluate-condition
  "Evaluates a condition against an input value."
  [condition input-value config]
  (case condition
    "not-empty"
    (and (some? input-value)
         (or (and (string? input-value) (not= "" input-value))
             (and (seqable? input-value) (not (empty? input-value)))))

    "empty"
    (or (nil? input-value)
        (and (string? input-value) (= "" input-value))
        (and (seqable? input-value) (empty? input-value)))

    "equals"
    (= input-value (get config "value"))

    "contains"
    (if (string? input-value)
      (.includes input-value (get config "value"))
      (some #(= % (get config "value")) input-value))

    "greater-than"
    (> input-value (get config "value"))

    false))

(defn- conditional-executor
  [step context]
  (let [config (:step-config step)
        condition (get config "condition")
        input-ref (get config "input")
        input-value (resolve-input input-ref context)
        then-step (get config "then-step")
        else-step (get config "else-step")
        condition-result (evaluate-condition condition input-value config)]
    (js/Promise.resolve
      (if condition-result
        {:directive :jump :target-step then-step}
        (if else-step
          {:directive :jump :target-step else-step}
          {:directive :continue})))))

(defn- sub-skill-executor
  [step context]
  (if-not *execute-skill-fn*
    (js/Promise.reject
      (errors/make-error :executor-not-initialized "Skill executor not initialized"))
    (let [config (interpolate-config (:step-config step) context)
          skill-id (get config "skill-id")
          inputs (get config "inputs" {})]
      (*execute-skill-fn* skill-id (assoc context :inputs inputs)))))

(defn- legacy-task-executor
  [step context]
  (if-not *run-legacy-task-fn*
    (js/Promise.reject
      (errors/make-error :executor-not-initialized "Legacy task runner not initialized"))
    (let [config (:step-config step)
          task-id (get config "task-id")]
      (*run-legacy-task-fn* task-id))))

(defn- mcp-tool-executor
  [step context]
  (if-not *call-mcp-tool-fn*
    (js/Promise.reject
      (errors/make-error :mcp-not-initialized "MCP client not initialized"))
    (let [server (:step-mcp-server step)
          tool (:step-mcp-tool step)
          config (:step-config step)
          args (interpolate-config config context)]
      (*call-mcp-tool-fn* server tool args))))

(defn- mcp-resource-executor
  [step context]
  (if-not *read-mcp-resource-fn*
    (js/Promise.reject
      (errors/make-error :mcp-not-initialized "MCP client not initialized"))
    (let [server (:step-mcp-server step)
          config (:step-config step)
          resource (get config "resource")]
      (*read-mcp-resource-fn* server resource))))

(defn- ask-human-executor
  "Executor for :ask-human step type.
   Sends a question to a human via the server's approval endpoint
   and waits for their response."
  [step context]
  (if-not *ask-human-fn*
    (js/Promise.reject
      (errors/make-error :ask-human-not-initialized "Ask human function not initialized"))
    (let [config (interpolate-config (:step-config step) context)
          contact (get config "contact")
          question (get config "question")
          options (get config "options")
          timeout (get config "timeout")
          on-timeout (get config "on-timeout" "fail")]
      (-> (*ask-human-fn* {:contact contact
                           :question question
                           :options options
                           :timeout timeout})
          (.then (fn [result]
                   (let [status (.-status result)]
                     (if (and (= status "timeout") (= on-timeout "continue"))
                       {:status "timeout" :response nil :continued true}
                       (if (= status "timeout")
                         (throw (errors/make-error :approval-timeout "Human approval timed out"))
                         {:status status
                          :response (.-response result)})))))))))

;; Register all executors
(register-executor! :graph-query graph-query-executor)
(register-executor! :llm-call llm-call-executor)
(register-executor! :block-insert block-insert-executor)
(register-executor! :block-update block-update-executor)
(register-executor! :page-create page-create-executor)
(register-executor! :transform transform-executor)
(register-executor! :conditional conditional-executor)
(register-executor! :sub-skill sub-skill-executor)
(register-executor! :legacy-task legacy-task-executor)
(register-executor! :mcp-tool mcp-tool-executor)
(register-executor! :mcp-resource mcp-resource-executor)
(register-executor! :ask-human ask-human-executor)
(register-executor! :http-request http/http-request-executor)
(register-executor! :emit-event emit/emit-event-executor)
