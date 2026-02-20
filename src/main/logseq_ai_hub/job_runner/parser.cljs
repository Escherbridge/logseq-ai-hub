(ns logseq-ai-hub.job-runner.parser
  "Parsers for job and skill definitions from Logseq blocks."
  (:require [logseq-ai-hub.job-runner.schemas :as schemas]
            [clojure.string :as str]))

(defn- try-parse-json
  "Attempts to parse a string as JSON. Returns parsed value or original string on failure."
  [s]
  (try
    (js->clj (js/JSON.parse s))
    (catch :default _
      s)))

(defn- detect-and-parse-value
  "Detects value type and parses accordingly:
   - JSON objects/arrays (starts with { or [) -> parse to map/vector
   - Comma-separated values -> split to vector
   - Otherwise -> return as string"
  [value]
  (let [trimmed (str/trim value)]
    (cond
      ;; JSON object or array
      (or (str/starts-with? trimmed "{")
          (str/starts-with? trimmed "["))
      (try-parse-json trimmed)

      ;; CSV - contains comma
      (str/includes? trimmed ",")
      (mapv str/trim (str/split trimmed #","))

      ;; Known CSV fields (even without commas) - convert to single-element vector
      ;; This handles cases like "skill-inputs:: single-value"
      :else
      trimmed)))

(defn parse-block-properties
  "Extracts key:: value pairs from block content string.
   Returns map of keyword keys to parsed values.
   Handles multiline values, JSON, and CSV."
  [block-content]
  (if (or (nil? block-content) (str/blank? block-content))
    {}
    (let [lines (str/split-lines block-content)
          properties (atom {})
          current-key (atom nil)
          current-value (atom [])]

      (doseq [line lines]
        (if-let [[_ key value] (re-matches #"^([a-z-]+)::\s*(.*)$" line)]
          (do
            ;; Save previous property if exists
            (when @current-key
              (swap! properties assoc @current-key
                     (str/trim (str/join " " @current-value))))
            ;; Start new property
            (reset! current-key (keyword key))
            (reset! current-value [value]))
          ;; Continuation line (no ::)
          (when @current-key
            (swap! current-value conj (str/trim line)))))

      ;; Save last property
      (when @current-key
        (swap! properties assoc @current-key
               (str/trim (str/join " " @current-value))))

      ;; Post-process values: detect and parse JSON/CSV
      (into {} (map (fn [[k v]]
                      (let [parsed (detect-and-parse-value v)]
                        ;; Special handling for known CSV fields
                        (if (and (string? parsed)
                                 (#{:skill-inputs :skill-outputs :skill-tags
                                    :job-depends-on} k))
                          [k [parsed]]  ;; Wrap single value in vector
                          [k parsed])))
                    @properties)))))

(defn- coerce-property-types
  "Coerces string property values to their expected types based on schema."
  [schema props]
  (into {} (map (fn [[k v]]
                  (let [spec (get schema k)]
                    (cond
                      ;; Enum types: convert string to keyword
                      (and spec (= :enum (:type spec)) (string? v))
                      [k (keyword v)]

                      ;; Integer types: parse string to int
                      (and spec (= :integer (:type spec)) (string? v))
                      [k (js/parseInt v 10)]

                      ;; Already parsed or no schema
                      :else
                      [k v])))
                props)))

(defn parse-job-definition
  "Parses a job definition from block content.
   Returns {:job-id page-name :properties {...} :steps [...] :valid true/false :errors [...]}"
  [first-block-content child-block-contents page-name]
  (let [;; Parse first block properties
        raw-props (parse-block-properties first-block-content)
        ;; Coerce types
        typed-props (coerce-property-types schemas/job-properties-schema raw-props)
        ;; Validate
        validation (schemas/validate-properties schemas/job-properties-schema typed-props)

        ;; Parse child blocks as steps
        child-blocks (or child-block-contents [])
        steps (mapv (fn [block-content]
                      (let [raw-step (parse-block-properties block-content)
                            typed-step (coerce-property-types schemas/step-properties-schema raw-step)
                            step-validation (schemas/validate-properties
                                             schemas/step-properties-schema
                                             typed-step)]
                        (if (:valid step-validation)
                          (:properties step-validation)
                          ;; Return invalid step with errors
                          (assoc typed-step :_validation-errors (:errors step-validation)))))
                    child-blocks)

        ;; Sort steps by step-order
        sorted-steps (sort-by :step-order steps)

        ;; Collect all validation errors (job + steps)
        step-errors (mapcat :_validation-errors sorted-steps)
        all-errors (concat (:errors validation []) step-errors)

        ;; Remove validation error keys from steps
        clean-steps (mapv #(dissoc % :_validation-errors) sorted-steps)]

    (if (empty? all-errors)
      {:job-id page-name
       :properties (:properties validation)
       :steps clean-steps
       :valid true}
      {:job-id page-name
       :properties typed-props
       :steps clean-steps
       :valid false
       :errors all-errors})))

(defn parse-skill-definition
  "Parses a skill definition from block content.
   Returns {:skill-id page-name :properties {...} :steps [...] :valid true/false :errors [...]}"
  [first-block-content child-block-contents page-name]
  (let [;; Parse first block properties
        raw-props (parse-block-properties first-block-content)
        ;; Coerce types
        typed-props (coerce-property-types schemas/skill-properties-schema raw-props)
        ;; Validate
        validation (schemas/validate-properties schemas/skill-properties-schema typed-props)

        ;; Parse child blocks as steps
        child-blocks (or child-block-contents [])
        steps (mapv (fn [block-content]
                      (let [raw-step (parse-block-properties block-content)
                            typed-step (coerce-property-types schemas/step-properties-schema raw-step)
                            step-validation (schemas/validate-properties
                                             schemas/step-properties-schema
                                             typed-step)]
                        (if (:valid step-validation)
                          (:properties step-validation)
                          ;; Return invalid step with errors
                          (assoc typed-step :_validation-errors (:errors step-validation)))))
                    child-blocks)

        ;; Sort steps by step-order
        sorted-steps (sort-by :step-order steps)

        ;; Collect all validation errors (skill + steps)
        step-errors (mapcat :_validation-errors sorted-steps)
        all-errors (concat (:errors validation []) step-errors)

        ;; Remove validation error keys from steps
        clean-steps (mapv #(dissoc % :_validation-errors) sorted-steps)]

    (if (empty? all-errors)
      {:skill-id page-name
       :properties (:properties validation)
       :steps clean-steps
       :valid true}
      {:skill-id page-name
       :properties typed-props
       :steps clean-steps
       :valid false
       :errors all-errors})))
