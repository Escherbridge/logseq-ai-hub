(ns logseq-ai-hub.memory-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.memory :as memory]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def created-pages (atom []))
(def appended-blocks (atom []))
(def deleted-pages (atom []))
(def datalog-queries (atom []))
(def get-page-calls (atom []))

(defn setup-mocks!
  "Resets all mock state and installs global mocks for logseq."
  []
  ;; Reset mock tracking
  (reset! created-pages [])
  (reset! appended-blocks [])
  (reset! deleted-pages [])
  (reset! datalog-queries [])
  (reset! get-page-calls [])

  ;; Reset memory module state
  (reset! memory/state
    {:config {:page-prefix "AI-Memory/" :enabled false}
     :index {}})

  ;; Mock logseq
  (set! js/logseq
    #js {:Editor #js {:createPage
                      (fn [name _props _opts]
                        (swap! created-pages conj name)
                        (js/Promise.resolve #js {:name name}))
                      :appendBlockInPage
                      (fn [name content]
                        (swap! appended-blocks conj {:page name :content content})
                        (js/Promise.resolve #js {:uuid "mock-uuid"}))
                      :deletePage
                      (fn [name]
                        (swap! deleted-pages conj name)
                        (js/Promise.resolve nil))
                      :getPageBlocksTree
                      (fn [page-name]
                        (swap! get-page-calls conj page-name)
                        ;; Return mock blocks for test pages
                        (if (= page-name "AI-Memory/test-tag")
                          (js/Promise.resolve
                            #js [#js {:uuid "block-1"
                                      :content "Test memory 1\nstored-at:: 2026-02-11T14:30:00.000Z\nmemory-tag:: test-tag"}
                                 #js {:uuid "block-2"
                                      :content "Test memory 2\nstored-at:: 2026-02-11T14:31:00.000Z\nmemory-tag:: test-tag"}])
                          (js/Promise.reject (js/Error. "Page not found"))))
                      :registerSlashCommand
                      (fn [_name _handler]
                        nil)}
         :DB #js {:datascriptQuery
                  (fn [query]
                    (swap! datalog-queries conj query)
                    ;; Return mock results based on query
                    (cond
                      ;; Query for searching memories
                      (.includes query "includes?")
                      (js/Promise.resolve
                        (clj->js [[{"block/content" "Memory about cats\nstored-at:: 2026-02-11T14:30:00.000Z\nmemory-tag:: animals"
                                    "block/uuid" "uuid-1"}]
                                  [{"block/content" "Another cat memory\nstored-at:: 2026-02-11T14:31:00.000Z\nmemory-tag:: pets"
                                    "block/uuid" "uuid-2"}]]))

                      ;; Query for clearing memories (finding pages)
                      (.includes query "starts-with?")
                      (js/Promise.resolve
                        (clj->js [[{"block/name" "ai-memory/tag1"}]
                                  [{"block/name" "ai-memory/tag2"}]]))

                      :else
                      (js/Promise.resolve #js [])))}
         :App #js {:showMsg (fn [_msg _type] nil)}
         :settings #js {"memoryEnabled" false
                        "memoryPagePrefix" "AI-Memory/"}}))

;; ---------------------------------------------------------------------------
;; Pure Function Tests
;; ---------------------------------------------------------------------------

(deftest test-memory-page-name
  (setup-mocks!)
  (testing "generates correct memory page name"
    (is (= "AI-Memory/test-tag"
           (memory/memory-page-name "AI-Memory/" "test-tag"))))

  (testing "works with different prefixes"
    (is (= "Memory/personal"
           (memory/memory-page-name "Memory/" "personal")))))

(deftest test-format-memory-block
  (setup-mocks!)
  (testing "formats memory block with content and properties"
    (let [result (memory/format-memory-block "Remember this important thing" "important")]
      (is (.includes result "Remember this important thing"))
      (is (.includes result "stored-at::"))
      (is (.includes result "memory-tag:: important")))))

;; ---------------------------------------------------------------------------
;; Storage Tests
;; ---------------------------------------------------------------------------

(deftest test-store-memory-when-enabled
  (setup-mocks!)
  (testing "store-memory! creates page and appends block when enabled"
    (async done
      (swap! memory/state assoc-in [:config :enabled] true)
      (-> (memory/store-memory! "test-tag" "Test memory content")
          (.then (fn [_]
                   (is (some #(= "AI-Memory/test-tag" %) @created-pages))
                   (is (= 1 (count @appended-blocks)))
                   (let [{:keys [page content]} (first @appended-blocks)]
                     (is (= "AI-Memory/test-tag" page))
                     (is (.includes content "Test memory content"))
                     (is (.includes content "stored-at::"))
                     (is (.includes content "memory-tag:: test-tag")))
                   (done)))))))

(deftest test-store-memory-when-disabled
  (setup-mocks!)
  (testing "store-memory! rejects when memory system is disabled"
    (async done
      (swap! memory/state assoc-in [:config :enabled] false)
      (-> (memory/store-memory! "test-tag" "Test memory")
          (.then (fn [_] (is false "Should have rejected") (done)))
          (.catch (fn [err]
                    (is (.includes (.-message err) "not enabled"))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Retrieval Tests
;; ---------------------------------------------------------------------------

(deftest test-retrieve-by-tag
  (setup-mocks!)
  (testing "retrieve-by-tag returns blocks from a tag page"
    (async done
      (swap! memory/state assoc-in [:config :enabled] true)
      (-> (memory/retrieve-by-tag "test-tag")
          (.then (fn [results]
                   (is (= 2 (count results)))
                   (is (.includes (:content (first results)) "Test memory 1"))
                   (is (some #(= "AI-Memory/test-tag" %) @get-page-calls))
                   (done)))))))

(deftest test-retrieve-by-tag-when-disabled
  (setup-mocks!)
  (testing "retrieve-by-tag rejects when memory system is disabled"
    (async done
      (swap! memory/state assoc-in [:config :enabled] false)
      (-> (memory/retrieve-by-tag "test-tag")
          (.then (fn [_] (is false "Should have rejected") (done)))
          (.catch (fn [err]
                    (is (.includes (.-message err) "not enabled"))
                    (done)))))))

(deftest test-retrieve-by-tag-nonexistent-page
  (setup-mocks!)
  (testing "retrieve-by-tag returns empty array for nonexistent page"
    (async done
      (swap! memory/state assoc-in [:config :enabled] true)
      (-> (memory/retrieve-by-tag "nonexistent")
          (.then (fn [results]
                   (is (= [] results))
                   (done)))))))

(deftest test-retrieve-memories
  (setup-mocks!)
  (testing "retrieve-memories searches across pages"
    (async done
      (swap! memory/state assoc-in [:config :enabled] true)
      (-> (memory/retrieve-memories "cats")
          (.then (fn [results]
                   (is (= 2 (count results)))
                   (is (.includes (:block/content (first results)) "cats"))
                   (is (= 1 (count @datalog-queries)))
                   (let [query (first @datalog-queries)]
                     (is (.includes query "includes?"))
                     (is (.includes query "cats"))
                     (is (.includes query "ai-memory/")))
                   (done)))))))

(deftest test-retrieve-memories-empty-query
  (setup-mocks!)
  (testing "retrieve-memories returns empty array for blank query"
    (async done
      (swap! memory/state assoc-in [:config :enabled] true)
      (-> (memory/retrieve-memories "")
          (.then (fn [results]
                   (is (= [] results))
                   (is (= 0 (count @datalog-queries)))
                   (done)))))))

(deftest test-retrieve-memories-when-disabled
  (setup-mocks!)
  (testing "retrieve-memories rejects when memory system is disabled"
    (async done
      (swap! memory/state assoc-in [:config :enabled] false)
      (-> (memory/retrieve-memories "test")
          (.then (fn [_] (is false "Should have rejected") (done)))
          (.catch (fn [err]
                    (is (.includes (.-message err) "not enabled"))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Clear Tests
;; ---------------------------------------------------------------------------

(deftest test-clear-memories
  (setup-mocks!)
  (testing "clear-memories! deletes all memory pages"
    (async done
      (swap! memory/state assoc-in [:config :enabled] true)
      (swap! memory/state assoc :index {:tag1 ["uuid1"] :tag2 ["uuid2"]})
      (-> (memory/clear-memories!)
          (.then (fn [_]
                   (is (= 1 (count @datalog-queries)))
                   (is (= 2 (count @deleted-pages)))
                   (is (some #(= "ai-memory/tag1" %) @deleted-pages))
                   (is (some #(= "ai-memory/tag2" %) @deleted-pages))
                   (is (= {} (:index @memory/state)))
                   (done)))))))

(deftest test-clear-memories-when-disabled
  (setup-mocks!)
  (testing "clear-memories! rejects when memory system is disabled"
    (async done
      (swap! memory/state assoc-in [:config :enabled] false)
      (-> (memory/clear-memories!)
          (.then (fn [_] (is false "Should have rejected") (done)))
          (.catch (fn [err]
                    (is (.includes (.-message err) "not enabled"))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Init Tests
;; ---------------------------------------------------------------------------

(deftest test-init-when-enabled
  (setup-mocks!)
  (testing "init! reads settings and updates state when enabled"
    (aset js/logseq "settings" #js {"memoryEnabled" true
                                     "memoryPagePrefix" "Memory/"})
    (memory/init!)
    (is (true? (get-in @memory/state [:config :enabled])))
    (is (= "Memory/" (get-in @memory/state [:config :page-prefix])))))

(deftest test-init-when-disabled
  (setup-mocks!)
  (testing "init! reads settings and updates state when disabled"
    (aset js/logseq "settings" #js {"memoryEnabled" false
                                     "memoryPagePrefix" "AI-Memory/"})
    (memory/init!)
    (is (false? (get-in @memory/state [:config :enabled])))
    (is (= "AI-Memory/" (get-in @memory/state [:config :page-prefix])))))

(deftest test-init-default-prefix
  (setup-mocks!)
  (testing "init! uses default prefix when not specified"
    (aset js/logseq "settings" #js {"memoryEnabled" true})
    (swap! memory/state assoc-in [:config :page-prefix] "AI-Memory/")
    (memory/init!)
    (is (= "AI-Memory/" (get-in @memory/state [:config :page-prefix])))))

;; ---------------------------------------------------------------------------
;; State Management Tests
;; ---------------------------------------------------------------------------

(deftest test-state-structure
  (setup-mocks!)
  (testing "state has correct initial structure"
    (is (map? @memory/state))
    (is (contains? @memory/state :config))
    (is (contains? @memory/state :index))
    (is (contains? (:config @memory/state) :page-prefix))
    (is (contains? (:config @memory/state) :enabled))))
