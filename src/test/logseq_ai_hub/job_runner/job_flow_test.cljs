(ns logseq-ai-hub.job-runner.job-flow-test
  "create_skill -> create_job -> execute-job! driven end-to-end on the fake Logseq
   host (real datascript), with the runner wired as job-runner.init wires it. Only
   the LLM provider's HTTP endpoint is faked. See src/main/logseq_ai_hub/AGENTS.md
   §job-runner-shape."
  (:require [cljs.test :refer-macros [deftest is async]]
            [logseq-ai-hub.agent-bridge :as bridge]
            [logseq-ai-hub.fake-logseq :as fake]
            [logseq-ai-hub.job-runner.engine :as engine]
            [logseq-ai-hub.job-runner.executor :as executor]
            [logseq-ai-hub.job-runner.graph :as graph]
            [logseq-ai-hub.job-runner.queue :as queue]
            [logseq-ai-hub.job-runner.runner :as runner]
            [logseq-ai-hub.job-runner.scheduler :as scheduler]))

(defn- over-the-wire [x]
  (-> x clj->js js/JSON.stringify js/JSON.parse (js->clj :keywordize-keys true)))

(defn- op [operation params]
  (-> (bridge/run-operation (get bridge/operation-handlers operation) params)
      (.then over-the-wire)))

(defn- op-error
  "Resolves to the rejection message; a resolved op is itself a test failure."
  [operation params]
  (-> (op operation params)
      (.then (fn [result] (is false (str operation " should have failed, got " (pr-str result))) "")
             (fn [err] (or (.-message err) (str err))))))

(defn- wire-real-runner!
  "Wires runner and engine to the real graph/engine/queue/executor, as job-runner.init
   does. Returns a fn restoring the previous wiring (set!, not with-redefs: async)."
  []
  (let [saved {:logseq js/logseq
               :fetch js/fetch
               :step engine/executor-execute-step
               :process-input executor/*agent-process-input-fn*
               :read-job runner/graph-read-job-page
               :read-skill runner/graph-read-skill-page
               :scan-jobs runner/graph-scan-job-pages
               :status runner/graph-update-job-status!
               :property runner/graph-update-job-property!
               :log runner/graph-append-job-log!
               :queue-write runner/graph-queue-write!
               :engine runner/engine-execute-skill-with-retries
               :make-queue runner/queue-make-queue
               :enqueue runner/queue-enqueue
               :dequeue runner/queue-dequeue
               :remove runner/queue-remove-from-queue
               :size runner/queue-queue-size
               :find runner/queue-find-in-queue}]
    (engine/set-executor-execute-step! executor/execute-step)
    (set! executor/*agent-process-input-fn* nil)        ; executor_test leaves a mock here
    (runner/init-runner!
     {:graph {:read-job-page graph/read-job-page
              :read-skill-page graph/read-skill-page
              :scan-job-pages graph/scan-job-pages
              :update-job-status! graph/update-job-status!
              :update-job-property! graph/update-job-property!
              :append-job-log! graph/append-job-log!}
      :engine {:execute-skill-with-retries engine/execute-skill-with-retries}
      :queue {:make-queue queue/make-queue
              :enqueue queue/enqueue
              :dequeue queue/dequeue
              :remove-from-queue queue/remove-from-queue
              :queue-size queue/queue-size
              :find-in-queue queue/find-in-queue}})
    (fn restore! []
      (set! js/logseq (:logseq saved))
      (set! js/fetch (:fetch saved))
      (set! engine/executor-execute-step (:step saved))
      (set! executor/*agent-process-input-fn* (:process-input saved))
      (set! runner/graph-read-job-page (:read-job saved))
      (set! runner/graph-read-skill-page (:read-skill saved))
      (set! runner/graph-scan-job-pages (:scan-jobs saved))
      (set! runner/graph-update-job-status! (:status saved))
      (set! runner/graph-update-job-property! (:property saved))
      (set! runner/graph-append-job-log! (:log saved))
      (set! runner/graph-queue-write! (:queue-write saved))
      (set! runner/engine-execute-skill-with-retries (:engine saved))
      (set! runner/queue-make-queue (:make-queue saved))
      (set! runner/queue-enqueue (:enqueue saved))
      (set! runner/queue-dequeue (:dequeue saved))
      (set! runner/queue-remove-from-queue (:remove saved))
      (set! runner/queue-queue-size (:size saved))
      (set! runner/queue-find-in-queue (:find saved))
      (runner/reset-runner!))))

(defn- fake-llm-fetch
  "An OpenAI-compatible chat-completions endpoint that replies with the prompt it got.
   The reply has a comma so job-result exercises the parser's string handling."
  [requests]
  (fn [url opts]
    (let [body (js->clj (js/JSON.parse (aget opts "body")) :keywordize-keys true)
          prompt (:content (last (:messages body)))]
      (swap! requests conj {:url url :model (:model body) :prompt prompt})
      (js/Promise.resolve
       #js {:ok true
            :status 200
            :json (fn []
                    (js/Promise.resolve
                     (clj->js {:choices [{:message {:content (str "Otter digest, in brief: " prompt)}
                                          :finish_reason "stop"}]})))}))))

(defn- failing-llm-fetch
  "A chat-completions endpoint answering HTTP 500 with an OpenAI-style error body."
  [requests]
  (fn [url _opts]
    (swap! requests conj url)
    (js/Promise.resolve
     #js {:ok false
          :status 500
          :text (fn [] (js/Promise.resolve "{\"error\":{\"message\":\"Upstream overloaded\"}}"))})))

(def ^:private test-settings
  #js {"llmApiKey" "test-key"
       "llmEndpoint" "https://llm.test/v1"
       "llmModel" "provider/some-model"
       "jobPagePrefix" "Jobs/"
       "skillPagePrefix" "Skills/"})

(def ^:private otter-skill
  {"name" "otter-digest"
   "type" "llm-chain"
   "description" "One-line digest of a topic"
   "inputs" ["topic"]
   "outputs" ["digest"]
   "steps" [{"order" 1
             "action" "llm-call"
             "model" "llm-model"
             "prompt_template" "Summarize {{topic}} in one line"}]})

(defn- sleep [ms] (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- wait-until
  "Resolves true once (pred) holds, false if the deadline passes first."
  [pred deadline]
  (cond
    (pred) (js/Promise.resolve true)
    (> (js/Date.now) deadline) (js/Promise.resolve false)
    :else (.then (sleep 5) (fn [_] (wait-until pred deadline)))))

(defn- gated-llm-fetch
  "fake-llm-fetch whose replies are held until release! is called, so a run can be
   caught in flight. Returns [fetch release!]."
  [requests]
  (let [open! (atom nil)
        opened (js/Promise. (fn [resolve] (reset! open! resolve)))
        reply (fake-llm-fetch requests)]
    [(fn [url opts] (let [response (reply url opts)] (.then opened (fn [_] response))))
     (fn release! [] (@open! nil))]))

(defn- flow-failed [finish]
  (fn [err]
    (is false (str "flow failed: " (or (.-message err) (pr-str err))))
    (finish)))

(defn- settled-job
  "get_job once the job is completed/failed and its queued writes have flushed;
   returns whatever get_job says at the deadline otherwise."
  [job-name deadline]
  (-> (op "get_job" {"jobId" job-name})
      (.then (fn [job]
               (cond
                 (#{"completed" "failed"} (:status job))
                 (-> (:promise @graph/write-queue-state)
                     (.then (fn [_] (op "get_job" {"jobId" job-name}))))

                 (> (js/Date.now) deadline) job

                 :else (.then (sleep 10) (fn [_] (settled-job job-name deadline))))))))

(deftest skill-authored-over-the-bridge-runs-as-a-job
  (async done
    (let [restore! (wire-real-runner!)
          _ (fake/install!)
          requests (atom [])
          finish (fn [& _] (restore!) (done))]
      (aset js/logseq "settings" test-settings)
      (set! js/fetch (fake-llm-fetch requests))
      (-> (op "create_skill" otter-skill)
          (.then (fn [created]
                   (is (= {:skillId "Skills/otter-digest" :name "otter-digest" :steps 1} created))
                   (op "create_job" {"name" "otters-today"
                                     "type" "autonomous"
                                     "skill" "[[Skills/otter-digest]]"
                                     "input" {"topic" "sea otters"}})))
          (.then (fn [job]
                   (is (= "queued" (:status job)))
                   (runner/execute-job! "Jobs/otters-today")))
          ;; job-status/job-result writes go through the write queue, not execute-job!'s promise
          (.then (fn [_] (:promise @graph/write-queue-state)))
          (.then (fn [_] (op "get_job" {"jobId" "otters-today"})))
          (.then (fn [job]
                   (is (= "completed" (:status job)) (str "job error: " (:error job)))
                   (is (= 1 (count @requests)) "one provider call for the one llm-call step")
                   (is (= {:url "https://llm.test/v1/chat/completions" :model "provider/some-model"}
                          (select-keys (first @requests) [:url :model])))
                   (is (= "Summarize sea otters in one line" (:prompt (first @requests)))
                       "{{topic}} is filled from job-input")
                   (is (= "Otter digest, in brief: Summarize sea otters in one line" (:result job))
                       "job-result is the provider's reply, read back as text despite the comma")
                   (is (= "autonomous" (:type job)))
                   (is (= "Skills/otter-digest" (:skill job)) "[[link]] job-skill resolves to the page name")
                   (is (= {:topic "sea otters"} (:input job)))
                   (op "get_skill" {"skillId" "otter-digest"})))
          (.then (fn [skill]
                   (is (= "llm-chain" (:type skill)))
                   (is (= "One-line digest of a topic" (:description skill)))
                   (is (= ["topic"] (:inputs skill)))
                   (is (= [{:step-order 1
                            :step-action "llm-call"
                            :step-model "llm-model"
                            :step-prompt-template "Summarize {{topic}} in one line"}]
                          (:steps skill)))
                   (op "list_jobs" {"status" "completed"})))
          (.then (fn [listed]
                   (is (= [["otters-today" "autonomous"]]
                          (map (juxt :name :type) (:jobs listed))))))
          (.then finish
                 (fn [err]
                   (is false (str "flow failed: " (or (.-message err) (pr-str err))))
                   (finish)))))))

(deftest queued-job-runs-through-the-poll-loop
  ;; No execute-job! call: the job must get from create_job's enqueue to completed
  ;; through start-runner! + poll-tick! alone (AGENTS.md §job-runner-queue).
  (async done
    (let [restore! (wire-real-runner!)
          _ (fake/install!)
          requests (atom [])
          finish (fn [& _] (runner/stop-runner!) (restore!) (done))]
      (aset js/logseq "settings" test-settings)
      (set! js/fetch (fake-llm-fetch requests))
      (runner/update-config! {:poll-interval-ms 5})
      (-> (runner/start-runner!)
          (.then (fn [_] (op "create_skill" otter-skill)))
          (.then (fn [_] (op "create_job" {"name" "otters-polled"
                                           "type" "autonomous"
                                           "skill" "[[Skills/otter-digest]]"
                                           "input" {"topic" "sea otters"}})))
          (.then (fn [_] (settled-job "otters-polled" (+ (js/Date.now) 3000))))
          (.then (fn [job]
                   (is (= "completed" (:status job)) (str "job error: " (:error job)))
                   (is (= "Otter digest, in brief: Summarize sea otters in one line" (:result job)))
                   (is (= 1 (count @requests)) "the job runs once, not once per tick")
                   (is (= 0 (:queued (runner/runner-status))))))
          (.then finish
                 (fn [err]
                   (is false (str "flow failed: " (or (.-message err) (pr-str err))))
                   (finish)))))))

(deftest start-job-during-a-run-does-not-run-it-twice
  ;; create_job enqueues, the poll loop claims the job, then start_job arrives while
  ;; the provider call is in flight (AGENTS.md §job-runner-queue, running guard).
  (async done
    (let [restore! (wire-real-runner!)
          _ (fake/install!)
          requests (atom [])
          [gated-fetch release!] (gated-llm-fetch requests)
          finish (fn [& _] (runner/stop-runner!) (restore!) (done))]
      (aset js/logseq "settings" test-settings)
      (set! js/fetch gated-fetch)
      (runner/update-config! {:poll-interval-ms 5})
      (-> (runner/start-runner!)
          (.then (fn [_] (op "create_skill" otter-skill)))
          (.then (fn [_] (op "create_job" {"name" "otters-once"
                                           "type" "autonomous"
                                           "skill" "Skills/otter-digest"
                                           "input" {"topic" "sea otters"}})))
          (.then (fn [created]
                   (is (= "queued" (:status created)))
                   (wait-until #(= 1 (count @requests)) (+ (js/Date.now) 3000))))
          (.then (fn [in-flight?]
                   (is in-flight? "the poll loop started the job")
                   (op "start_job" {"jobId" "otters-once"})))
          (.then (fn [started]
                   (is (= "running" (:status started)) "start_job reports the run in progress")
                   (release!)
                   (settled-job "otters-once" (+ (js/Date.now) 3000))))
          (.then (fn [job]
                   (is (= "completed" (:status job)) (str "job error: " (:error job)))
                   (sleep 50)))                 ; ~10 ticks for a re-queued copy to start
          (.then (fn [_]
                   (is (= 1 (count @requests)) "exactly one provider request: the job ran once")
                   (is (= {:queued 0 :running 0} (select-keys (runner/runner-status) [:queued :running])))))
          (.then finish (flow-failed finish))))))

(deftest only-autonomous-and-event-driven-jobs-run-on-create
  (async done
    (let [restore! (wire-real-runner!)
          _ (fake/install!)
          requests (atom [])
          finish (fn [& _]
                   (runner/stop-runner!)
                   (scheduler/unregister-schedule! "Jobs/otters-nightly")
                   (restore!)
                   (done))]
      (aset js/logseq "settings" test-settings)
      (set! js/fetch (fake-llm-fetch requests))
      (runner/update-config! {:poll-interval-ms 5})
      (-> (runner/start-runner!)
          (.then (fn [_] (op "create_skill" otter-skill)))
          (.then (fn [_] (op "create_job" {"name" "otters-by-hand" "type" "manual"
                                           "skill" "Skills/otter-digest"
                                           "input" {"topic" "sea otters"}})))
          (.then (fn [manual]
                   (is (= "created" (:status manual)))
                   (op "create_job" {"name" "otters-nightly" "type" "scheduled"
                                     "schedule" "0 3 * * *"
                                     "skill" "Skills/otter-digest"
                                     "input" {"topic" "sea otters"}})))
          (.then (fn [scheduled]
                   (is (= "scheduled" (:status scheduled)))
                   (is (= "0 3 * * *" (get-in (scheduler/list-schedules) ["Jobs/otters-nightly" :cron-expr]))
                       "the scheduler holds the cron for the new job")
                   (sleep 50)))                 ; ~10 poll ticks
          (.then (fn [_] (op "list_jobs" {})))
          (.then (fn [listed]
                   (is (= 0 (count @requests)) "neither job ran on create")
                   (is (= #{["otters-by-hand" "draft"] ["otters-nightly" "draft"]}
                          (set (map (juxt :name :status) (:jobs listed)))))
                   (op "start_job" {"jobId" "otters-by-hand"})))
          (.then (fn [started]
                   (is (= "queued" (:status started)))
                   (settled-job "otters-by-hand" (+ (js/Date.now) 3000))))
          (.then (fn [job]
                   (is (= "completed" (:status job)) (str "job error: " (:error job)))
                   (is (= 1 (count @requests)) "start_job ran the manual job; the scheduled one stayed put")))
          (.then finish (flow-failed finish))))))

(deftest llm-call-step-model-chooses-the-provider-model
  (async done
    (let [restore! (wire-real-runner!)
          _ (fake/install!)
          requests (atom [])
          finish (fn [& _] (restore!) (done))
          cases [["no step model -> the LLM Model setting" "model-absent" {} "provider/some-model"]
                 ["registered id llm-model -> the LLM Model setting" "model-registered"
                  {"model" "llm-model"} "provider/some-model"]
                 ["provider model name -> the request's model" "model-override"
                  {"model" "anthropic/claude-sonnet-4"} "anthropic/claude-sonnet-4"]]]
      (aset js/logseq "settings" test-settings)
      (set! js/fetch (fake-llm-fetch requests))
      (-> (reduce
           (fn [chain [label skill step-extra expected-model]]
             (.then chain
                    (fn [_]
                      (-> (op "create_skill" {"name" skill "type" "llm-chain" "description" label
                                              "inputs" ["topic"] "outputs" ["out"]
                                              "steps" [(merge {"order" 1 "action" "llm-call"
                                                               "prompt_template" "Hi {{topic}}"}
                                                              step-extra)]})
                          (.then (fn [_] (op "create_job" {"name" skill "type" "manual"
                                                           "skill" (str "Skills/" skill)
                                                           "input" {"topic" "otters"}})))
                          (.then (fn [_] (runner/execute-job! (str "Jobs/" skill))))
                          (.then (fn [_] (:promise @graph/write-queue-state)))
                          (.then (fn [_] (op "get_job" {"jobId" skill})))
                          (.then (fn [job]
                                   (is (= "completed" (:status job)) (str label ": " (:error job)))
                                   (is (= expected-model (:model (last @requests))) label)))))))
           (js/Promise.resolve nil)
           cases)
          (.then finish (flow-failed finish))))))

(deftest create-skill-accepts-both-step-key-spellings
  ;; MCP sends snake_case step keys; the REST route (server/src/validation/skills.ts) sends camelCase.
  (async done
    (fake/install!)
    (let [cases [["snake_case (MCP)" "keys-snake"
                  {"prompt_template" "Find {{q}}" "mcp_server" "search" "mcp_tool" "find"}]
                 ["camelCase (REST)" "keys-camel"
                  {"promptTemplate" "Find {{q}}" "mcpServer" "search" "mcpTool" "find"}]]]
      (-> (reduce
           (fn [chain [label skill step-keys]]
             (.then chain
                    (fn [_]
                      (-> (op "create_skill" {"name" skill "type" "mcp-tool" "description" label
                                              "inputs" ["q"] "outputs" ["hits"]
                                              "steps" [(merge {"order" 1 "action" "mcp-tool"} step-keys)]})
                          (.then (fn [_] (op "get_skill" {"skillId" skill})))
                          (.then (fn [read-back]
                                   (is (= [{:step-order 1
                                            :step-action "mcp-tool"
                                            :step-mcp-server "search"
                                            :step-mcp-tool "find"
                                            :step-prompt-template "Find {{q}}"}]
                                          (:steps read-back))
                                       label)))))))
           (js/Promise.resolve nil)
           cases)
          (.then (fn [_] (done))
                 (fn [err] (is false (str err)) (done)))))))

(deftest failed-runs-record-why-in-job-error
  (async done
    (let [restore! (wire-real-runner!)
          _ (fake/install!)
          requests (atom [])
          finish (fn [& _] (restore!) (done))
          cases [["provider HTTP 500" "flaky"
                  [{"order" 1 "action" "llm-call" "model" "llm-model" "prompt_template" "Hi {{topic}}"}]
                  #"Upstream overloaded"]
                 ["skill with no steps" "hollow" [] #"has no steps"]]]
      (aset js/logseq "settings" test-settings)
      (set! js/fetch (failing-llm-fetch requests))
      (-> (reduce
           (fn [chain [label skill steps expected]]
             (.then chain
                    (fn [_]
                      (-> (op "create_skill" {"name" skill "type" "llm-chain" "description" label
                                              "inputs" ["topic"] "outputs" ["out"] "steps" steps})
                          (.then (fn [_] (op "create_job" {"name" skill "type" "manual"
                                                           "skill" (str "Skills/" skill)
                                                           "input" {"topic" "otters"}})))
                          (.then (fn [_] (runner/execute-job! (str "Jobs/" skill))))
                          (.then (fn [_] (:promise @graph/write-queue-state)))
                          (.then (fn [_] (op "get_job" {"jobId" skill})))
                          (.then (fn [job]
                                   (is (= "failed" (:status job)) label)
                                   (is (re-find expected (str (:error job)))
                                       (str label ": job-error was " (pr-str (:error job))))))))))
           (js/Promise.resolve nil)
           cases)
          (.then (fn [_] (is (= 1 (count @requests)) "only the llm-call skill reaches the provider")))
          (.then finish
                 (fn [err]
                   (is false (str "flow failed: " (or (.-message err) (pr-str err))))
                   (finish)))))))

(deftest step-layouts
  (async done
    (let [conn (fake/install!)]
      (fake/seed! conn [{:name "Skills/nested"
                         :blocks [["skill-type:: llm-chain"
                                   "step-order:: 2\nstep-action:: transform"
                                   "step-order:: 1\nstep-action:: llm-call"]]}
                        {:name "Skills/top-level"
                         :blocks ["skill-type:: llm-chain"
                                  "step-order:: 2\nstep-action:: transform"
                                  "A note about this skill"
                                  "step-order:: 1\nstep-action:: llm-call"
                                  "Step 1 completed (retry: 0) [2026-10-11T00:00:00Z]"]}
                        {:name "Skills/noted"
                         :blocks [["skill-type:: llm-chain" "A nested note, not a step"]
                                  "step-order:: 2\nstep-action:: transform"
                                  "step-order:: 1\nstep-action:: llm-call"]}])
      ;; A logged error quoting a property line must not turn into a step.
      (graph/append-job-log! "Skills/top-level" "Job failed: provider said\nstep-action:: llm-call")
      (-> (:promise @graph/write-queue-state)
          (.then (fn [_]
                   (js/Promise.all #js [(graph/read-skill-page "Skills/nested")
                                        (graph/read-skill-page "Skills/top-level")
                                        (graph/read-skill-page "Skills/noted")])))
          (.then (fn [[nested top-level noted]]
                   (doseq [[label skill] [["children of the properties block" nested]
                                          ["top-level blocks after it, non-step blocks and logs ignored" top-level]
                                          ["top-level blocks when the first block's children are notes" noted]]]
                     (is (= [[1 :llm-call] [2 :transform]]
                            (mapv (juxt :step-order :step-action) (:steps skill)))
                         label)
                     (is (= :llm-chain (:skill-type skill) (get-in skill [:properties :skill-type]))
                         (str label ": properties are flat and still nested")))
                   (done)))
          (.catch (fn [err] (is false (str err)) (done)))))))

(deftest create-rejects-unwritable-definitions
  (async done
    (fake/seed! (fake/install!)
                [{:name "Jobs/existing" :blocks ["job-type:: manual\njob-status:: completed\njob-skill:: Skills/x"]}])
    (let [cases [["job page already exists" "create_job"
                  {"name" "existing" "type" "autonomous" "skill" "Skills/x"}
                  #"Job page already exists: Jobs/existing"]
                 ["scheduled job without a cron schedule" "create_job"
                  {"name" "no-cron" "type" "scheduled" "skill" "Skills/x"}
                  #"needs a 5-field cron"]
                 ["unknown skill type" "create_skill"
                  {"name" "bad-type" "type" "prompt-chain" "description" "x" "steps" []}
                  #"Invalid skill type \"prompt-chain\""]
                 ["unknown step action" "create_skill"
                  {"name" "bad-action" "type" "llm-chain" "description" "x"
                   "steps" [{"order" 1 "action" "llm_call"}]}
                  #"step 1 has invalid action \"llm_call\""]
                 ["step without an order" "create_skill"
                  {"name" "no-order" "type" "llm-chain" "description" "x"
                   "steps" [{"action" "llm-call"}]}
                  #"step 1 needs an integer \"order\""]
                 ["unknown job type" "create_job"
                  {"name" "bad-job" "type" "one-shot" "skill" "Skills/x"}
                  #"Invalid job type \"one-shot\""]]]
      (-> (js/Promise.all
           (clj->js (for [[label operation params expected] cases]
                      (-> (op-error operation params)
                          (.then (fn [message] (is (re-find expected message) (str label ": " message))))))))
          (.then (fn [_]
                   (js/Promise.all
                    (clj->js (for [page ["Skills/bad-type" "Skills/bad-action" "Skills/no-order" "Jobs/bad-job"
                                         "Jobs/no-cron"]]
                               (js/logseq.Editor.getPage page))))))
          (.then (fn [pages]
                   (is (every? nil? (array-seq pages)) "a rejected definition writes no page")
                   (done)))
          (.catch (fn [err] (is false (str err)) (done)))))))
