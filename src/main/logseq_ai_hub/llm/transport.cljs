(ns logseq-ai-hub.llm.transport
  "Sends OpenAI-compatible LLM requests over the configured llmTransport:
   direct (browser fetch), logseq-desktop (Logseq's host-side request, no CORS)
   or hub-server (the AI Hub server's /api/llm relay).
   See logseq_ai_hub/AGENTS.md §llm-transport."
  (:require [clojure.string :as str]
            [logseq-ai-hub.auth :as auth]
            [logseq-ai-hub.llm.settings :as llm-settings]
            [logseq-ai-hub.llm.response :as llm-response]))

(defn- strip-trailing-slash [s]
  (str/replace s #"/+$" ""))

(defn merge-headers
  "Merges header maps left to right; a later name replaces an earlier one case-insensitively."
  [& header-maps]
  (reduce (fn [merged [k v]]
            (let [lower (str/lower-case k)]
              (assoc (into {} (remove (fn [[existing _]] (= lower (str/lower-case existing)))) merged)
                     k v)))
          {}
          (mapcat seq header-maps)))

(def ^:private header-value-chars
  "HTTP header value bytes: tab and visible Latin-1, no CR/LF/NUL."
  #"^[\t\x20-\x7e\x80-\xff]*$")

(defn- unsafe-header
  "The setting holding a value no HTTP stack will send, or nil. Checked before sending:
   Node's fetch quotes a rejected value in its error, which would carry the secret into the graph."
  [api-key headers]
  (or (when (and api-key (not (re-matches header-value-chars api-key))) "'LLM API Key'")
      (some (fn [[k v]] (when-not (re-matches header-value-chars v)
                          (str "'LLM Extra Headers' value for \"" k "\"")))
            headers)))

(defn- resolve-target
  "{:mode :base :headers} for the current settings, or {:error msg} when they cannot work."
  []
  (let [mode (llm-settings/transport)]
    (case mode
      ("direct" "logseq-desktop")
      (let [{:keys [headers error]} (llm-settings/extra-headers)
            api-key (llm-settings/api-key)
            unsafe (when-not error (unsafe-header api-key headers))]
        (cond
          error {:error error}
          (and (nil? api-key) (empty? headers))
          {:error "LLM API Key is missing. Please check Plugin Settings."}
          unsafe
          {:error (str unsafe " contains a character HTTP headers cannot carry (often a line break "
                       "or zero-width space pasted with it). Re-enter it.")}
          :else
          {:mode mode
           :base (llm-settings/endpoint)
           :headers (merge-headers (when api-key {"Authorization" (str "Bearer " api-key)})
                                   headers)}))

      "hub-server"
      (let [url (some-> (auth/get-server-url) str str/trim)
            jwt? (= "jwt" (auth/get-auth-mode))
            ;; The relay never verifies JWTs, so JWT mode needs a relay token.
            token (or (llm-settings/relay-token)
                      (when-not jwt? (some-> (auth/get-auth-token) str str/trim not-empty)))]
        (cond
          (str/blank? url)
          {:error "LLM Transport is 'hub-server' but 'Webhook Server URL' is empty."}
          (str/blank? token)
          {:error (str "LLM Transport is 'hub-server' but there is no token for the relay. Set "
                       (if jwt?
                         "'LLM Relay Token' (the relay does not accept JWTs)."
                         "'LLM Relay Token' (issued by your admin), or 'Plugin API Token'."))}
          :else
          {:mode mode
           :base (str (strip-trailing-slash url) "/api/llm")
           :headers {"Authorization" (str "Bearer " token)}}))

      {:error (str "Unknown LLM Transport \"" mode "\". Use direct, logseq-desktop or hub-server.")})))

(defn config-error
  "Why the LLM settings cannot produce a request, or nil when they can."
  []
  (:error (resolve-target)))

(defn describe
  "Where LLM requests go under the current settings, for diagnostics."
  []
  (let [{:keys [mode base]} (resolve-target)]
    (case mode
      "logseq-desktop" (str base " (via Logseq desktop)")
      "hub-server" (str base " (AI Hub relay)")
      base)))

(defn request-model
  "Model to send for a settings-derived request. Under hub-server a blank 'LLM Model Name'
   yields nil, so the server's AGENT_MODEL decides."
  []
  (if (= "hub-server" (llm-settings/transport))
    (llm-settings/configured-model)
    (llm-settings/model)))

(defn- parse-json [text]
  (try (js/JSON.parse text) (catch :default _ nil)))

(defn- network-failure?
  "True for fetch's own connection failure (CORS, DNS, refused), not e.g. an invalid header."
  [err]
  (and (instance? js/TypeError err)
       (boolean (re-find #"(?i)^(failed to fetch|networkerror|load failed|fetch failed)"
                         (str (.-message err))))))

(defn- redact-credentials
  "Replaces the configured key, token and extra-header values in `s`."
  [s]
  (let [secrets (->> (concat [(llm-settings/api-key) (llm-settings/relay-token)
                              (some-> (auth/get-auth-token) str str/trim)]
                             (vals (:headers (llm-settings/extra-headers))))
                     (filter #(and (string? %) (>= (count %) 4)))
                     ;; Longest first: a secret containing another must not leave a remainder.
                     (sort-by count >))]
    (reduce (fn [acc secret] (str/replace acc secret "[redacted]")) s secrets)))

(def ^:private desktop-required
  (str "LLM Transport 'logseq-desktop' needs the Logseq desktop app (this host did not run the "
       "request). Use 'direct' or 'hub-server'."))

(defn- host-tls-or-dns-failure?
  "True for Node-side certificate, DNS or connection errors from Logseq's main process."
  [cause]
  (boolean (re-find #"(?i)certificate|self[- ]signed|UNABLE_TO_VERIFY|CERT_|ENOTFOUND|ECONNREFUSED|EAI_AGAIN|ETIMEDOUT"
                    cause)))

(defn- unreachable [mode url err]
  (let [origin (try (.-origin (js/URL. url)) (catch :default _ url))
        cause (redact-credentials (if (instance? js/Error err) (.-message err) (str err)))]
    (js/Error.
      (cond
        (and (= "direct" mode) (network-failure? err))
        (str "could not reach " origin " (" cause "). If this is an organisation gateway "
             "that sends no CORS headers, set 'LLM Transport' to 'logseq-desktop' or 'hub-server'.")
        (= "hub-server" mode)
        (str "could not reach the AI Hub server at " origin " (" cause ").")
        (and (= "logseq-desktop" mode) (host-tls-or-dns-failure? cause))
        (str "could not reach " origin " from the Logseq app (" cause "). Logseq's main process "
             "does not use the system certificate store or system proxy: set a proxy in Logseq's "
             "own settings, or use 'hub-server' for gateways behind internal certificates.")
        :else
        (str "could not send the request to " origin " (" cause ").")))))

(defn- via-fetch
  "Sends with js/fetch; resolves {:ok true :status :data} or {:ok false :status :text}."
  [mode url method headers body]
  (-> (js/fetch url (clj->js (cond-> {:method method :headers headers}
                               body (assoc :body (js/JSON.stringify (clj->js body))))))
      ;; Before .then, so only a failed connection (CORS, DNS, refused) reads as unreachable.
      (.catch (fn [err] (throw (unreachable mode url err))))
      (.then (fn [res]
               (if (.-ok res)
                 (-> (.json res) (.then (fn [data] {:ok true :status (.-status res) :data data})))
                 (-> (.text res) (.then (fn [text] {:ok false :status (.-status res) :text text}))))))))

(defn- body-status
  "An HTTP status a gateway put in its error body (`error.code`: 401 or \"401\"), or nil."
  [data]
  (let [code (some-> data (aget "error") (aget "code"))
        n (cond (number? code) code
                (and (string? code) (re-matches #"\d{3}" code)) (js/parseInt code 10))]
    (when (and n (<= 100 n 599)) n)))

(defn- host-result
  "Normalises a logseq.Request result: an includeResponse envelope, or (Logseq <= 0.10) the bare body.
   With no status, success needs positive evidence (`success?` on the parsed body), and the status
   is taken from the body's error.code when the gateway sent one."
  [success? result]
  (cond
    (nil? result) (throw (js/Error. desktop-required))

    (string? result)
    (let [data (parse-json result)]
      (if (and (some? data) (success? data))
        {:ok true :status nil :data data}
        {:ok false :status (body-status data) :text result}))

    :else
    (let [text (str (or (aget result "body") ""))
          data (parse-json text)
          status (aget result "status")]
      (if (and (true? (aget result "ok")) (some? data))
        {:ok true :status status :data data}
        {:ok false :status status :text text}))))

(defn- via-logseq-desktop
  "Sends through Logseq's main process (logseq.Request), which CORS does not apply to.
   The SDK's Request getter always returns a client, so a host that cannot run the request
   shows up as a nil rejection or a nil result instead."
  [url method headers body success?]
  (if-let [client (some-> js/logseq (aget "Request"))]
    ;; `data` must be an object: Logseq 0.10 JSON.stringifies it itself, so a string double-encodes.
    (-> (.call (aget client "_request") client
               (clj->js (cond-> {:url url :method method :headers headers
                                 :returnType "text" :includeResponse true}
                          body (assoc :data body))))
        (.catch (fn [err]
                  (throw (if (nil? err)
                           (js/Error. desktop-required)
                           (unreachable "logseq-desktop" url err)))))
        (.then (partial host-result success?)))
    (js/Promise.reject (js/Error. desktop-required))))

(defn- send!
  "Sends method+path over the configured transport; resolves {:ok :status :data|:text}.
   `success?` judges a parsed body when the transport reports no status."
  [method path body success?]
  (let [{:keys [error mode base headers]} (resolve-target)]
    (if error
      (js/Promise.reject (js/Error. error))
      (let [url (str base path)
            headers (merge-headers (when body {"Content-Type" "application/json"}) headers)]
        (if (= mode "logseq-desktop")
          (via-logseq-desktop url method headers body success?)
          (via-fetch mode url method headers body))))))

(defn- has-choices? [data] (array? (aget data "choices")))

(defn post-chat!
  "POSTs a chat/completions body; resolves the provider's JSON payload, rejects with a readable Error."
  [body]
  (-> (send! "POST" "/chat/completions" body has-choices?)
      (.then (fn [{:keys [ok status data text]}]
               (if ok
                 data
                 (let [text (redact-credentials (str text))]
                   (js/console.error "LLM provider response body:" text)
                   ;; A gateway may echo the request's credentials in its error body.
                   (throw (js/Error. (llm-response/error-message status text)))))))))

(defn- openrouter? [base]
  (try (= "openrouter.ai" (.-hostname (js/URL. base))) (catch :default _ false)))

(defn probe!
  "Cheapest request that proves the credentials work; resolves {:ok :status :text :checked}.
   OpenRouter's /models ignores auth, so it probes /key; hub-server sends a 1-token completion,
   the only way to exercise the server's own key through the relay. Rejects only when unreachable."
  []
  (let [{:keys [mode base]} (resolve-target)
        [checked request]
        (cond
          (= "hub-server" mode)
          [:chat (send! "POST" "/chat/completions"
                        (cond-> {:messages [{:role "user" :content "ping"}] :max_tokens 1}
                          (request-model) (assoc :model (request-model)))
                        has-choices?)]
          (openrouter? base) [:key (send! "GET" "/key" nil #(some? (aget % "data")))]
          :else [:models (send! "GET" "/models" nil #(array? (aget % "data")))])]
    (.then request (fn [result]
                     (-> result
                         (update :text #(some-> % str redact-credentials))
                         (assoc :checked checked))))))

(defn cleartext-origin
  "The target's origin when credentials would travel over plain http to a non-loopback host, else nil."
  []
  (when-let [base (:base (resolve-target))]
    (try
      (let [u (js/URL. base)]
        (when (and (= "http:" (.-protocol u))
                   (not (contains? #{"localhost" "127.0.0.1" "[::1]"} (.-hostname u))))
          (.-origin u)))
      (catch :default _ nil))))
