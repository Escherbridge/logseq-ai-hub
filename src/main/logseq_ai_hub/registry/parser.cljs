(ns logseq-ai-hub.registry.parser
  "Parsers for registry page types: tools, prompts, procedures.
   Converts Logseq page content into normalized registry entries.
   Reuses parse-block-properties from job-runner parser."
  (:require [logseq-ai-hub.job-runner.parser :as block-parser]
            [logseq-ai-hub.registry.schemas :as schemas]
            [clojure.string :as str]))

(defn- coerce-property-types
  "Coerces string property values to expected types based on schema."
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

                      :else
                      [k v])))
                props)))

(defn parse-tool-page
  "Parses a tool page's first block content into a registry tool entry.
   Returns {:valid true :entry {...}} or {:valid false :errors [...]}"
  [page-name block-content]
  (let [raw-props (block-parser/parse-block-properties block-content)
        typed-props (coerce-property-types schemas/tool-properties-schema raw-props)
        validation (schemas/validate-properties schemas/tool-properties-schema typed-props)]
    (if (:valid validation)
      {:valid true
       :entry {:id page-name
               :type :tool
               :name (get-in validation [:properties :tool-name])
               :description (get-in validation [:properties :tool-description])
               :properties (:properties validation)
               :source :graph-page}}
      {:valid false
       :errors (:errors validation)
       :page page-name})))

(defn- extract-prompt-sections
  "Extracts ## System and ## User sections from block content body.
   Returns {:system \"...\" :user \"...\"}.
   Content before any ## heading is ignored (that's where properties live)."
  [block-content]
  (let [lines (str/split-lines block-content)
        ;; Remove property lines (key:: value)
        non-prop-lines (remove #(re-matches #"^[a-z-]+::\s*.*$" %) lines)
        ;; Remove tags:: line
        body-lines (remove #(re-matches #"^tags::\s*.*$" %) non-prop-lines)
        body (str/join "\n" body-lines)
        ;; Split by ## headings
        sections (re-seq #"(?m)^##\s+(\S+)\s*\n([\s\S]*?)(?=\n##\s|\z)" body)]
    (reduce (fn [acc [_ heading content]]
              (let [key (keyword (str/lower-case heading))]
                (assoc acc key (str/trim content))))
            {}
            sections)))

(defn parse-prompt-page
  "Parses a prompt page into a registry prompt entry.
   Extracts properties from the first block and ## System/## User sections from body.
   Returns {:valid true :entry {...}} or {:valid false :errors [...]}"
  [page-name block-content]
  (let [raw-props (block-parser/parse-block-properties block-content)
        typed-props (coerce-property-types schemas/prompt-properties-schema raw-props)
        validation (schemas/validate-properties schemas/prompt-properties-schema typed-props)
        sections (extract-prompt-sections block-content)]
    (if (:valid validation)
      {:valid true
       :entry {:id page-name
               :type :prompt
               :name (get-in validation [:properties :prompt-name])
               :description (get-in validation [:properties :prompt-description])
               :arguments (get-in validation [:properties :prompt-arguments])
               :system-section (:system sections)
               :user-section (:user sections)
               :properties (:properties validation)
               :source :graph-page}}
      {:valid false
       :errors (:errors validation)
       :page page-name})))

(defn parse-procedure-page
  "Parses a procedure page into a registry procedure entry.
   Extracts properties and body steps from block content.
   Returns {:valid true :entry {...}} or {:valid false :errors [...]}"
  [page-name block-content]
  (let [raw-props (block-parser/parse-block-properties block-content)
        typed-props (coerce-property-types schemas/procedure-properties-schema raw-props)
        validation (schemas/validate-properties schemas/procedure-properties-schema typed-props)
        ;; Extract body (non-property, non-tag lines) as procedure steps
        lines (str/split-lines block-content)
        body-lines (remove #(or (re-matches #"^[a-z-]+::\s*.*$" %)
                                (re-matches #"^tags::\s*.*$" %))
                           lines)
        body (str/trim (str/join "\n" body-lines))]
    (if (:valid validation)
      {:valid true
       :entry {:id page-name
               :type :procedure
               :name (get-in validation [:properties :procedure-name])
               :description (get-in validation [:properties :procedure-description])
               :requires-approval (= "true" (get-in validation [:properties :procedure-requires-approval]))
               :approval-contact (get-in validation [:properties :procedure-approval-contact])
               :body body
               :properties (:properties validation)
               :source :graph-page}}
      {:valid false
       :errors (:errors validation)
       :page page-name})))

(defn parse-skill-as-tool
  "Converts an existing parsed skill definition into a tool registry entry.
   Skill inputs become JSON schema properties (all typed as string).
   Returns a registry entry map."
  [skill-def]
  (let [props (:properties skill-def)
        inputs (or (:skill-inputs props) [])
        schema-properties (into {}
                                (map (fn [input-name]
                                       [input-name {"type" "string"}])
                                     inputs))
        input-schema {"type" "object"
                      "properties" schema-properties
                      "required" (vec inputs)}]
    {:id (:skill-id skill-def)
     :type :tool
     :name (str "skill_" (last (str/split (:skill-id skill-def) #"/")))
     :description (or (:skill-description props) "")
     :handler :skill
     :skill-id (:skill-id skill-def)
     :input-schema input-schema
     :properties props
     :source :auto-detected}))
