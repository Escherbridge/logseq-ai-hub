(ns logseq-ai-hub.registry.bridge
  "Bridge operation handlers for registry CRUD.
   These are called by the server via the Agent Bridge SSE/callback pattern."
  (:require [logseq-ai-hub.registry.store :as store]
            [logseq-ai-hub.registry.scanner :as scanner]))

(defn handle-registry-list
  "Lists all registry entries, optionally filtered by type.
   Params: {\"type\" \"tool|prompt|procedure|agent|skill\" (optional)}
   Returns: vector of entry summaries."
  [params]
  (let [type-str (get params "type")
        type-kw (when type-str (keyword type-str))
        entries (if type-kw
                  (store/list-entries type-kw)
                  (store/list-entries))]
    (js/Promise.resolve
      {:entries (mapv (fn [e]
                        (-> e
                            (assoc :type (name (:type e)))
                            (update :source #(when % (name %)))))
                      entries)
       :count (count entries)
       :version (:version (store/get-snapshot))})))

(defn handle-registry-get
  "Gets a single registry entry by name and type.
   Params: {\"name\" \"entry-name\" \"type\" \"tool\"}
   Returns: full entry details or error."
  [params]
  (let [id (get params "name")
        type-str (get params "type")]
    (if (or (nil? id) (nil? type-str))
      (js/Promise.reject "Missing required params: name and type")
      (let [entry (store/get-entry (keyword type-str) id)]
        (if entry
          (js/Promise.resolve (dissoc entry :type))
          (js/Promise.reject (str "Entry not found: " type-str "/" id)))))))

(defn handle-registry-search
  "Searches registry entries by keyword.
   Params: {\"query\" \"search term\" \"type\" \"tool\" (optional)}
   Returns: matching entries."
  [params]
  (let [query (get params "query")
        type-str (get params "type")
        type-kw (when type-str (keyword type-str))]
    (if (nil? query)
      (js/Promise.reject "Missing required param: query")
      (let [results (store/search-entries query type-kw)]
        (js/Promise.resolve
          {:entries (mapv (fn [e]
                           {:id (:id e)
                            :type (name (:type e))
                            :name (:name e)
                            :description (:description e)})
                         results)
           :count (count results)})))))

(defn handle-registry-refresh
  "Triggers a full registry rescan.
   Returns: counts of discovered items by type."
  [_params]
  (scanner/refresh-registry!))

(defn handle-execute-skill
  "Executes a skill by ID with given inputs.
   Params: {\"skillId\" \"Skills/foo\" \"inputs\" {...}}
   Returns: skill execution result."
  [params]
  (let [skill-id (get params "skillId")
        inputs (or (get params "inputs") {})]
    (if (nil? skill-id)
      (js/Promise.reject "Missing required param: skillId")
      ;; Delegate to dynamic var for actual execution (wired in init)
      (if-let [exec-fn (get @bridge-fns :execute-skill)]
        (exec-fn skill-id inputs)
        (js/Promise.reject "Skill execution not initialized")))))

;; Dynamic function registry for bridge dependencies
(defonce bridge-fns (atom {}))

(defn set-execute-skill-fn!
  "Sets the function used to execute skills. Called during initialization."
  [f]
  (swap! bridge-fns assoc :execute-skill f))
