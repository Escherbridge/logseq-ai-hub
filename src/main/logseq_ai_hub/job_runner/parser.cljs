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

(defn- property-types
  "{property-key schema-type} for a property schema."
  [schema]
  (into {} (map (fn [[k spec]] [k (:type spec)])) schema))

(def ^:private job-runner-property-types
  (property-types (merge schemas/job-properties-schema
                         schemas/skill-properties-schema
                         schemas/step-properties-schema)))

(defn- parse-value
  "Parses a raw value by its schema type (see AGENTS.md §job-runner-shape):
   :csv -> vector (JSON array or comma-split); :string/:enum/:integer -> trimmed
   string, commas and brackets kept; other keys -> JSON when it parses, else string."
  [type value]
  (let [trimmed (str/trim value)
        json? (or (str/starts-with? trimmed "{") (str/starts-with? trimmed "["))]
    (case type
      :csv (let [parsed (when json? (try-parse-json trimmed))]
             (if (vector? parsed) parsed (mapv str/trim (str/split trimmed #","))))
      (:string :enum :integer) trimmed
      (if json? (try-parse-json trimmed) trimmed))))

(defn- parse-block-properties*
  [block-content types]
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

      (into {} (map (fn [[k v]] [k (parse-value (get types k) v)]))
            @properties))))

(defn parse-block-properties
  "Extracts key:: value pairs from block content string.
   Returns map of keyword keys to values parsed by the schema's types
   (default: the job/skill/step schemas). Handles multiline values."
  ([block-content]
   (parse-block-properties* block-content job-runner-property-types))
  ([block-content schema]
   (parse-block-properties* block-content (property-types schema))))

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
