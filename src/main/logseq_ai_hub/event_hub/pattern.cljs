(ns logseq-ai-hub.event-hub.pattern
  "Glob-style pattern matching for event types.
   Supports dot-separated segments with * wildcard matching a single segment."
  (:require [clojure.string :as str]))

(defn pattern-matches?
  "Tests whether a glob pattern matches an event type string.
   Both are dot-separated. Each segment must match:
   - Exact string match, OR
   - Pattern segment is '*' (matches any single segment).
   Segment counts must be equal.

   Examples:
     (pattern-matches? \"job.*\" \"job.completed\")       => true
     (pattern-matches? \"job.*\" \"job.step.completed\")   => false
     (pattern-matches? \"webhook.*.*\" \"webhook.grafana.alert\") => true
     (pattern-matches? \"job.completed\" \"job.completed\") => true
     (pattern-matches? \"job.completed\" \"job.failed\")    => false"
  [pattern event-type]
  (let [pattern-segs (str/split pattern #"\.")
        event-segs (str/split event-type #"\.")]
    (and (= (count pattern-segs) (count event-segs))
         (every? (fn [[p e]]
                   (or (= p "*") (= p e)))
                 (map vector pattern-segs event-segs)))))
