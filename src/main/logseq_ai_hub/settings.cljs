(ns logseq-ai-hub.settings
  "Coercing readers for plugin settings.

   Logseq stores a cleared field as \"\", and in ClojureScript an empty string
   is TRUTHY — so the common `(or (aget settings k) default)` keeps \"\" instead
   of falling back. That silently produced a job runner whose concurrency limit
   was \"\" (making `(< n \"\")` false forever) and an LLM request with model \"\".
   Read every setting through here instead."
  (:require [clojure.string :as str]))

(defn- raw [k]
  (aget (aget js/logseq "settings") k))

(defn text
  "Trimmed string setting, or `default` when absent/blank."
  [k default]
  (let [v (raw k)]
    (if (string? v)
      (let [t (str/trim v)]
        (if (str/blank? t) default t))
      default)))

(defn number
  "Numeric setting, or `default` when absent, blank or not a number.
   Accepts the string form Logseq stores for number fields."
  [k default]
  (let [v (raw k)]
    (cond
      (number? v) (if (js/isNaN v) default v)
      (string? v) (let [t (str/trim v)
                        n (js/parseFloat t)]
                    (if (or (str/blank? t) (js/isNaN n)) default n))
      :else default)))

(defn flag
  "Boolean setting, or `default` when absent. Accepts \"true\"/\"false\" strings."
  [k default]
  (let [v (raw k)]
    (cond
      (boolean? v) v
      (string? v) (case (str/lower-case (str/trim v))
                    "true" true
                    "false" false
                    default)
      :else default)))
