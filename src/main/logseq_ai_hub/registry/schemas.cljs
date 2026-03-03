(ns logseq-ai-hub.registry.schemas
  "Property schemas and validation for registry page types (tools, prompts, procedures).
   Reuses validate-properties from job-runner schemas."
  (:require [logseq-ai-hub.job-runner.schemas :as schemas]))

;; Re-export validate-properties for convenience
(def validate-properties schemas/validate-properties)

;; Tool page properties schema
(def tool-properties-schema
  {:tool-name {:required true :type :string}
   :tool-description {:required true :type :string}
   :tool-handler {:required true :type :enum :values #{:mcp-tool :skill :http :graph-query}}
   :tool-input-schema {:required true :type :json}
   ;; Handler-specific properties (all optional)
   :tool-mcp-server {:required false :type :string}
   :tool-mcp-tool {:required false :type :string}
   :tool-skill {:required false :type :string}
   :tool-http-url {:required false :type :string}
   :tool-http-method {:required false :type :string}
   :tool-query {:required false :type :string}})

;; Prompt page properties schema
(def prompt-properties-schema
  {:prompt-name {:required true :type :string}
   :prompt-description {:required true :type :string}
   :prompt-arguments {:required false :type :csv}})

;; Procedure page properties schema
(def procedure-properties-schema
  {:procedure-name {:required true :type :string}
   :procedure-description {:required true :type :string}
   :procedure-requires-approval {:required false :type :string :default "false"}
   :procedure-approval-contact {:required false :type :string}})
