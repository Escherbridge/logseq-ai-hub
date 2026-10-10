(ns logseq-ai-hub.fake-logseq
  "In-memory Logseq host for integration tests. DB.datascriptQuery runs real
   datascript and post-processes results the way Logseq's api.cljs does
   (keyword namespaces dropped, uuids stringified). Editor methods operate on the
   same database and return the plugin API's shapes: camelCase keys, {id n}
   refs, and [\"uuid\" ...] child tuples unless includeChildren is set."
  (:require [datascript.core :as d]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def ^:private schema
  {:block/name {:db/unique :db.unique/identity}
   :block/uuid {:db/unique :db.unique/identity}
   :block/page {:db/valueType :db.type/ref}
   :block/parent {:db/valueType :db.type/ref}
   :block/refs {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :block/tags {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}})

;; ---------------------------------------------------------------------------
;; Content → derived attributes (a small slice of Logseq's file-graph parser)

(def ^:private property-line-re #"^\s*([A-Za-z][A-Za-z0-9_-]*):: (.*)$")

(defn- link-names [text] (map second (re-seq #"\[\[([^\]]+)\]\]" text)))

(defn- property-value [key value]
  (let [links (link-names value)]
    (cond (seq links) (set links)
          (#{"tags" "alias"} (str/lower-case key)) (set (map str/trim (str/split value #",")))
          (#{"true" "false"} value) (= value "true")
          :else value)))

(defn- parse-properties [content]
  (into {}
        (keep (fn [line]
                (when-let [[_ k v] (re-matches property-line-re line)]
                  (when-not (= "id" (str/lower-case k))
                    [(keyword (str/lower-case k)) (property-value k v)]))))
        (str/split-lines (or content ""))))

(defn- properties-only? [content]
  (let [lines (remove str/blank? (str/split-lines (or content "")))]
    (and (seq lines) (every? #(re-matches property-line-re %) lines))))

(defn- ref-names
  "Like Logseq: links, #tags, ref-valued properties, and a page per property key."
  [content]
  (distinct (concat (map name (keys (parse-properties content)))
                    (link-names content)
                    (map second (re-seq #"(?:^|\s)#([\w-]+)" content))
                    (mapcat (fn [[_ v]] (when (set? v) v)) (parse-properties content)))))

;; ---------------------------------------------------------------------------
;; Store primitives

(defn- page-eid [db page-name]
  (when (string? page-name) (:db/id (d/entity db [:block/name (str/lower-case page-name)]))))

(defn- block-eid [db identity]
  (let [eid (cond (number? identity) identity
                  (string? identity) (:db/id (d/entity db [:block/uuid (uuid identity)]))
                  :else nil)]
    (when (and eid (:block/page (d/entity db eid))) eid)))

(defn- ensure-page! [conn page-name]
  (or (page-eid @conn page-name)
      (get-in (d/transact! conn [{:db/id -1
                                  :block/name (str/lower-case page-name)
                                  :block/original-name page-name
                                  :block/uuid (random-uuid)
                                  :block/updated-at (js/Date.now)}])
              [:tempids -1])))

(defn- children-eids [db parent]
  (->> (d/q '[:find ?c ?o :in $ ?p :where [?c :block/parent ?p] [?c :fake/order ?o]] db parent)
       (sort-by second)
       (mapv first)))

(defn- pre-block? [db eid]
  (let [e (d/entity db eid)
        page (:db/id (:block/page e))]
    (and (= page (:db/id (:block/parent e)))
         (= eid (first (children-eids db page)))
         (properties-only? (:block/content e)))))

(defn- sync-page!
  "Page properties and tags come from the page's properties block, as in Logseq."
  [conn page]
  (let [db @conn
        head (first (children-eids db page))
        props (if (and head (pre-block? db head)) (:block/properties (d/entity db head)) {})
        tag-ids (mapv #(ensure-page! conn %) (let [t (:tags props)] (if (set? t) t [])))]
    (d/transact! conn [[:db.fn/retractAttribute page :block/tags]
                       (cond-> {:db/id page :block/properties props :block/updated-at (js/Date.now)}
                         (seq tag-ids) (assoc :block/tags tag-ids))])))

(defn- set-content! [conn eid content]
  (let [ref-ids (mapv #(ensure-page! conn %) (ref-names content))]
    (d/transact! conn [[:db.fn/retractAttribute eid :block/refs]
                       (cond-> {:db/id eid
                                :block/content content
                                :block/properties (parse-properties content)}
                         (seq ref-ids) (assoc :block/refs ref-ids))])
    (sync-page! conn (:db/id (:block/page (d/entity @conn eid))))))

(defn- slot
  "An order value placing a block at `where` among `parent`'s children."
  [db parent anchor where]
  (let [siblings (children-eids db parent)
        orders (mapv #(:fake/order (d/entity db %)) siblings)
        i (.indexOf siblings anchor)]
    (case where
      :last (if (seq orders) (inc (peek orders)) 0)
      :first (if (seq orders) (dec (first orders)) 0)
      :after (if (< (inc i) (count orders)) (/ (+ (nth orders i) (nth orders (inc i))) 2) (inc (nth orders i)))
      :before (if (pos? i) (/ (+ (nth orders i) (nth orders (dec i))) 2) (dec (nth orders i))))))

(defn- placement
  "[page parent order] for a new or moved block relative to `anchor`."
  [db anchor where]
  (let [a (d/entity db anchor)
        page (:db/id (:block/page a))]
    (if (#{:last-child} where)
      [page anchor (slot db anchor nil :last)]
      (let [parent (:db/id (:block/parent a))]
        [page parent (slot db parent anchor where)]))))

(defn- create-block! [conn page parent order content]
  (let [eid (get-in (d/transact! conn [{:db/id -1 :block/uuid (random-uuid) :block/page page
                                        :block/parent parent :fake/order order}])
                    [:tempids -1])]
    (set-content! conn eid content)
    eid))

(defn- subtree [db eid] (cons eid (mapcat #(subtree db %) (children-eids db eid))))

;; ---------------------------------------------------------------------------
;; Plugin-API shapes

(defn- camel [k] (str/replace k #"-(\w)" #(str/upper-case (second %))))

(defn- api-properties [props]
  (into {} (map (fn [[k v]] [(camel (name k)) (if (set? v) (vec (sort v)) v)])) props))

(defn- page-map [db eid]
  (let [e (d/entity db eid)]
    (cond-> {"id" eid
             "uuid" (str (:block/uuid e))
             "name" (:block/name e)
             "originalName" (:block/original-name e)
             "journal?" (boolean (:block/journal? e))
             "properties" (api-properties (:block/properties e))}
      (:block/journal-day e) (assoc "journalDay" (:block/journal-day e))
      (:block/updated-at e) (assoc "updatedAt" (:block/updated-at e)))))

(defn- block-map [db eid include-children?]
  (let [e (d/entity db eid)
        kids (children-eids db eid)]
    {"id" eid
     "uuid" (str (:block/uuid e))
     "content" (:block/content e)
     "page" {"id" (:db/id (:block/page e))}
     "parent" {"id" (:db/id (:block/parent e))}
     "properties" (api-properties (:block/properties e))
     "preBlock?" (boolean (:fake/flagged-pre? e))   ; real Logseq sets this only when parsing a file, e.g. after createPage with properties
     "children" (if include-children?
                  (mapv #(block-map db % true) kids)
                  (mapv (fn [k] ["uuid" (str (:block/uuid (d/entity db k)))]) kids))}))

(defn- resolved [x] (js/Promise.resolve (clj->js x)))

(defn- normalize-for-json
  "What Logseq's datascript_query does to results before handing them to plugins."
  [x]
  (walk/postwalk (fn [a] (cond (keyword? a) (name a) (uuid? a) (str a) :else a)) x))

(defn- options [opts] (if opts (js->clj opts :keywordize-keys true) {}))

;; ---------------------------------------------------------------------------
;; Host

(defn- editor [conn]
  {:getPage
   (fn [identity]
     (let [db @conn
           eid (cond (number? identity) (when (:block/name (d/entity db identity)) identity)
                     :else (page-eid db identity))]
       (resolved (when eid (page-map db eid)))))

   :getPageBlocksTree
   (fn [page-name]
     (let [db @conn page (page-eid db page-name)]
       (resolved (when page (mapv #(block-map db % true) (children-eids db page))))))

   :getBlock
   (fn [identity opts]
     (let [db @conn eid (block-eid db identity)]
       (resolved (when eid (block-map db eid (:includeChildren (options opts)))))))

   :insertBlock
   (fn [anchor content opts]
     (let [{:keys [sibling before]} (options opts)
           db @conn
           anchor-eid (block-eid db anchor)
           [page parent order] (placement db anchor-eid (cond before :before sibling :after :else :last-child))
           eid (create-block! conn page parent order content)]
       (resolved (block-map @conn eid false))))

   :appendBlockInPage
   (fn [page-name content]
     (if-let [page (page-eid @conn page-name)]
       (let [eid (create-block! conn page page (slot @conn page nil :last) content)]
         (resolved (block-map @conn eid false)))
       (resolved nil)))

   :updateBlock
   (fn [uuid content]
     (set-content! conn (block-eid @conn uuid) content)
     (resolved nil))

   :removeBlock
   (fn [uuid]
     (when-let [eid (block-eid @conn uuid)]
       (let [page (:db/id (:block/page (d/entity @conn eid)))]
         (d/transact! conn (mapv (fn [e] [:db.fn/retractEntity e]) (subtree @conn eid)))
         (sync-page! conn page)))
     (resolved nil))

   :moveBlock
   (fn [src target opts]
     (let [{:keys [before children]} (options opts)
           db @conn
           src-eid (block-eid db src)
           old-page (:db/id (:block/page (d/entity db src-eid)))
           [page parent order] (placement db (block-eid db target)
                                          (cond before :before children :last-child :else :after))]
       (d/transact! conn (into [{:db/id src-eid :block/parent parent :fake/order order}]
                               (map (fn [e] {:db/id e :block/page page}) (subtree db src-eid))))
       (sync-page! conn old-page)
       (sync-page! conn page)
       (resolved nil)))

   :createPage
   (fn [page-name props _opts]
     (let [existing (page-eid @conn page-name)
           eid (or existing (ensure-page! conn page-name))
           props (js->clj props)]
       (when (and (not existing) (seq props))
         (let [pre (create-block! conn eid eid 0 (str/join "\n" (map (fn [[k v]] (str k ":: " v)) props)))]
           (d/transact! conn [{:db/id pre :fake/flagged-pre? true}])))
       (resolved (page-map @conn eid))))

   :deletePage
   (fn [page-name]
     (when-let [page (page-eid @conn page-name)]
       (let [blocks (d/q '[:find [?b ...] :in $ ?p :where [?b :block/page ?p]] @conn page)]
         (d/transact! conn (mapv (fn [e] [:db.fn/retractEntity e]) (cons page blocks)))))
     (resolved nil))

   :renamePage
   (fn [old-name new-name]
     (let [page (page-eid @conn old-name)
           pattern (re-pattern (str "(?i)\\[\\[" (str/replace old-name #"[.*+?^${}()|\[\]\\]" "\\$&") "\\]\\]"))]
       (d/transact! conn [{:db/id page :block/name (str/lower-case new-name) :block/original-name new-name}])
       (doseq [[eid content] (d/q '[:find ?b ?c :where [?b :block/content ?c]] @conn)]
         (when (re-find pattern content)
           (set-content! conn eid (str/replace content pattern (str "[[" new-name "]]")))))
       (resolved nil)))

   :getPageLinkedReferences
   (fn [page-name]
     (let [db @conn
           page (page-eid db page-name)
           rows (d/q '[:find ?src ?b :in $ ?p :where [?b :block/refs ?p] [?b :block/page ?src] [(not= ?src ?p)]] db page)]
       (resolved (->> rows
                      (group-by first)
                      (sort-by (comp :block/original-name #(d/entity db %) key))
                      (mapv (fn [[src pairs]]
                              [(page-map db src) (mapv #(block-map db (second %) false) pairs)]))))))

   :upsertBlockProperty
   (fn [uuid key value]
     (let [eid (block-eid @conn uuid)
           lines (str/split-lines (:block/content (d/entity @conn eid)))
           line-re (re-pattern (str "(?i)^\\s*" key ":: "))
           new-line (str key ":: " value)
           replaced (mapv #(if (re-find line-re %) new-line %) lines)
           lines (if (= replaced lines) (conj lines new-line) replaced)]
       (set-content! conn eid (str/join "\n" lines))
       (resolved nil)))

   :removeBlockProperty
   (fn [uuid key]
     (let [eid (block-eid @conn uuid)
           line-re (re-pattern (str "(?i)^\\s*" key ":: "))]
       (set-content! conn eid (->> (str/split-lines (:block/content (d/entity @conn eid)))
                                   (remove #(re-find line-re %))
                                   (str/join "\n")))
       (resolved nil)))})

(defn- js-object [fns] (clj->js (into {} (map (fn [[k f]] [(name k) f])) fns)))

(defn install!
  "Points js/logseq at a fresh fake graph and returns its datascript conn."
  []
  (let [conn (d/create-conn schema)]
    (set! js/logseq
          #js {:settings #js {}
               :Editor (js-object (editor conn))
               :DB #js {:datascriptQuery
                        (fn [q & inputs]
                          (resolved (normalize-for-json (apply d/q (reader/read-string q) @conn inputs))))}})
    conn))

(defn- seed-blocks! [conn page parent specs]
  (doseq [[i spec] (map-indexed vector specs)]
    (let [[content & kids] (if (string? spec) [spec] spec)
          eid (create-block! conn page parent i content)]
      (seed-blocks! conn page eid kids))))

(defn seed!
  "Adds pages: [{:name \"P\" :journal-day 20261009 :blocks [\"text\" [\"parent\" \"child\"]]}].
   A block spec is a string or [content & child-specs]."
  [conn pages]
  (doseq [{:keys [name journal-day blocks]} pages]
    (let [page (ensure-page! conn name)]
      (when journal-day
        (d/transact! conn [{:db/id page :block/journal? true :block/journal-day journal-day}]))
      (seed-blocks! conn page page blocks))))

(defn uuid-of
  "uuid string of the block whose first line is exactly `first-line`."
  [conn first-line]
  (some (fn [[u c]] (when (= first-line (first (str/split-lines c))) (str u)))
        (d/q '[:find ?u ?c :where [?b :block/content ?c] [?b :block/uuid ?u]] @conn)))
