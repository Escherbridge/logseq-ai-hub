(ns logseq-ai-hub.mcp.page-reader
  "Reads MCP server definitions from Logseq pages with MCP/ prefix.

   Page format (first block properties):
     mcp-url:: https://server.example.com/mcp
     mcp-transport:: streamable-http
     mcp-auth-token:: sk-xxx (or {{secret.KEY}})
     mcp-description:: Human-readable description"
  (:require [logseq-ai-hub.job-runner.parser :as parser]
            [logseq-ai-hub.job-runner.interpolation :as interpolation]
            [clojure.string :as str]))

(def ^:private mcp-prefix "MCP/")

(defn read-mcp-page
  "Reads an MCP server definition from a Logseq page.
   page-name should be like 'MCP/brave-search'.
   Returns a Promise resolving to a config map:
     {:id \"brave-search\"
      :name \"MCP/brave-search\"
      :url \"https://...\"
      :transport-type :streamable-http
      :auth-token \"resolved-value\"}
   or nil if page is not found or lacks mcp-url."
  [page-name]
  (-> (js/logseq.Editor.getPageBlocksTree page-name)
      (.then (fn [blocks]
               (when (and blocks (pos? (.-length blocks)))
                 (let [first-block (aget blocks 0)
                       content (.-content first-block)
                       props (parser/parse-block-properties content)]
                   (when-let [url (:mcp-url props)]
                     (let [auth-raw (:mcp-auth-token props)
                           ;; Resolve {{secret.KEY}} in auth token
                           auth-token (when (and auth-raw (not (str/blank? auth-raw)))
                                        (interpolation/interpolate auth-raw nil))]
                       {:id (subs page-name (count mcp-prefix))
                        :name page-name
                        :url url
                        :transport-type (keyword (or (:mcp-transport props) "streamable-http"))
                        :auth-token auth-token}))))))
      (.catch (fn [err]
                (js/console.error "Error reading MCP page:" page-name err)
                nil))))

(defn scan-mcp-pages
  "Scans for all pages with the MCP/ prefix.
   Returns a Promise resolving to a vector of page names."
  []
  (let [query (str "[:find (pull ?p [:block/name :block/original-name]) "
                   ":where [?p :block/name ?name] "
                   "[(clojure.string/starts-with? ?name \"mcp/\")]]")]
    (-> (js/Promise.resolve (js/logseq.DB.datascriptQuery query))
        (.then (fn [results]
                 (let [pages (js->clj results :keywordize-keys true)]
                   (mapv (fn [[page-info]] (:block/original-name page-info)) pages)))))))
