(ns logseq-ai-hub.mcp.page-reader-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.mcp.page-reader :as page-reader]))

(defn- mock-logseq-editor!
  "Sets up js/logseq.Editor.getPageBlocksTree mock."
  [page-blocks-map]
  (set! js/logseq
        #js {:Editor #js {:getPageBlocksTree
                          (fn [page-name]
                            (if-let [blocks (get page-blocks-map page-name)]
                              (js/Promise.resolve (clj->js blocks))
                              (js/Promise.resolve nil)))}
             :settings #js {}
             :DB #js {:datascriptQuery (fn [_] #js [])}}))

(deftest read-mcp-page-basic
  (testing "reads MCP page with url and transport"
    (async done
      (mock-logseq-editor!
        {"MCP/brave-search"
         [{:content "mcp-url:: https://brave.example.com/mcp\nmcp-transport:: streamable-http\nmcp-description:: Brave Search"}]})
      (-> (page-reader/read-mcp-page "MCP/brave-search")
          (.then (fn [config]
                   (is (some? config))
                   (is (= "brave-search" (:id config)))
                   (is (= "MCP/brave-search" (:name config)))
                   (is (= "https://brave.example.com/mcp" (:url config)))
                   (is (= :streamable-http (:transport-type config)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest read-mcp-page-defaults-transport
  (testing "defaults to streamable-http when transport not specified"
    (async done
      (mock-logseq-editor!
        {"MCP/simple"
         [{:content "mcp-url:: https://server.example.com"}]})
      (-> (page-reader/read-mcp-page "MCP/simple")
          (.then (fn [config]
                   (is (some? config))
                   (is (= :streamable-http (:transport-type config)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest read-mcp-page-missing-url
  (testing "returns nil when mcp-url is missing"
    (async done
      (mock-logseq-editor!
        {"MCP/no-url"
         [{:content "mcp-description:: No URL here"}]})
      (-> (page-reader/read-mcp-page "MCP/no-url")
          (.then (fn [config]
                   (is (nil? config))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest read-mcp-page-nonexistent
  (testing "returns nil for nonexistent page"
    (async done
      (mock-logseq-editor! {})
      (-> (page-reader/read-mcp-page "MCP/missing")
          (.then (fn [config]
                   (is (nil? config))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest read-mcp-page-with-auth
  (testing "reads auth token from page properties"
    (async done
      (mock-logseq-editor!
        {"MCP/authed"
         [{:content "mcp-url:: https://server.example.com\nmcp-auth-token:: sk-test-123"}]})
      (-> (page-reader/read-mcp-page "MCP/authed")
          (.then (fn [config]
                   (is (some? config))
                   (is (= "sk-test-123" (:auth-token config)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest scan-mcp-pages-test
  (testing "scan-mcp-pages queries for MCP/ prefix pages"
    (async done
      (set! js/logseq
            #js {:DB #js {:datascriptQuery
                          (fn [_query]
                            (clj->js [[{:block/name "mcp/brave-search"
                                        :block/original-name "MCP/brave-search"}]
                                      [{:block/name "mcp/github"
                                        :block/original-name "MCP/github"}]]))}
                 :Editor #js {}
                 :settings #js {}})
      (-> (page-reader/scan-mcp-pages)
          (.then (fn [pages]
                   (is (= ["MCP/brave-search" "MCP/github"] pages))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))
