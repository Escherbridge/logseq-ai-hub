(ns logseq-ai-hub.event-hub.http
  "HTTP request step executor for the job runner.
   Provides :http-request step action with URL allowlisting,
   HTTPS enforcement, template interpolation, and AbortController timeouts."
  (:require [logseq-ai-hub.job-runner.interpolation :as interpolation]
            [logseq-ai-hub.util.errors :as errors]
            [clojure.string :as str]))

;; =============================================================================
;; URL Allowlist
;; =============================================================================

(defn- parse-hostname
  "Extracts hostname from a URL string. Returns nil on parse failure."
  [url-str]
  (try
    (let [url (js/URL. url-str)]
      (.-hostname url))
    (catch js/Error _
      nil)))

(defn- parse-protocol
  "Extracts protocol from a URL string (e.g. \"http:\" or \"https:\").
   Returns nil on parse failure."
  [url-str]
  (try
    (let [url (js/URL. url-str)]
      (.-protocol url))
    (catch js/Error _
      nil)))

(defn- matches-pattern?
  "Checks if a hostname matches a single allowlist pattern.
   Supports exact match (e.g. \"api.github.com\") and
   subdomain wildcard (e.g. \"*.example.com\")."
  [hostname pattern]
  (cond
    ;; Wildcard pattern: *.example.com
    (str/starts-with? pattern "*.")
    (let [suffix (subs pattern 1)] ;; ".example.com"
      (str/ends-with? hostname suffix))

    ;; Exact match
    :else
    (= hostname pattern)))

(defn url-allowed?
  "Checks if a URL is allowed by the allowlist.
   - Empty allowlist means all URLs are allowed.
   - Supports exact match (\"api.github.com\") and wildcard (\"*.example.com\").
   - Rejects http:// unless hostname is localhost or 127.0.0.1."
  [url-str allowlist]
  (let [hostname (parse-hostname url-str)
        protocol (parse-protocol url-str)]
    (cond
      ;; Unparseable URL
      (nil? hostname) false

      ;; Reject http:// for non-localhost
      (and (= protocol "http:")
           (not (contains? #{"localhost" "127.0.0.1"} hostname)))
      false

      ;; Empty allowlist = all allowed
      (or (nil? allowlist) (empty? allowlist)) true

      ;; Check against patterns
      :else
      (boolean (some #(matches-pattern? hostname %) allowlist)))))

;; =============================================================================
;; Interpolation Helpers
;; =============================================================================

(defn interpolate-map
  "Recursively interpolates all string values in a map using the
   interpolation engine. Non-string values are passed through."
  [m context]
  (when m
    (into {}
      (for [[k v] m]
        [k (cond
             (string? v) (interpolation/interpolate v context)
             (map? v) (interpolate-map v context)
             :else v)]))))

;; =============================================================================
;; Response Parsing
;; =============================================================================

(defn parse-response
  "Extracts {:status N :ok bool :headers {} :body (parsed-json or text)}
   from a js/fetch Response object. Returns a Promise."
  [response]
  (let [status (.-status response)
        ok (.-ok response)
        headers (let [h (.-headers response)
                      result (atom {})]
                  (.forEach h (fn [v k] (swap! result assoc k v)))
                  @result)
        content-type (get headers "content-type" "")]
    (-> (if (str/includes? content-type "application/json")
          (-> (.json response)
              (.then (fn [json] (js->clj json :keywordize-keys true))))
          (.text response))
        (.then (fn [body]
                 {:status status
                  :ok ok
                  :headers headers
                  :body body})))))

;; =============================================================================
;; Settings Access
;; =============================================================================

(defn- get-http-allowlist
  "Reads the httpAllowlist setting (JSON array of domain patterns).
   Returns a vector of pattern strings, or [] if not configured."
  []
  (let [raw (aget js/logseq "settings" "httpAllowlist")]
    (if (and raw (not (str/blank? raw)))
      (try
        (js->clj (js/JSON.parse raw))
        (catch js/Error _
          (js/console.warn "[EventHub/HTTP] Failed to parse httpAllowlist setting")
          []))
      [])))

;; =============================================================================
;; HTTP Request Executor
;; =============================================================================

(defn http-request-executor
  "Step executor for :http-request action.
   Reads step-config for url, method, headers, body, timeout.
   Interpolates url, headers, body with context.
   Checks URL against httpAllowlist setting.
   Enforces HTTPS (rejects http:// unless localhost/127.0.0.1).
   Uses AbortController for timeout.
   Returns Promise resolving to {:status N :ok bool :headers {} :body ...}."
  [step context]
  (let [config (:step-config step)
        ;; Build interpolation context matching what interpolation/interpolate expects:
        ;; {:inputs {"key" "val"} :step-results {1 "res"} :variables {:today "..."}}
        interp-ctx {:inputs (:inputs context)
                    :step-results (:step-results context)
                    :variables (:variables context)}
        ;; Read and interpolate config values
        raw-url (get config "url")
        url (when raw-url (interpolation/interpolate raw-url interp-ctx))
        method (or (get config "method") "GET")
        raw-headers (get config "headers")
        headers (if raw-headers
                  (interpolate-map raw-headers interp-ctx)
                  {})
        raw-body (get config "body")
        body (when raw-body
               (if (string? raw-body)
                 (interpolation/interpolate raw-body interp-ctx)
                 (js/JSON.stringify (clj->js (interpolate-map raw-body interp-ctx)))))
        timeout-raw (get config "timeout" 10000)
        timeout (min (max timeout-raw 1) 60000)
        allowlist (get-http-allowlist)]

    (cond
      ;; No URL provided
      (or (nil? url) (str/blank? url))
      (js/Promise.reject
        (errors/make-error :http-invalid-url "No URL provided in step config"))

      ;; URL not allowed by allowlist
      (not (url-allowed? url allowlist))
      (js/Promise.reject
        (errors/make-error :http-url-blocked
          (str "URL not allowed by httpAllowlist: " url)))

      ;; Execute the request
      :else
      (let [controller (js/AbortController.)
            signal (.-signal controller)
            timer (js/setTimeout #(.abort controller) timeout)
            fetch-opts (cond-> {:method (str/upper-case method)
                                :headers headers
                                :signal signal}
                         body (assoc :body body))]
        (-> (js/fetch url (clj->js fetch-opts))
            (.then (fn [response]
                     (js/clearTimeout timer)
                     (parse-response response)))
            (.catch (fn [err]
                      (js/clearTimeout timer)
                      (if (= "AbortError" (.-name err))
                        (throw (errors/make-error :http-timeout
                                 (str "HTTP request timed out after " timeout "ms")))
                        (throw (errors/make-error :http-request-failed
                                 (str "HTTP request failed: " (.-message err))))))))))))
