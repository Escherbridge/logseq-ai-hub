(ns logseq-ai-hub.job-runner.openclaw
  "OpenClaw skill format interoperability.
   Provides import/export of skill definitions in OpenClaw-compatible JSON format."
  (:require [clojure.string :as str]))

;;; ============================================================================
;;; Constants and Configuration
;;; ============================================================================

(def openclaw-required-fields
  "Required fields in OpenClaw JSON format."
  #{:name :type :steps})

(def valid-skill-types
  "Valid skill type values."
  #{"llm-chain" "tool-chain" "composite" "mcp-tool"})

(def valid-step-actions
  "Valid step action values."
  #{"graph-query" "llm-call" "block-insert" "block-update" "page-create"
    "mcp-tool" "mcp-resource" "transform" "conditional" "sub-skill"})

;;; ============================================================================
;;; Dynamic Dependencies (for testing/injection)
;;; ============================================================================

(def ^:dynamic graph-read-skill-page
  "Dynamic var for graph read-skill-page dependency.
   Should be bound to a function: [page-name] -> Promise<skill-def or nil>"
  nil)

;;; ============================================================================
;;; Validation
;;; ============================================================================

(defn validate-openclaw-json
  "Validates an OpenClaw JSON map.
   Returns {:valid true} or {:valid false :errors [\"...\"]}"
  [json-map]
  (let [errors (atom [])]
    ;; Check required fields
    (doseq [field openclaw-required-fields]
      (when-not (contains? json-map field)
        (swap! errors conj (str "Missing required field: " (name field)))))

    ;; Check valid type
    (when (and (contains? json-map :type)
               (not (contains? valid-skill-types (:type json-map))))
      (swap! errors conj (str "Invalid skill type: " (:type json-map)
                              ". Must be one of: " (str/join ", " valid-skill-types))))

    ;; Check steps is non-empty array
    (cond
      (not (contains? json-map :steps))
      nil ;; Already reported as missing required field

      (not (sequential? (:steps json-map)))
      (swap! errors conj "Field 'steps' must be an array")

      (empty? (:steps json-map))
      (swap! errors conj "Field 'steps' must be a non-empty array")

      :else
      ;; Validate each step
      (doseq [[idx step] (map-indexed vector (:steps json-map))]
        (when-not (contains? step :order)
          (swap! errors conj (str "Step at index " idx " missing required field 'order'")))
        (when-not (contains? step :action)
          (swap! errors conj (str "Step at index " idx " missing required field 'action'")))
        (when (and (contains? step :action)
                   (not (contains? valid-step-actions (:action step))))
          (swap! errors conj (str "Step at index " idx " has invalid action: " (:action step)
                                  ". Must be one of: " (str/join ", " valid-step-actions))))))

    (if (empty? @errors)
      {:valid true}
      {:valid false :errors @errors})))

;;; ============================================================================
;;; Import (OpenClaw -> Logseq)
;;; ============================================================================

(defn- extract-skill-id
  "Extracts skill ID from OpenClaw name, ensuring Skills/ prefix."
  [name]
  (if (str/starts-with? name "Skills/")
    name
    (str "Skills/" name)))

(defn- strip-skill-prefix
  "Strips Skills/ prefix from skill ID to get OpenClaw name."
  [skill-id]
  (if (str/starts-with? skill-id "Skills/")
    (subs skill-id 7)
    skill-id))

(defn openclaw->logseq
  "Converts an OpenClaw JSON map to a Logseq skill definition map.

   Maps:
   - name -> skill-id (with Skills/ prefix)
   - type -> skill-type
   - description -> skill-description
   - inputs -> skill-inputs
   - outputs -> skill-outputs
   - tags -> skill-tags
   - steps -> steps (with :step-order, :step-action as keyword, :step-config)

   Unmapped fields (version, author, metadata) are stored in :openclaw-meta."
  [json-map]
  (let [;; Core skill fields
        skill-def {:skill-id (extract-skill-id (:name json-map))
                   :skill-type (:type json-map)}

        ;; Optional fields (only include if present)
        optional-fields (cond-> {}
                          (contains? json-map :description)
                          (assoc :skill-description (:description json-map))

                          (contains? json-map :inputs)
                          (assoc :skill-inputs (:inputs json-map))

                          (contains? json-map :outputs)
                          (assoc :skill-outputs (:outputs json-map))

                          (contains? json-map :tags)
                          (assoc :skill-tags (:tags json-map)))

        ;; Convert steps
        steps (mapv (fn [step]
                      {:step-order (:order step)
                       :step-action (keyword (:action step))
                       :step-config (:config step)})
                    (:steps json-map))

        ;; Extract metadata fields
        meta-fields (select-keys json-map [:version :author :metadata])
        openclaw-meta (when (seq meta-fields) meta-fields)]

    (cond-> (merge skill-def optional-fields {:steps steps})
      openclaw-meta
      (assoc :openclaw-meta openclaw-meta))))

(defn import-skill
  "Parses and validates an OpenClaw JSON string, converting to Logseq skill def.
   Returns {:ok skill-def} on success or {:error \"...\"} on failure."
  [json-str]
  (try
    (let [json-map (js->clj (js/JSON.parse json-str) :keywordize-keys true)
          validation (validate-openclaw-json json-map)]
      (if (:valid validation)
        {:ok (openclaw->logseq json-map)}
        {:error (str "Validation failed: " (str/join "; " (:errors validation)))}))
    (catch :default e
      {:error (str "Failed to parse JSON: " (.-message e))})))

(defn import-skill-to-graph!
  "Imports an OpenClaw skill JSON string and creates the page in Logseq graph.
   Returns a Promise that resolves when the skill page is created."
  [json-str]
  (let [import-result (import-skill json-str)]
    (if-let [error (:error import-result)]
      (js/Promise.reject (js/Error. error))
      (let [skill-def (:ok import-result)
            skill-id (:skill-id skill-def)]
        (-> (js/logseq.Editor.createPage skill-id)
            (.then (fn [page]
                     ;; Append skill definition as properties
                     (let [page-uuid (.-uuid page)
                           properties-block (str "skill-type:: " (:skill-type skill-def))]
                       (js/logseq.Editor.appendBlockInPage
                        page-uuid
                        properties-block
                        (clj->js {:properties (clj->js (dissoc skill-def :skill-id))})))))
            (.then (fn [_] skill-def)))))))

;;; ============================================================================
;;; Export (Logseq -> OpenClaw)
;;; ============================================================================

(defn logseq->openclaw
  "Converts a Logseq skill definition map to OpenClaw JSON map.

   Reverse of openclaw->logseq mapping. Merges :openclaw-meta back into export.
   Adds defaults for missing fields: version \"1.0.0\", author \"logseq-ai-hub\"."
  [skill-def]
  (let [;; Core fields
        openclaw {:name (strip-skill-prefix (:skill-id skill-def))
                  :type (:skill-type skill-def)}

        ;; Optional fields
        optional (cond-> {}
                   (:skill-description skill-def)
                   (assoc :description (:skill-description skill-def))

                   (:skill-inputs skill-def)
                   (assoc :inputs (:skill-inputs skill-def))

                   (:skill-outputs skill-def)
                   (assoc :outputs (:skill-outputs skill-def))

                   (:skill-tags skill-def)
                   (assoc :tags (:skill-tags skill-def)))

        ;; Convert steps
        steps (mapv (fn [step]
                      {:order (:step-order step)
                       :action (name (:step-action step))
                       :config (:step-config step)})
                    (:steps skill-def))

        ;; Merge metadata or use defaults
        meta-fields (if-let [meta (:openclaw-meta skill-def)]
                      meta
                      {:version "1.0.0"
                       :author "logseq-ai-hub"})]

    (merge openclaw
           optional
           {:steps steps}
           meta-fields)))

(defn export-skill
  "Converts a Logseq skill definition to an OpenClaw JSON string."
  [skill-def]
  (let [openclaw-map (logseq->openclaw skill-def)]
    (js/JSON.stringify (clj->js openclaw-map) nil 2)))

(defn export-skill-from-graph!
  "Reads a skill page from the Logseq graph and exports to OpenClaw JSON.
   Returns a Promise<json-string>.

   Uses dynamic var graph-read-skill-page for dependency injection."
  [skill-page-name]
  (if-not graph-read-skill-page
    (js/Promise.reject (js/Error. "graph-read-skill-page not bound"))
    (-> (graph-read-skill-page skill-page-name)
        (.then (fn [skill-def]
                 (if skill-def
                   (export-skill (js->clj skill-def :keywordize-keys true))
                   (throw (js/Error. (str "Skill page not found: " skill-page-name)))))))))
