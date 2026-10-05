(ns logseq-ai-hub.llm.response
  "Turns an OpenAI-compatible chat/completions payload into usable text or a diagnosis.

   Neither `.ok` nor `choices[0].message.content` is sufficient on its own:
   gateways return HTTP 200 carrying an {\"error\": ...} envelope and no choices,
   and reasoning models return content=null with the text in `reasoning` whenever
   the reasoning budget is exhausted. Reading only `.content` reported those as
   'Empty response from model.' while the model had in fact produced output."
  (:require [clojure.string :as str]))

(defn error-message
  "Human-readable message for a non-2xx body. Never echoes a raw JSON envelope:
   the provider's own `error.message` is the part a user can act on."
  [status body-text]
  (let [parsed (try (js/JSON.parse body-text) (catch :default _ nil))
        msg    (some-> parsed (aget "error") (aget "message"))]
    (cond
      (not (str/blank? msg))
      (str "provider said \"" msg "\" (HTTP " status ")")

      ;; Not JSON — a short snippet helps (an HTML error page, a proxy notice).
      (and (not (str/blank? body-text))
           (not (str/starts-with? (str/triml body-text) "{")))
      (str "HTTP " status " — " (subs body-text 0 (min 200 (count body-text))))

      :else
      (str "HTTP " status " with no error message"))))

(defn- provider-error
  "The `error.message` carried by a 200 response, or nil."
  [data]
  (let [msg (some-> data (aget "error") (aget "message"))]
    (when-not (str/blank? msg) msg)))

(defn extract-text
  "Returns {:text s} with the usable reply, or {:error msg} with a diagnosis."
  [data]
  (if-let [perr (provider-error data)]
    {:error (str "the provider said \"" perr "\"")}
    (let [choices (some-> data (aget "choices"))]
      (if (or (nil? choices) (zero? (alength choices)))
        {:error "the provider returned no choices, so there is no reply to insert"}
        (let [message   (some-> (aget choices 0) (aget "message"))
              finish    (some-> (aget choices 0) (aget "finish_reason"))
              content   (some-> message (aget "content"))
              reasoning (some-> message (aget "reasoning"))
              body      (cond
                          (not (str/blank? content)) content
                          ;; Reasoning models put the text here when they run out
                          ;; of budget before emitting a final answer.
                          (not (str/blank? reasoning))
                          (str reasoning
                               "\n\n> ℹ️ The model returned only its reasoning, no final answer.")
                          :else nil)]
          (cond
            (nil? body)
            {:error (str "the provider returned no usable content"
                         (when finish (str " (finish_reason: " finish ")"))
                         " — the model may have spent its whole token budget on reasoning")}

            (= finish "length")
            {:text (str body
                        "\n\n> ⚠️ This reply was cut off — the model hit its token limit. "
                        "Shorten the prompt or raise the model's max tokens.")}

            :else {:text body}))))))
