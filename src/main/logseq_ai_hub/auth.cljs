(ns logseq-ai-hub.auth
  (:require [clojure.string :as str]))

(defn get-auth-mode
  "Returns the current auth mode: \"token\" or \"jwt\".
   Defaults to \"token\" when not set or blank."
  []
  (let [mode (aget (aget js/logseq "settings") "authMode")]
    (if (str/blank? mode) "token" mode)))

(defn get-auth-token
  "Returns the resolved auth token based on current auth mode.
   Token mode: returns pluginApiToken. JWT mode: returns jwtToken."
  []
  (let [settings (aget js/logseq "settings")
        mode     (get-auth-mode)]
    (if (= mode "jwt")
      (aget settings "jwtToken")
      (aget settings "pluginApiToken"))))

(defn get-server-url
  "Returns the webhookServerUrl from plugin settings."
  []
  (aget (aget js/logseq "settings") "webhookServerUrl"))

(defn auth-configured?
  "Returns true if the resolved auth token is non-blank and server URL is set."
  []
  (and (not (str/blank? (get-auth-token)))
       (not (str/blank? (get-server-url)))))

(defn log-auth-warnings!
  "Logs console warnings for misconfigured auth. Does not block initialization."
  []
  (let [mode  (get-auth-mode)
        token (get-auth-token)]
    (cond
      (and (= mode "jwt") (str/blank? token))
      (js/console.warn "[Auth] JWT mode selected but no JWT token configured")

      (and (= mode "token") (str/blank? token))
      (js/console.warn "[Auth] Token mode selected but no API token configured"))))
