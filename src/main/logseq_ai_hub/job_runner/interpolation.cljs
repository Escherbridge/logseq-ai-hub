(ns logseq-ai-hub.job-runner.interpolation
  "Template string interpolation for job runner prompts and configs."
  (:require [clojure.string :as str]))

;; Dynamic var for secret resolution — set during init to secrets/get-secret.
;; Uses dynamic var to avoid circular dependency between interpolation and secrets modules.
(def ^:dynamic *resolve-secret*
  "Function to resolve a secret key to its value. Set during system init.
   Signature: (fn [key] value-or-nil)"
  (fn [_key] nil))

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

        ;; Secret reference ({{secret.KEY_NAME}})
        (str/starts-with? var-name "secret.")
        (let [secret-key (subs var-name 7)
              value (*resolve-secret* secret-key)]
          (if value
            value
            (do
              (js/console.warn "Secret not found:" secret-key)
              "")))

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
   - {{secret.KEY_NAME}} -> resolves secret from vault via *resolve-secret*
   - Missing variables are replaced with empty string
   - Single-pass only (no recursive resolution)"
  [template-str context]
  (if (or (nil? template-str) (str/blank? template-str))
    ""
    (let [placeholder-regex #"\{\{([^\s}]+)\}\}"]
      (str/replace template-str placeholder-regex
                   (fn [[_ var-name]]
                     (lookup-variable var-name context))))))

(defn interpolate-with-metadata
  "Like interpolate, but returns a map with metadata about secret usage.
   Returns: {:result \"interpolated string\"
             :contains-secrets boolean
             :secret-keys #{\"KEY1\" \"KEY2\"}}
   Use this when the result might be logged or stored — callers can check
   :contains-secrets to decide whether to redact."
  [template-str context]
  (if (or (nil? template-str) (str/blank? template-str))
    {:result "" :contains-secrets false :secret-keys #{}}
    (let [secret-keys-used (atom #{})
          placeholder-regex #"\{\{([^\s}]+)\}\}"
          result (str/replace template-str placeholder-regex
                              (fn [[_ var-name]]
                                (if (str/starts-with? var-name "secret.")
                                  (let [secret-key (subs var-name 7)
                                        value (*resolve-secret* secret-key)]
                                    (when value
                                      (swap! secret-keys-used conj secret-key))
                                    (if value
                                      value
                                      (do
                                        (js/console.warn "Secret not found:" secret-key)
                                        "")))
                                  (lookup-variable var-name context))))]
      {:result result
       :contains-secrets (boolean (seq @secret-keys-used))
       :secret-keys @secret-keys-used})))
