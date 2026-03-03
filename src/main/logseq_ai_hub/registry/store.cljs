(ns logseq-ai-hub.registry.store
  "Atom-based registry store for tools, prompts, procedures, agents, and skills.
   Provides CRUD operations and search over the registry."
  (:require [clojure.string :as str]))

(defonce registry
  (atom {:tools {}
         :prompts {}
         :procedures {}
         :agents {}
         :skills {}
         :version 0
         :last-scan nil}))

(defn init-store!
  "Resets the registry to its initial empty state."
  []
  (reset! registry
          {:tools {}
           :prompts {}
           :procedures {}
           :agents {}
           :skills {}
           :version 0
           :last-scan nil}))

(defn- category-key
  "Maps an entry type keyword to its registry category key."
  [entry-type]
  (case entry-type
    :tool :tools
    :prompt :prompts
    :procedure :procedures
    :agent :agents
    :skill :skills
    (throw (js/Error. (str "Unknown entry type: " entry-type)))))

(defn add-entry
  "Adds an entry to the registry. Entry must have :id and :type keys.
   Returns the updated registry value."
  [entry]
  (let [cat (category-key (:type entry))
        id (:id entry)]
    (swap! registry assoc-in [cat id] entry)))

(defn remove-entry
  "Removes an entry by type and id. Returns true if entry existed."
  [entry-type id]
  (let [cat (category-key entry-type)
        existed? (contains? (get @registry cat) id)]
    (swap! registry update cat dissoc id)
    existed?))

(defn get-entry
  "Gets a single entry by type and id. Returns nil if not found."
  [entry-type id]
  (get-in @registry [(category-key entry-type) id]))

(defn list-entries
  "Lists all entries, optionally filtered by type.
   If type-filter is nil, returns all entries across all categories."
  ([]
   (list-entries nil))
  ([type-filter]
   (if type-filter
     (vals (get @registry (category-key type-filter)))
     (concat
       (vals (:tools @registry))
       (vals (:prompts @registry))
       (vals (:procedures @registry))
       (vals (:agents @registry))
       (vals (:skills @registry))))))

(defn search-entries
  "Searches entries by substring match on name or description.
   Optionally filtered by type."
  ([query]
   (search-entries query nil))
  ([query type-filter]
   (let [q (str/lower-case query)
         entries (list-entries type-filter)]
     (filter (fn [entry]
               (or (str/includes?
                     (str/lower-case (or (:name entry) "")) q)
                   (str/includes?
                     (str/lower-case (or (:description entry) "")) q)))
             entries))))

(defn get-snapshot
  "Returns the full registry state as a serializable map (without atom wrapper)."
  []
  (let [reg @registry]
    {:tools (vals (:tools reg))
     :prompts (vals (:prompts reg))
     :procedures (vals (:procedures reg))
     :agents (vals (:agents reg))
     :skills (vals (:skills reg))
     :version (:version reg)
     :last-scan (:last-scan reg)}))

(defn bump-version!
  "Increments the registry version counter and sets last-scan timestamp.
   Returns the new version number."
  []
  (let [new-state (swap! registry (fn [reg]
                                    (-> reg
                                        (update :version inc)
                                        (assoc :last-scan (.toISOString (js/Date.))))))]
    (:version new-state)))

(defn clear-category!
  "Clears all entries in a category. Used before re-scanning."
  [entry-type]
  (let [cat (category-key entry-type)]
    (swap! registry assoc cat {})))
