(ns logseq-ai-hub.job-runner.schemas
  "Property schemas and validation for jobs, skills, and steps.")

;; Job properties schema
(def job-properties-schema
  {:job-type {:required true :type :enum :values #{:autonomous :manual :scheduled :event-driven}}
   :job-status {:required true :type :enum :values #{:draft :queued :running :completed :failed :cancelled :paused}}
   :job-priority {:required false :type :integer :min 1 :max 5 :default 3}
   :job-schedule {:required false :type :string}
   :job-depends-on {:required false :type :csv}  ;; comma-separated values -> vector
   :job-max-retries {:required false :type :integer :default 0}
   :job-retry-count {:required false :type :integer :default 0}
   :job-skill {:required false :type :string}  ;; page ref
   :job-input {:required false :type :json}  ;; JSON string -> map
   :job-created-at {:required true :type :string}  ;; ISO timestamp
   :job-started-at {:required false :type :string}
   :job-completed-at {:required false :type :string}
   :job-error {:required false :type :string}
   :job-result {:required false :type :string}})

;; Skill properties schema
(def skill-properties-schema
  {:skill-type {:required true :type :enum :values #{:llm-chain :tool-chain :composite :mcp-tool}}
   :skill-version {:required true :type :integer}
   :skill-description {:required true :type :string}
   :skill-inputs {:required true :type :csv}
   :skill-outputs {:required true :type :csv}
   :skill-tags {:required false :type :csv}})

;; Step properties schema
(def step-properties-schema
  {:step-order {:required true :type :integer}
   :step-action {:required true :type :enum :values #{:graph-query :llm-call :block-insert :block-update :page-create :mcp-tool :mcp-resource :transform :conditional :sub-skill :legacy-task}}
   :step-config {:required false :type :json}
   :step-prompt-template {:required false :type :string}
   :step-model {:required false :type :string}
   :step-mcp-server {:required false :type :string}
   :step-mcp-tool {:required false :type :string}})

(def step-action-types (:values (:step-action step-properties-schema)))

(defn- validate-enum [prop-name spec value]
  (let [allowed-values (:values spec)]
    (if (contains? allowed-values value)
      nil
      {:field prop-name
       :error (str "Invalid enum value. Expected one of " allowed-values ", got: " value)})))

(defn- validate-integer [prop-name spec value]
  (cond
    (not (integer? value))
    {:field prop-name
     :error (str "Expected integer, got: " (type value))}

    (and (:min spec) (< value (:min spec)))
    {:field prop-name
     :error (str "Value " value " is less than minimum " (:min spec))}

    (and (:max spec) (> value (:max spec)))
    {:field prop-name
     :error (str "Value " value " is greater than maximum " (:max spec))}

    :else nil))

(defn- validate-string [prop-name spec value]
  (if (string? value)
    nil
    {:field prop-name
     :error (str "Expected string, got: " (type value))}))

(defn- validate-csv [prop-name spec value]
  (if (vector? value)
    nil
    {:field prop-name
     :error (str "Expected vector (parsed CSV), got: " (type value))}))

(defn- validate-json [prop-name spec value]
  (if (map? value)
    nil
    {:field prop-name
     :error (str "Expected map (parsed JSON), got: " (type value))}))

(defn- validate-property [prop-name spec value]
  (case (:type spec)
    :enum (validate-enum prop-name spec value)
    :integer (validate-integer prop-name spec value)
    :string (validate-string prop-name spec value)
    :csv (validate-csv prop-name spec value)
    :json (validate-json prop-name spec value)
    {:field prop-name :error (str "Unknown type: " (:type spec))}))

(defn validate-properties
  "Validates properties against a schema.
   Returns {:valid true :properties coerced-props} or {:valid false :errors [...]}"
  [schema props]
  (let [errors (atom [])
        coerced-props (atom {})]

    ;; Check required fields and validate existing ones
    (doseq [[prop-name spec] schema]
      (let [value (get props prop-name)]
        (cond
          ;; Required field missing
          (and (:required spec) (nil? value))
          (swap! errors conj {:field prop-name :error "Required field missing"})

          ;; Optional field missing, apply default if exists
          (and (nil? value) (contains? spec :default))
          (swap! coerced-props assoc prop-name (:default spec))

          ;; Field exists, validate it
          (not (nil? value))
          (if-let [error (validate-property prop-name spec value)]
            (swap! errors conj error)
            (swap! coerced-props assoc prop-name value)))))

    (if (empty? @errors)
      {:valid true :properties @coerced-props}
      {:valid false :errors @errors})))
