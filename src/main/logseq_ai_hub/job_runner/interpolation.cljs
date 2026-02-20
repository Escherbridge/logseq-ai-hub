(ns logseq-ai-hub.job-runner.interpolation
  "Template string interpolation for job runner prompts and configs."
  (:require [clojure.string :as str]))

(defn- parse-step-result-ref
  "Parses step-N-result pattern and returns step number, or nil if not a match."
  [var-name]
  (when-let [[_ step-num] (re-matches #"step-(\d+)-result" var-name)]
    (js/parseInt step-num 10)))

(defn- lookup-variable
  "Looks up a variable in the context.
   Checks in order: step-results (for step-N-result pattern), inputs, variables.
   Returns empty string if not found."
  [var-name context]
  (if (nil? context)
    ""
    (let [;; Check if it's a step result reference
          step-num (parse-step-result-ref var-name)
          step-results (:step-results context)
          inputs (:inputs context)
          variables (:variables context)]
      (cond
        ;; Step result reference
        (and step-num step-results)
        (get step-results step-num "")

        ;; Input reference
        (and inputs (contains? inputs var-name))
        (get inputs var-name "")

        ;; Variable reference (keyword key)
        (and variables (contains? variables (keyword var-name)))
        (get variables (keyword var-name) "")

        ;; Not found
        :else
        ""))))

(defn interpolate
  "Interpolates template string with values from context.
   Context map:
   {:inputs {\"key\" \"value\"}           - input parameters
    :step-results {1 \"result\" 2 \"...\"}  - step outputs by step number
    :variables {:today \"...\" :now \"...\"} - system variables}

   Placeholders: {{variable-name}}
   - {{query}} -> looks up in :inputs
   - {{step-1-result}} -> looks up step 1 in :step-results
   - {{today}} -> looks up :today in :variables
   - Missing variables are replaced with empty string
   - Single-pass only (no recursive resolution)"
  [template-str context]
  (if (or (nil? template-str) (str/blank? template-str))
    ""
    (let [placeholder-regex #"\{\{([^\s}]+)\}\}"]
      (str/replace template-str placeholder-regex
                   (fn [[_ var-name]]
                     (lookup-variable var-name context))))))
