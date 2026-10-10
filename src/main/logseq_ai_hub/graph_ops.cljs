(ns logseq-ai-hub.graph-ops
  "Bridge handlers behind the graph MCP tools: read, search, link, edit, delete.
   Wire contract and design notes: src/main/logseq_ai_hub/AGENTS.md §graph-ops."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Host access
;; =============================================================================

(defn- invalid!
  "Rejects a request with a message the calling agent can act on."
  [message]
  (throw (js/Error. message)))

(defn- fail [message] (js/Promise.reject (js/Error. message)))

(defn- ->clj [x] (js->clj x :keywordize-keys true))

(defn- query
  "datascriptQuery → clj. Logseq strips keyword namespaces and stringifies uuids."
  [datalog]
  (-> (js/logseq.DB.datascriptQuery datalog) (.then ->clj)))

(defn- edn-string
  "A string as an EDN literal, safe to splice into a datalog query."
  [s]
  (pr-str (str s)))

(defn- get-page [page-identity]
  (-> (js/logseq.Editor.getPage page-identity) (.then ->clj)))

(defn- require-page [page-name]
  (-> (get-page page-name)
      (.then #(or % (fail (str "Page not found: " page-name))))))

(defn- get-block [uuid]
  (-> (js/logseq.Editor.getBlock uuid #js {:includeChildren true}) (.then ->clj)))

(defn- require-block [uuid]
  (-> (get-block uuid)
      (.then #(or % (fail (str "Block not found: " uuid))))))

(defn- page-tree [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.then #(vec (or (->clj %) [])))))

(defn- page-title [page] (or (:originalName page) (:name page)))

(defn- in-sequence
  "Runs promise-returning `f` over `items` one at a time; resolves to the results."
  [f items]
  (reduce (fn [acc item]
            (.then acc (fn [results] (.then (js/Promise.resolve (f item)) #(conj results %)))))
          (js/Promise.resolve [])
          items))

;; =============================================================================
;; Params
;; =============================================================================

(defn- text-param [params k]
  (let [v (get params k)]
    (when (string? v)
      (let [trimmed (str/trim v)] (when-not (str/blank? trimmed) trimmed)))))

(defn- required-text [params k]
  (or (text-param params k) (invalid! (str "Missing required parameter: " k))))

(defn- int-param [params k default lowest highest]
  (let [v (get params k)]
    (if (and (number? v) (js/isFinite v))
      (-> (js/Math.floor v) (max lowest) (min highest))
      default)))

(defn- choice-param [params k allowed default]
  (let [v (get params k)]
    (cond (nil? v) default
          (contains? allowed v) v
          :else (invalid! (str k " must be one of " (str/join ", " (sort allowed)) " — got " (pr-str v))))))

(def ^:private uuid-keys #{"uuid" "target_uuid"})

(defn- id-param
  "Text param, lower-cased when it is a uuid so comparisons and lookups agree."
  [params k]
  (cond-> (text-param params k) (uuid-keys k) (some-> str/lower-case)))

(defn- required-uuid [params k]
  (or (id-param params k) (invalid! (str "Missing required parameter: " k))))

(defn- exactly-one [params a b]
  (let [va (id-param params a) vb (id-param params b)]
    (when (= (boolean va) (boolean vb))
      (invalid! (str "Pass exactly one of " a " or " b ".")))
    (if va [a va] [b vb])))

(def ^:private property-key-re #"[A-Za-z][A-Za-z0-9_-]*")

(defn- properties-param
  "Validated {key value}: keys lower-cased (Logseq stores them that way, and
   getBlockProperty looks them up verbatim); each value a single-line string,
   a vector of single-line strings, or nil."
  [params k required?]
  (let [props (get params k)
        single-line? #(not (re-find #"[\r\n]" %))]
    (cond
      (and (nil? props) (not required?)) nil
      (not (and (map? props) (seq props))) (invalid! (str k " must be a non-empty object"))
      :else
      (into {}
            (map (fn [[key value]]
                   (when-not (re-matches property-key-re key)
                     (invalid! (str "Invalid property key: " (pr-str key))))
                   (when (= "id" (str/lower-case key))
                     (invalid! "The id property is managed by Logseq and cannot be set."))
                   (when-not (or (nil? value) (string? value)
                                 (and (sequential? value) (every? string? value)))
                     (invalid! (str "Property " key " must be a string, an array of page names, or null.")))
                   ;; A newline would let a value smuggle in extra `key:: value` lines, including id::.
                   (when-not (every? single-line? (if (sequential? value) value (keep identity [value])))
                     (invalid! (str "Property " key " must be a single line.")))
                   [(str/lower-case key) value]))
            props))))

;; =============================================================================
;; Outline parsing (markdown bullets → nested blocks)
;; =============================================================================

(def ^:private bullet-re #"^([ \t]*)[-*](?:[ \t]+(.*))?$")
(def ^:private fence-re #"^\s*(```|~~~)")

(defn- indent-width [whitespace] (count (str/replace whitespace "\t" "  ")))

(defn- strip-indent [line width]
  (let [leading (count (re-find #"^[ \t]*" line))]
    (subs line (min leading width))))

(defn- outline-items
  "Flat [{:indent :lines}]: bullet lines open an item, other lines continue the
   current one. Text before the first bullet becomes its own top-level item.
   Lines inside a code fence never open an item."
  [text]
  (loop [lines (str/split-lines text) items [] in-fence? false]
    (if (empty? lines)
      items
      (let [line (first lines)
            bullet (when-not in-fence? (re-matches bullet-re line))
            body (if bullet (or (nth bullet 2) "") line)
            fence-flip? (boolean (re-find fence-re body))
            next-fence (if fence-flip? (not in-fence?) in-fence?)]
        (cond
          bullet
          (recur (rest lines)
                 (conj items {:indent (indent-width (nth bullet 1)) :lines [body]})
                 next-fence)

          (empty? items)
          (recur (rest lines) [{:indent 0 :lines [line]}] next-fence)

          :else
          (let [{:keys [indent]} (peek items)]
            (recur (rest lines)
                   (update-in items [(dec (count items)) :lines] conj (strip-indent line (+ indent 2)))
                   next-fence)))))))

(defn- items->tree [items]
  (loop [items items tree []]
    (if-let [{:keys [indent content]} (first items)]
      (let [[descendants remaining] (split-with #(> (:indent %) indent) (rest items))]
        (recur remaining (conj tree {:content content :children (items->tree descendants)})))
      tree)))

(defn parse-outline
  "Markdown outline → [{:content :children}]. Indentation nests; text with no
   bullets is a single block."
  [text]
  (if (str/blank? text)
    []
    (->> (outline-items text)
         (map (fn [{:keys [indent lines]}] {:indent indent :content (str/trim (str/join "\n" lines))}))
         (remove #(and (str/blank? (:content %)) (zero? (:indent %))))
         items->tree)))

;; =============================================================================
;; Property values
;; =============================================================================

(defn- link-list [names] (str/join ", " (map #(str "[[" % "]]") names)))

(defn- existing-names
  "Page names held by a property value (a collection, or text with [[links]] or commas)."
  [value]
  (cond
    (nil? value) []
    (string? value) (let [refs (map second (re-seq #"\[\[([^\]]+)\]\]" value))]
                      (if (seq refs)
                        (vec refs)
                        (->> (str/split value #",") (map str/trim) (remove str/blank?) vec)))
    (coll? value) (mapv str value)
    :else [(str value)]))

(defn merge-names
  "`add` appends names not already present (case-insensitive); `remove` drops them."
  [existing incoming mode]
  (let [same? (fn [a b] (= (str/lower-case a) (str/lower-case b)))]
    (case mode
      "add" (reduce (fn [acc n] (if (some #(same? % n) acc) acc (conj acc n))) (vec existing) incoming)
      "remove" (vec (remove (fn [e] (some #(same? e %) incoming)) existing)))))

(defn final-value
  "The text written for a property (nil = remove it). Strings are verbatim and
   string vectors become [[links]]; add/remove merge into the current link list."
  [value current mode]
  (cond
    (nil? value) nil
    (= mode "set") (if (sequential? value) (link-list value) value)
    ;; Merging into prose ("Hello, world") would turn it into [[Hello]], [[world]].
    (and (string? current) (not (str/includes? current "[[")))
    (invalid! (str "That property holds plain text (" (pr-str current)
                   "), not a list of links — use mode \"set\" to replace it."))
    :else (let [names (merge-names (existing-names current)
                                   (if (string? value) [value] value)
                                   mode)]
            (when (seq names) (link-list names)))))

(defn- properties-only?
  "True when every non-blank line of `content` is a `key:: value` line."
  [content]
  (let [lines (remove str/blank? (str/split-lines (or content "")))]
    (and (seq lines) (every? #(re-find #"^\s*[A-Za-z][A-Za-z0-9_-]*:: " %) lines))))

(defn preserve-id-property
  "Keeps the block's `id::` line when new content drops it — that line is what
   lets ((uuid)) references survive a re-index of the file."
  [old-content new-content]
  (let [id-line (re-find #"(?m)^\s*id:: .+$" (or old-content ""))]
    (if (and id-line (not (re-find #"(?m)^\s*id:: " new-content)))
      (str (str/trimr new-content) "\n" (str/trim id-line))
      new-content)))

;; =============================================================================
;; Tree shaping
;; =============================================================================

(defn- ->node [block]
  (cond-> {:uuid (str (:uuid block))
           :content (or (:content block) "")
           :children (mapv ->node (filter map? (:children block)))}
    (:preBlock? block) (assoc :pre true)))

(defn- block-count [blocks]
  (reduce + (map #(inc (block-count (filter map? (:children %)))) blocks)))

(defn- descendant-uuids [block]
  (into #{} (mapcat #(cons (str (:uuid %)) (descendant-uuids %))) (filter map? (:children block))))

(defn- preview [content]
  (let [line (first (str/split-lines (or content "")))]
    (if (> (count line) 80) (str (subs line 0 79) "…") (or line ""))))

;; =============================================================================
;; Insertion
;; =============================================================================

(declare insert-nodes!)

(defn- insert-one! [anchor content opts]
  (-> (js/logseq.Editor.insertBlock anchor content (clj->js opts))
      (.then #(if % (str (.-uuid ^js %)) (fail (str "Logseq did not insert block: " (preview content)))))))

(defn- insert-children! [parent-uuid children]
  (if (seq children)
    (insert-nodes! parent-uuid {:sibling false} children)
    (js/Promise.resolve [])))

(defn- insert-nodes!
  "Inserts `nodes` in order: the first relative to `anchor` using `first-opts`,
   each next one after the previous. Resolves to created uuids in pre-order."
  [anchor first-opts nodes]
  (-> (reduce (fn [acc [index node]]
                (.then acc
                  (fn [[previous uuids]]
                    (-> (insert-one! (if (zero? index) anchor previous)
                                     (:content node)
                                     (if (zero? index) first-opts {:sibling true}))
                        (.then (fn [uuid]
                                 (.then (insert-children! uuid (:children node))
                                        (fn [child-uuids]
                                          [uuid (-> uuids (conj uuid) (into child-uuids))]))))))))
              (js/Promise.resolve [nil []])
              (map-indexed vector nodes))
      (.then second)))

(defn- continue-from!
  "The first node is already written at `first-uuid`; add its children and the rest."
  [first-uuid [first-node & more]]
  (-> (insert-children! first-uuid (:children first-node))
      (.then (fn [child-uuids]
               (.then (insert-nodes! first-uuid {:sibling true} more)
                      #(-> [first-uuid] (into child-uuids) (into %)))))))

(defn- insert-into-page!
  "Inserts nodes at the end or start of a page. Start lands after the page's
   properties block. A page holding only Logseq's blank placeholder block is
   filled in place instead of leaving an empty bullet behind."
  [page-name position nodes]
  (if (empty? nodes)
    (js/Promise.resolve [])
    (-> (page-tree page-name)
        (.then
          (fn [blocks]
            (let [first-block (first blocks)
                  placeholder? (and (= 1 (count blocks))
                                    (str/blank? (:content first-block))
                                    (empty? (:children first-block)))]
              (cond
                placeholder?
                (let [uuid (str (:uuid first-block))]
                  (-> (js/logseq.Editor.updateBlock uuid (:content (first nodes)))
                      (.then #(continue-from! uuid nodes))))

                (empty? blocks)
                (-> (js/logseq.Editor.appendBlockInPage page-name (:content (first nodes)))
                    (.then #(if % (str (.-uuid ^js %)) (fail (str "Logseq did not append to page: " page-name))))
                    (.then #(continue-from! % nodes)))

                (= position "start")
                (if (or (:preBlock? first-block) (properties-only? (:content first-block)))
                  (insert-nodes! (str (:uuid first-block)) {:sibling true} nodes)
                  (insert-nodes! (str (:uuid first-block)) {:sibling true :before true} nodes))

                :else
                (insert-nodes! (str (:uuid (peek blocks))) {:sibling true} nodes))))))))

(defn- insert-at-block!
  "Inserts nodes before/after a block, or as its last children."
  [uuid position nodes]
  (-> (require-block uuid)
      (.then
        (fn [block]
          (case position
            "before" (if (:preBlock? block)
                       (fail "Nothing can go before a page's properties block — use position \"after\".")
                       (insert-nodes! uuid {:sibling true :before true} nodes))
            "after" (insert-nodes! uuid {:sibling true} nodes)
            "child" (if-let [last-child (last (filter map? (:children block)))]
                      (insert-nodes! (str (:uuid last-child)) {:sibling true} nodes)
                      (insert-nodes! uuid {:sibling false} nodes)))))))

(defn- outline-param [params k]
  (let [nodes (parse-outline (or (get params k) ""))]
    (when (empty? nodes) (invalid! (str "Missing required parameter: " k)))
    nodes))

;; =============================================================================
;; Properties
;; =============================================================================

(defn- write-block-properties!
  "Writes properties onto an existing block; resolves to {key written-text-or-nil}."
  [uuid props mode]
  (reduce
    (fn [acc [key value]]
      (.then acc
        (fn [written]
          (-> (if (= mode "set")
                (js/Promise.resolve nil)
                (-> (js/logseq.Editor.getBlockProperty uuid key) (.then ->clj)))
              (.then (fn [current]
                       (let [text (final-value value current mode)]
                         (-> (if (nil? text)
                               (js/logseq.Editor.removeBlockProperty uuid key)
                               (js/logseq.Editor.upsertBlockProperty uuid key text))
                             (.then (fn [_] (assoc written key text)))))))))))
    (js/Promise.resolve {})
    props))

(defn- page-properties-block
  "The page's properties block (its first block when that holds only properties)."
  [blocks]
  (let [first-block (first blocks)]
    (when (and first-block (or (:preBlock? first-block) (properties-only? (:content first-block))))
      first-block)))

(defn- write-page-properties!
  "Writes page properties, creating the properties block when the page has none."
  [page-name props mode]
  (-> (page-tree page-name)
      (.then
        (fn [blocks]
          (if-let [block (page-properties-block blocks)]
            (.then (write-block-properties! (str (:uuid block)) props mode)
                   (fn [written] {:target (str (:uuid block)) :properties written}))
            (let [written (into {} (map (fn [[k v]] [k (final-value v nil mode)])) props)
                  lines (keep (fn [[k text]] (when text (str k ":: " text))) written)]
              (if (empty? lines)
                {:target nil :properties written}
                ;; Not prependBlockInPage: Logseq's version inserts before the *second*
                ;; block, assuming the first is already the properties block.
                (-> (if-let [head (first blocks)]
                      (js/logseq.Editor.insertBlock (str (:uuid head)) (str/join "\n" lines)
                                                    #js {:sibling true :before true})
                      (js/logseq.Editor.appendBlockInPage page-name (str/join "\n" lines)))
                    (.then #(if % (str (.-uuid ^js %)) (fail (str "Logseq did not add properties to page: " page-name))))
                    (.then (fn [uuid]
                             (.then (page-tree page-name)
                                    (fn [after]
                                      (if (= uuid (str (:uuid (first after))))
                                        {:target uuid :properties written}
                                        (fail (str "Logseq did not place the properties block first on " page-name
                                                   ", so they would not be page properties.")))))))))))))))

;; =============================================================================
;; Read handlers
;; =============================================================================

(defn handle-graph-query [params]
  (let [datalog (required-text params "query")
        limit (int-param params "limit" 100 1 1000)]
    (-> (query datalog)
        (.then (fn [results]
                 ;; A scalar find spec (`?x .`) returns a value, not rows; (vec 42)
                 ;; would throw and (vec "name") would split it into characters.
                 (if (sequential? results)
                   (let [results (vec results)]
                     {:results (vec (take limit results))
                      :total (count results)
                      :truncated (> (count results) limit)})
                   {:results results :total 1 :truncated false}))))))

(defn- escape-regex [s] (str/replace s #"[.*+?^${}()|\[\]\\]" "\\$&"))

(defn search-pattern
  "Case-insensitive regex requiring every term, in any order."
  [terms]
  (str "(?i)" (apply str (map #(str "(?=[\\s\\S]*?" (escape-regex %) ")") terms))))

(defn handle-graph-search [params]
  (let [query-text (required-text params "query")
        terms (->> (str/split query-text #"\s+") (remove str/blank?) vec)
        _ (when (> (count terms) 8) (invalid! "graph_search takes at most 8 terms; drop the least specific ones."))
        limit (int-param params "limit" 20 1 100)
        page (text-param params "page")
        pattern (edn-string (search-pattern terms))
        block-query (str "[:find (pull ?b [:block/uuid :block/content {:block/page [:block/original-name]}])"
                         " :where [(re-pattern " pattern ") ?re]"
                         " [?b :block/content ?c] [(re-find ?re ?c)] [?b :block/page ?p]"
                         (when page (str " [?p :block/name " (edn-string (str/lower-case page)) "]"))
                         "]")
        page-query (str "[:find (pull ?p [:block/original-name])"
                        " :where [(re-pattern " pattern ") ?re]"
                        " [?p :block/original-name ?n] [(re-find ?re ?n)]]")]
    ;; A misspelled page must say so, not come back as "no matches".
    (-> (if page (require-page page) (js/Promise.resolve nil))
        (.then #(js/Promise.all #js [(query block-query) (if page (js/Promise.resolve []) (query page-query))]))
        (.then (fn [[block-rows page-rows]]
                 (let [blocks (->> block-rows
                                   (map first)
                                   (map (fn [b] {:uuid (str (:uuid b))
                                                 :page (get-in b [:page :original-name])
                                                 :content (:content b)}))
                                   (sort-by (comp str/lower-case str :page)))
                       pages (->> page-rows (map (comp :original-name first)) (sort-by str/lower-case))]
                   {:query query-text
                    :pages (vec (take 20 pages))
                    :pagesTotal (count pages)
                    :blocks (vec (take limit blocks))
                    :total (count blocks)}))))))

(defn- tag-names [page]
  (into (mapv :original-name (:tags page))
        (existing-names (get-in page [:properties :tags]))))

(defn- property-matches? [page {:strs [key value]}]
  (let [current (get-in page [:properties (keyword (str/lower-case (str key)))])]
    (cond
      (nil? current) false
      (str/blank? value) true
      :else (let [candidates (cond (string? current) (cons current (existing-names current))
                                   (coll? current) (map str current)
                                   :else [(str current)])]   ; booleans and numbers, e.g. public:: true
              (boolean (some #(= (str/lower-case value) (str/lower-case %)) candidates))))))

(defn handle-page-list [params]
  (let [pattern (some-> (text-param params "pattern") str/lower-case)
        namespace (some-> (text-param params "namespace") str/lower-case (str/replace #"/+$" ""))
        tag (some-> (text-param params "tag") str/lower-case)
        property (let [p (get params "property")]
                   (when (some? p)
                     (when-not (and (map? p) (text-param p "key"))
                       (invalid! "property must be an object with a key (and optional value)"))
                     p))
        journals (choice-param params "journals" #{"exclude" "include" "only"} "exclude")
        sort-order (choice-param params "sort" #{"updated" "name"} "updated")
        limit (int-param params "limit" 50 1 500)
        offset (int-param params "offset" 0 0 js/Number.MAX_SAFE_INTEGER)]
    (-> (query (str "[:find (pull ?p [:block/name :block/original-name :block/updated-at :block/journal?"
                    " :block/properties {:block/tags [:block/original-name]}])"
                    " :where [?p :block/name]]"))
        (.then
          (fn [rows]
            (let [pages (->> rows
                             (map first)
                             (filter (fn [p]
                                       (let [name (str (:name p))]
                                         (and (case journals
                                                "exclude" (not (:journal? p))
                                                "only" (boolean (:journal? p))
                                                true)
                                              (or (nil? pattern) (str/includes? name pattern))
                                              (or (nil? namespace) (str/starts-with? name (str namespace "/")))
                                              (or (nil? tag) (some #(= tag (str/lower-case %)) (tag-names p)))
                                              (or (nil? property) (property-matches? p property))))))
                             (sort-by (if (= sort-order "name")
                                        #(str/lower-case (or (:original-name %) (:name %) ""))
                                        #(- (or (:updated-at %) 0)))))]
              {:total (count pages)
               :offset offset
               :pages (->> pages
                           (drop offset)
                           (take limit)
                           (mapv (fn [p]
                                   (let [tags (tag-names p)]
                                     (cond-> {:name (or (:original-name p) (:name p))}
                                       (:updated-at p) (assoc :updatedAt (:updated-at p))
                                       (:journal? p) (assoc :journal true)
                                       (seq tags) (assoc :tags (vec (distinct tags))))))))}))))))

(defn handle-page-read [params]
  (let [page-name (required-text params "name")]
    (-> (require-page page-name)
        (.then (fn [page]
                 (.then (page-tree page-name)
                        (fn [blocks]
                          {:page {:name (page-title page)
                                  :uuid (str (:uuid page))
                                  :journal (boolean (:journal? page))}
                           :blocks (mapv ->node blocks)})))))))

(defn handle-block-get [params]
  (let [uuid (required-uuid params "uuid")]
    (-> (require-block uuid)
        (.then (fn [block]
                 (let [page-id (get-in block [:page :id])
                       parent-id (get-in block [:parent :id])]
                   (js/Promise.all
                     #js [(get-page page-id)
                          (if (and parent-id (not= parent-id page-id))
                            (-> (js/logseq.Editor.getBlock parent-id) (.then ->clj))
                            (js/Promise.resolve nil))
                          block]))))
        (.then (fn [[page parent block]]
                 (assoc (->node block)
                        :page (page-title page)
                        :parentUuid (some-> (:uuid parent) str)))))))

(defn handle-page-links [params]
  (let [page-name (required-text params "name")
        direction (choice-param params "direction" #{"both" "in" "out"} "both")
        limit (int-param params "limit" 50 1 500)
        outgoing-query (str "[:find (pull ?b [:block/content :block/properties"
                            " {:block/refs [:block/original-name]}])"
                            " :where [?p :block/name " (edn-string (str/lower-case page-name)) "]"
                            " [?b :block/page ?p]]")
        ;; Logseq also refs a page per property *key* (related, tags, status…);
        ;; those are not links the user wrote, unless the content links them too.
        linked-names (fn [block]
                       (let [keys (set (map name (keys (:properties block))))
                             content (str/lower-case (or (:content block) ""))]
                         (->> (:refs block)
                              (keep :original-name)
                              (remove #(let [n (str/lower-case %)]
                                         (and (keys n) (not (str/includes? content (str "[[" n "]]")))))))))]
    (-> (require-page page-name)
        (.then
          (fn [page]
            (let [title (page-title page)]
              (js/Promise.all
                #js [title
                     (if (= direction "in")
                       (js/Promise.resolve nil)
                       (.then (query outgoing-query)
                              (fn [rows]
                                (->> rows (mapcat (comp linked-names first))
                                     (remove #(= (str/lower-case %) (str/lower-case title)))
                                     distinct (sort-by str/lower-case) vec))))
                     (if (= direction "out")
                       (js/Promise.resolve nil)
                       (.then (js/logseq.Editor.getPageLinkedReferences title) ->clj))]))))
        (.then
          (fn [[title outgoing references]]
            (let [groups (mapv (fn [[source blocks]]
                                 {:page (page-title source)
                                  :blocks (mapv (fn [b] {:uuid (str (:uuid b)) :content (:content b)}) blocks)})
                               references)
                  total (reduce + (map (comp count :blocks) groups))
                  limited (loop [groups groups remaining limit kept []]
                            (if (or (empty? groups) (<= remaining 0))
                              kept
                              (let [g (first groups)
                                    taken (vec (take remaining (:blocks g)))]
                                (recur (rest groups) (- remaining (count taken))
                                       (conj kept (assoc g :blocks taken))))))]
              (cond-> {:page title}
                (some? outgoing) (assoc :outgoing outgoing)
                (not= direction "out") (assoc :backlinks limited :backlinksTotal total))))))))

;; =============================================================================
;; Write handlers
;; =============================================================================

(defn- property-texts [props]
  (into {} (map (fn [[k v]] [k (final-value v nil "set")])) props))

(defn handle-page-create [params]
  (let [page-name (required-text params "name")
        nodes (parse-outline (or (get params "content") ""))
        props (properties-param params "properties" false)
        if-exists (choice-param params "if_exists" #{"error" "skip" "append"} "error")]
    (-> (get-page page-name)
        (.then
          (fn [existing]
            (cond
              (and existing (= if-exists "error"))
              (fail (str "Page already exists: " (page-title existing)
                         ". Pass if_exists \"append\" to add to it, or \"skip\" to leave it."))

              (and existing (= if-exists "skip"))
              {:page (page-title existing) :created false :uuids []}

              existing
              (let [title (page-title existing)]
                (-> (if props (write-page-properties! title props "set") (js/Promise.resolve nil))
                    (.then #(insert-into-page! title "end" nodes))
                    (.then (fn [uuids] {:page title :created false :uuids uuids}))))

              :else
              (-> (js/logseq.Editor.createPage page-name
                                               (clj->js (property-texts (remove (comp nil? val) props)))
                                               #js {:createFirstBlock false :redirect false})
                  (.then (fn [page]
                           (if page
                             (let [title (or (.-originalName ^js page) page-name)]
                               (.then (insert-into-page! title "end" nodes)
                                      (fn [uuids] {:page title :created true :uuids uuids})))
                             (fail (str "Logseq did not create page: " page-name))))))))))))

(defn handle-page-delete [params]
  (let [page-name (required-text params "name")]
    (-> (require-page page-name)
        (.then (fn [page]
                 (let [title (page-title page)]
                   (-> (page-tree title)
                       (.then (fn [blocks]
                                (.then (js/logseq.Editor.deletePage title)
                                       (fn [_] {:deleted title :blocks (block-count blocks)})))))))))))

(defn handle-page-rename [params]
  (let [page-name (required-text params "name")
        new-name (required-text params "new_name")]
    (-> (js/Promise.all #js [(require-page page-name) (get-page new-name)])
        (.then (fn [[page clash]]
                 (let [title (page-title page)]
                   (if (and clash (not= (str/lower-case new-name) (str/lower-case title)))
                     (fail (str "A page named " (page-title clash) " already exists; Logseq would merge them. Pick another name."))
                     (.then (js/logseq.Editor.renamePage title new-name)
                            (fn [_] {:from title :to new-name})))))))))

(defn handle-block-insert [params]
  (let [nodes (outline-param params "content")
        [target-kind target] (exactly-one params "page" "uuid")]
    (if (= target-kind "page")
      (let [position (choice-param params "position" #{"end" "start"} "end")]
        (-> (require-page target)
            (.then #(insert-into-page! (page-title %) position nodes))
            (.then (fn [uuids] {:uuids uuids}))))
      (let [position (choice-param params "position" #{"child" "before" "after"} "child")]
        (.then (insert-at-block! target position nodes) (fn [uuids] {:uuids uuids}))))))

(defn handle-block-append
  "Legacy op used by the server's built-in agent: append one block, properties as lines."
  [params]
  (let [page-name (required-text params "page")
        content (required-text params "content")
        props (get params "properties")
        text (if (map? props)
               (str content "\n" (str/join "\n" (map (fn [[k v]] (str (name k) ":: " v)) props)))
               content)]
    (-> (require-page page-name)
        (.then #(insert-into-page! (page-title %) "end" [{:content text :children []}]))
        (.then (fn [uuids] {:page page-name :blockUuid (first uuids)})))))

(defn handle-block-update [params]
  (let [uuid (required-uuid params "uuid")
        content (get params "content")
        props (properties-param params "properties" false)]
    (when (and (not (string? content)) (nil? props))
      (invalid! "Pass content, properties, or both."))
    (-> (require-block uuid)
        (.then (fn [block]
                 (if (string? content)
                   (js/logseq.Editor.updateBlock uuid (preserve-id-property (:content block) content))
                   nil)))
        (.then (fn [_] (when props (write-block-properties! uuid props "set"))))
        (.then (fn [_] {:uuid uuid :updated true})))))

(defn handle-block-delete [params]
  (let [single (id-param params "uuid")
        many (get params "uuids")
        uuids (cond
                (and single (some? many)) (invalid! "Pass uuid or uuids, not both.")
                single [single]
                (and (sequential? many) (seq many) (every? string? many))
                (vec (distinct (map (comp str/lower-case str/trim) many)))
                :else (invalid! "Missing required parameter: uuid (or uuids, a non-empty array)"))]
    (-> (js/Promise.all (clj->js (map get-block uuids)))
        (.then
          (fn [blocks]
            (let [blocks (vec blocks)
                  missing (keep-indexed (fn [i b] (when (nil? b) (nth uuids i))) blocks)]
              (if (seq missing)
                (fail (str "Block not found: " (str/join ", " missing) ". Nothing was deleted."))
                ;; A block already inside another listed block goes with its ancestor.
                (let [covered (into #{} (mapcat descendant-uuids) blocks)
                      targets (remove #(covered (str (:uuid %))) blocks)]
                  (in-sequence
                    (fn [block]
                      (-> (get-page (get-in block [:page :id]))
                          (.then (fn [page]
                                   (.then (js/logseq.Editor.removeBlock (str (:uuid block)))
                                          (fn [_]
                                            {:uuid (str (:uuid block))
                                             :page (page-title page)
                                             :preview (preview (:content block))
                                             :descendants (count (descendant-uuids block))}))))))
                    targets))))))
        (.then (fn [deleted] {:deleted deleted})))))

(defn- move-and-confirm!
  "moveBlock resolves to nothing even when Logseq declines the move, so confirm
   the block now sits under the expected parent."
  [uuid anchor opts expected-parent-id]
  (-> (js/logseq.Editor.moveBlock uuid anchor (clj->js opts))
      (.then #(get-block uuid))
      (.then (fn [moved]
               (if (= expected-parent-id (get-in moved [:parent :id]))
                 {:uuid uuid :moved true}
                 (fail "Logseq did not move the block (it may have refused the target)."))))))

(def ^:private displaces-properties
  "Nothing can go before a page's properties block — the page would lose its properties.")

(defn handle-block-move [params]
  (let [uuid (required-uuid params "uuid")
        [target-kind target] (exactly-one params "target_uuid" "page")]
    (-> (require-block uuid)
        (.then
          (fn [block]
            (let [own-subtree (conj (descendant-uuids block) (str (:uuid block)))]
              (if (= target-kind "target_uuid")
                (let [position (choice-param params "position" #{"after" "before" "child"} "after")]
                  (if (own-subtree target)
                    (fail "Cannot move a block relative to itself or one of its own children.")
                    (.then (require-block target)
                           (fn [target-block]
                             (let [last-child (last (filter map? (:children target-block)))
                                   parent-id (get-in target-block [:parent :id])]
                               (case position
                                 "before" (if (:preBlock? target-block)
                                            (fail displaces-properties)
                                            (move-and-confirm! uuid target {:before true} parent-id))
                                 "after" (move-and-confirm! uuid target {} parent-id)
                                 "child" (if last-child
                                           (move-and-confirm! uuid (str (:uuid last-child)) {} (:id target-block))
                                           (move-and-confirm! uuid target {:children true} (:id target-block)))))))))
                (let [position (choice-param params "position" #{"end" "start"} "end")]
                  (-> (require-page target)
                      (.then (fn [page]
                               (.then (page-tree (page-title page))
                                      (fn [blocks]
                                        (let [others (remove #(= (str (:uuid %)) uuid) blocks)
                                              head (first others)
                                              page-id (:id page)]
                                          (cond
                                            (empty? others)
                                            (fail (str "Page " target " has no other blocks to move next to — use block_insert to write there."))

                                            (= position "end")
                                            (move-and-confirm! uuid (str (:uuid (last others))) {} page-id)

                                            (page-properties-block [head])
                                            (move-and-confirm! uuid (str (:uuid head)) {} page-id)

                                            :else
                                            (move-and-confirm! uuid (str (:uuid head)) {:before true} page-id))))))))))))))))

(defn handle-properties-set [params]
  (let [[target-kind target] (exactly-one params "page" "uuid")
        props (properties-param params "properties" true)
        mode (choice-param params "mode" #{"set" "add" "remove"} "set")]
    (if (= target-kind "page")
      (-> (require-page target)
          (.then (fn [page]
                   (let [title (page-title page)]
                     (.then (write-page-properties! title props mode)
                            #(assoc % :page title))))))
      (-> (require-block target)
          (.then #(write-block-properties! target props mode))
          (.then (fn [written] {:target target :properties written}))))))

(defn journal-day
  "yyyymmdd integer for a YYYY-MM-DD string, or for `now` when date is nil."
  [date now]
  (if date
    (if-let [[_ y m d] (re-matches #"(\d{4})-(\d{2})-(\d{2})" date)]
      (js/parseInt (str y m d) 10)
      (invalid! (str "date must be YYYY-MM-DD, got " (pr-str date))))
    (+ (* 10000 (.getFullYear now)) (* 100 (inc (.getMonth now))) (.getDate now))))

(defn handle-journal-append [params]
  (let [nodes (outline-param params "content")
        day (journal-day (text-param params "date") (js/Date.))]
    (-> (query (str "[:find (pull ?p [:block/original-name]) :where [?p :block/journal-day " day "]]"))
        (.then (fn [rows]
                 (if-let [title (:original-name (ffirst rows))]
                   (.then (insert-into-page! title "end" nodes) (fn [uuids] {:page title :uuids uuids}))
                   (fail (str "No journal page exists for " day
                              " yet. Open that day in Logseq first, or write to a named page with page_create."))))))))

;; =============================================================================
;; Dispatch
;; =============================================================================

(def handlers
  "Bridge operation name → handler."
  {"graph_query"    handle-graph-query
   "graph_search"   handle-graph-search
   "page_list"      handle-page-list
   "page_read"      handle-page-read
   "block_get"      handle-block-get
   "page_links"     handle-page-links
   "page_create"    handle-page-create
   "page_delete"    handle-page-delete
   "page_rename"    handle-page-rename
   "block_insert"   handle-block-insert
   "block_append"   handle-block-append
   "block_update"   handle-block-update
   "block_delete"   handle-block-delete
   "block_move"     handle-block-move
   "properties_set" handle-properties-set
   "journal_append" handle-journal-append})
