(ns logseq-ai-hub.graph-ops-test
  "Graph bridge operations driven end-to-end against a fake Logseq host backed
   by real datascript. Results pass through a JSON round trip, so assertions see
   exactly what the server receives."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as str]
            [logseq-ai-hub.agent-bridge :as bridge]
            [logseq-ai-hub.graph-ops :as ops]
            [logseq-ai-hub.fake-logseq :as fake]))

(defn- over-the-wire [x]
  (-> x clj->js js/JSON.stringify js/JSON.parse (js->clj :keywordize-keys true)))

(defn- op [operation params]
  (-> (bridge/run-operation (get bridge/operation-handlers operation) params)
      (.then over-the-wire)))

(defn- op-error
  "Resolves to the rejection message; a resolved op is itself a test failure."
  [operation params]
  (-> (op operation params)
      (.then (fn [result] (is false (str operation " should have failed, got " (pr-str result))) "")
             (fn [err] (or (.-message err) (str err))))))

(defn- shape
  "Node tree → [content & child-shapes], ignoring uuids."
  [nodes]
  (mapv (fn [n] (into [(:content n)] (shape (:children n)))) nodes))

(defn- body-shape
  "Page shape without the page-properties block."
  [page-read]
  (shape (remove :pre (:blocks page-read))))

(defn- run-flow [done promise]
  (-> promise
      (.then (fn [_] (done)))
      (.catch (fn [err] (is false (str "flow failed: " (or (.-message err) err))) (done)))))

;; ---------------------------------------------------------------------------
;; Outline parsing — pure, real branching

(deftest parse-outline-cases
  (doseq [[label text expected]
          [["text without bullets is one block" "Just a note\nsecond line" [["Just a note\nsecond line"]]]
           ["indentation nests" "- a\n  - b\n    - c\n- d" [["a" ["b" ["c"]]] ["d"]]]
           ["tabs nest like spaces" "- a\n\t- b" [["a" ["b"]]]]
           ["non-bullet lines continue the block" "- a\n  more of a\n- b" [["a\nmore of a"] ["b"]]]
           ["bullets inside a code fence stay content"
            "- code\n  ```\n  - not a bullet\n  ```\n- after"
            [["code\n```\n- not a bullet\n```"] ["after"]]]
           ["text before the first bullet is its own block" "Intro\n- a" [["Intro"] ["a"]]]
           ["uneven deeper indents are both children" "- a\n    - b\n  - c" [["a" ["b"] ["c"]]]]
           ["property lines stay in the block" "- title\n  type:: book" [["title\ntype:: book"]]]
           ["blank input is nothing" "  \n " []]]]
    (is (= expected (shape (ops/parse-outline text))) label)))

(deftest property-value-semantics
  (doseq [[label value current mode expected]
          [["string is verbatim" "learning" nil "set" "learning"]
           ["array becomes links" ["A" "B"] nil "set" "[[A]], [[B]]"]
           ["null removes" nil "x" "set" nil]
           ["add merges without case duplicates" ["go" "Zig"] ["Go"] "add" "[[Go]], [[Zig]]"]
           ["add reads links out of text values" "C" "[[A]], [[B]]" "add" "[[A]], [[B]], [[C]]"]
           ["remove drops names" ["go"] ["Go" "Zig"] "remove" "[[Zig]]"]
           ["removing the last name removes the property" ["Go"] ["Go"] "remove" nil]]]
    (is (= expected (ops/final-value value current mode)) label))
  (is (thrown-with-msg? js/Error #"plain text" (ops/final-value ["world"] "Hello, world" "remove"))
      "merging into prose would rewrite it as [[Hello]] links"))

(deftest preserve-id-property-cases
  (is (= "edited\nid:: 6f1" (ops/preserve-id-property "old\nid:: 6f1" "edited"))
      "dropping id:: would orphan every ((uuid)) reference after a re-index")
  (is (= "edited\nid:: 777" (ops/preserve-id-property "old\nid:: 6f1" "edited\nid:: 777"))
      "an explicit id line in the new content wins")
  (is (= "edited" (ops/preserve-id-property "old" "edited"))))

;; ---------------------------------------------------------------------------
;; Flows

(deftest build-a-note-graph
  (testing "create a page from an outline with properties, find it by tag and property, read it back"
    (fake/install!)
    (async done
      (run-flow done
        (-> (op "page_create" {"name" "Rust"
                               "content" "- Ownership\n  - Borrowing\n- Lifetimes"
                               "properties" {"tags" ["language" "systems"] "status" "learning"}})
            (.then (fn [r]
                     (is (= {:page "Rust" :created true} (select-keys r [:page :created])))
                     (is (= 3 (count (:uuids r))) "every created block's uuid is returned")
                     (op "page_read" {"name" "rust"})))
            (.then (fn [r]
                     (is (= "Rust" (get-in r [:page :name])) "lookup is case-insensitive; title keeps its case")
                     (is (= [["Ownership" ["Borrowing"]] ["Lifetimes"]] (body-shape r)))
                     (let [props (first (filter :pre (:blocks r)))]
                       (is (str/includes? (:content props) "tags:: [[language]], [[systems]]"))
                       (is (str/includes? (:content props) "status:: learning")))
                     (op "page_list" {"tag" "Language"})))
            (.then (fn [r]
                     (is (= ["Rust"] (map :name (:pages r))))
                     (is (= ["language" "systems"] (sort (:tags (first (:pages r))))))
                     (op "page_list" {"property" {"key" "status" "value" "Learning"}})))
            (.then (fn [r]
                     (is (= ["Rust"] (map :name (:pages r))))
                     (op-error "page_create" {"name" "rust"})))
            (.then (fn [msg]
                     (is (str/includes? msg "already exists"))
                     (op "page_create" {"name" "Rust" "if_exists" "append" "content" "- Traits"})))
            (.then (fn [r]
                     (is (false? (:created r)))
                     (op "page_read" {"name" "Rust"})))
            (.then (fn [r]
                     (is (= ["Traits"] (last (body-shape r))) "append lands at the end"))))))))

(deftest search-the-graph
  (testing "multi-term, case-insensitive search that treats regex characters literally"
    (let [conn (fake/install!)]
      (fake/seed! conn [{:name "Alpha" :blocks ["Meeting notes about Rust ownership"
                                                ["c++ templates are hard" "nested RUST detail"]]}
                        {:name "Beta" :blocks ["rust and go"]}
                        {:name "Rust Lang" :blocks ["homepage"]}])
      (async done
        (run-flow done
          (-> (op "graph_search" {"query" "ownership rust"})
              (.then (fn [r]
                       (is (= 1 (:total r)) "every term must match, in any order")
                       (is (= {:page "Alpha" :uuid (fake/uuid-of conn "Meeting notes about Rust ownership")}
                              (select-keys (first (:blocks r)) [:page :uuid])))
                       (op "graph_search" {"query" "RUST"})))
              (.then (fn [r]
                       (is (= 3 (:total r)) "matching ignores case")
                       (is (= ["Rust Lang"] (:pages r)) "page titles match too")
                       (op "graph_search" {"query" "c++"})))
              (.then (fn [r]
                       (is (= 1 (:total r)) "+ is matched literally, not as a regex quantifier")
                       (op "graph_search" {"query" "rust" "page" "beta" "limit" 5})))
              (.then (fn [r]
                       (is (= ["Beta"] (map :page (:blocks r))) "page restricts the search")
                       (op "graph_search" {"query" "rust" "limit" 1})))
              (.then (fn [r]
                       (is (= [1 3] [(count (:blocks r)) (:total r)]) "limit caps blocks but total stays honest")
                       (op-error "graph_search" {"query" "rust" "page" "Gamma"})))
              (.then (fn [msg]
                       (is (str/includes? msg "Page not found: Gamma") "a misspelled page is not 'no matches'")
                       (op-error "graph_search" {"query" "a b c d e f g h i"})))
              (.then (fn [msg] (is (str/includes? msg "at most 8 terms") "extra terms are refused, not dropped")))))))))

(deftest blank-page-cases
  (doseq [[label blocks expected]
          [["no blocks" [] true]
           ["Logseq's blank placeholder" [{:content "" :children []}] true]
           ["only a properties block" [{:content "concepts:: [[A]]" :children []}] true]
           ["real content" [{:content "concepts:: [[A]]" :children []} {:content "summary" :children []}] false]
           ["properties-only text that is not first is content" [{:content "x"} {:content "k:: v"}] false]
           ["a blank block with children has content" [{:content "" :children [{:content "kid"}]}] false]]]
    (is (= expected (ops/blank-page? blocks)) label)))

(deftest link-first-write-later
  (testing "a page that exists only because something links to it is written, not skipped"
    (let [conn (fake/install!)]
      ;; Linking creates the target pages, exactly as the first live corpus run did.
      (fake/seed! conn [{:name "Notes" :blocks ["see [[Later]] and [[Hub]]"]}])
      (async done
        (run-flow done
          (-> (op "page_create" {"name" "Later" "content" "- body" "if_exists" "skip"})
              (.then (fn [r]
                       (is (true? (:created r)) "a reference-only page is filled despite skip")
                       (op "properties_set" {"page" "Hub" "properties" {"concepts" ["Later"]} "mode" "add"})))
              (.then (fn [_]
                       (op "page_create" {"name" "Hub" "content" "- summary"
                                          "properties" {"type" ["paper"]} "if_exists" "skip"})))
              (.then (fn [r]
                       (is (true? (:created r)) "a page holding only a properties block is still unwritten")
                       (op "page_read" {"name" "Hub"})))
              (.then (fn [r]
                       (let [props (first (:blocks r))]
                         (is (:pre props) "recognised as page properties even though the host did not flag it")
                         (is (str/includes? (:content props) "concepts:: [[Later]]") "earlier links survive")
                         (is (str/includes? (:content props) "type:: [[paper]]")))
                       (is (= [["summary"]] (body-shape r)))
                       (op "page_create" {"name" "Later" "content" "- again" "if_exists" "skip"})))
              (.then (fn [r]
                       (is (false? (:created r)) "once written, skip protects it")))))))))

(deftest add-to-ui-written-tags
  (testing "adding a tag merges into Logseq's plain `a, b` tag text instead of refusing it"
    (let [conn (fake/install!)]
      (fake/seed! conn [{:name "Note" :blocks ["tags:: alpha, beta" "body"]}])
      (async done
        (run-flow done
          (-> (op "properties_set" {"page" "Note" "properties" {"tags" ["Gamma" "alpha"]} "mode" "add"})
              (.then (fn [r]
                       (is (= "[[alpha]], [[beta]], [[Gamma]]" (get-in r [:properties :tags])))))))))))

(deftest filter-by-non-text-property
  (testing "boolean property values (public:: true) filter instead of crashing the listing"
    (let [conn (fake/install!)]
      (fake/seed! conn [{:name "Shared" :blocks ["public:: true" "body"]}
                        {:name "Private" :blocks ["public:: false"]}])
      (async done
        (run-flow done
          (-> (op "page_list" {"property" {"key" "public" "value" "true"}})
              (.then (fn [r] (is (= ["Shared"] (map :name (:pages r))))))))))))

(deftest graph-query-scalar-find
  (testing "a scalar find spec returns the value rather than crashing or splitting it"
    (let [conn (fake/install!)]
      (fake/seed! conn [{:name "P1" :blocks ["x" "y"]}])
      (async done
        (run-flow done
          (-> (op "graph_query" {"query" "[:find (count ?b) . :where [?b :block/content]]"})
              (.then (fn [r] (is (= {:results 2 :total 1 :truncated false} r))))))))))

(deftest list-pages
  (testing "listing pages: journals excluded by default, sorting and paging"
    (let [conn (fake/install!)]
      (fake/seed! conn [{:name "b-note" :blocks ["x"]}
                        {:name "a-note" :blocks ["x"]}
                        {:name "Oct 9th, 2026" :journal-day 20261009 :blocks ["entry"]}])
      (async done
        (run-flow done
          (-> (op "page_list" {"sort" "name"})
              (.then (fn [r]
                       (is (= ["a-note" "b-note"] (map :name (:pages r))))
                       (op "page_list" {"journals" "only"})))
              (.then (fn [r]
                       (is (= [["Oct 9th, 2026" true]] (map (juxt :name :journal) (:pages r))))
                       (op "page_list" {"sort" "name" "limit" 1 "offset" 1})))
              (.then (fn [r]
                       (is (= {:total 2 :offset 1} (select-keys r [:total :offset])))
                       (is (= ["b-note"] (map :name (:pages r))))
                       (op-error "page_list" {"journals" "sometimes"})))
              (.then (fn [msg] (is (str/includes? msg "journals must be one of"))))))))))

(deftest link-pages-and-follow-backlinks
  (testing "page links merge into a property list, show up as backlinks, and can be removed"
    (let [conn (fake/install!)]
      (fake/seed! conn [{:name "Rust" :blocks ["intro"]}
                        {:name "Go" :blocks ["intro go"]}])
      (async done
        (run-flow done
          (-> (op "properties_set" {"page" "rust" "properties" {"related" ["Go"]} "mode" "add"})
              (.then (fn [r]
                       (is (= {:page "Rust" :properties {:related "[[Go]]"}} (select-keys r [:page :properties])))
                       ;; Mixed-case key: Logseq stores keys lower-case and looks them up verbatim.
                       (op "properties_set" {"page" "Rust" "properties" {"Related" ["go" "Zig"]} "mode" "add"})))
              (.then (fn [r]
                       (is (= "[[Go]], [[Zig]]" (get-in r [:properties :related]))
                           "add keeps existing links and skips case-duplicates")
                       (op "page_read" {"name" "Rust"})))
              (.then (fn [r]
                       (let [props-block (first (:blocks r))]
                         (is (:pre props-block)
                             "properties land in the FIRST block, so they are page properties")
                         (is (= 1 (count (filter :pre (:blocks r))))
                             "the second write reuses the properties block instead of stacking another")
                         (is (= [["intro"]] (body-shape r)))
                         (-> (op-error "block_insert" {"uuid" (:uuid props-block) "position" "before" "content" "x"})
                             (.then (fn [msg]
                                      (is (str/includes? msg "before a page's properties block"))
                                      (op-error "properties_set" {"page" "Rust" "properties" {"status" "x\nid:: 123"}})))
                             (.then (fn [msg]
                                      (is (str/includes? msg "single line") "a newline could smuggle in an id:: line")
                                      (op "page_links" {"name" "Go"})))))))
              (.then (fn [r]
                       (is (= ["Rust"] (map :page (:backlinks r))))
                       (is (= 1 (:backlinksTotal r)))
                       (op "page_links" {"name" "Rust" "direction" "out"})))
              (.then (fn [r]
                       (is (= ["Go" "Zig"] (:outgoing r))
                           "the property-key page (related) is not reported as a link")
                       (is (not (contains? r :backlinks)) "direction out omits backlinks")
                       (op "properties_set" {"page" "Rust" "properties" {"related" ["Go" "Zig"]} "mode" "remove"})))
              (.then (fn [r]
                       (is (= {:related nil} (:properties r)) "removing every link removes the property")
                       (op "page_links" {"name" "Go" "direction" "in"})))
              (.then (fn [r]
                       (is (= [0 []] [(:backlinksTotal r) (:backlinks r)]))
                       (op-error "properties_set" {"page" "Rust" "uuid" (fake/uuid-of conn "intro") "properties" {"a" "b"}})))
              (.then (fn [msg] (is (str/includes? msg "exactly one of page or uuid"))))))))))

(deftest targeted-block-edits
  (testing "insert at precise positions, move, update, and delete specific blocks"
    (let [conn (fake/install!)]
      (fake/seed! conn [{:name "Notes" :blocks [["A" "A1\nid:: 6f1" "A2"] "B"]}
                        {:name "Other" :blocks ["elsewhere"]}])
      (let [uid #(fake/uuid-of conn %)
            a1 (uid "A1")                       ; captured before its content is edited
            read-notes #(op "page_read" {"name" "Notes"})]
        (async done
          (run-flow done
            (-> (op "block_insert" {"uuid" (uid "A") "content" "- A3\n  - A3a"})
                (.then (fn [r]
                         (is (= 2 (count (:uuids r))))
                         (op "block_insert" {"uuid" (uid "B") "position" "before" "content" "before B"})))
                (.then (fn [_] (op "block_insert" {"page" "Notes" "position" "start" "content" "- first"})))
                (.then read-notes)
                (.then (fn [r]
                         (is (= [["first"]
                                 ["A" ["A1\nid:: 6f1"] ["A2"] ["A3" ["A3a"]]]
                                 ["before B"]
                                 ["B"]]
                                (body-shape r))
                             "child appends as the last child; before/start land exactly")
                         (op "block_move" {"uuid" (uid "A2") "target_uuid" (uid "B") "position" "child"})))
                (.then (fn [_] (op-error "block_move" {"uuid" (uid "A")
                                                       "target_uuid" (str/upper-case (uid "A3a"))})))
                (.then (fn [msg]
                         (is (str/includes? msg "own children"))
                         (op "block_update" {"uuid" a1 "content" "A1 edited"})))
                (.then (fn [_] (op "block_get" {"uuid" a1})))
                (.then (fn [r]
                         (is (= "A1 edited\nid:: 6f1" (:content r)) "the id:: line survives a content rewrite")
                         (is (= {:page "Notes" :parentUuid (uid "A")} (select-keys r [:page :parentUuid])))
                         (op-error "block_delete" {"uuids" [(uid "A") "00000000-0000-0000-0000-000000000000"]})))
                (.then (fn [msg]
                         (is (str/includes? msg "Nothing was deleted"))
                         (op "block_get" {"uuid" (uid "A")})))
                (.then (fn [r]
                         (is (= "A" (:content r)) "a failed batch deletes nothing")
                         (op "block_delete" {"uuids" [(uid "A") (uid "A3a")]})))
                (.then (fn [r]
                         (is (= [{:page "Notes" :preview "A" :descendants 3}]
                                (map #(select-keys % [:page :preview :descendants]) (:deleted r)))
                             "a listed block inside another listed block goes with its ancestor")
                         (read-notes)))
                (.then (fn [r]
                         (is (= [["first"] ["before B"] ["B" ["A2"]]] (body-shape r)))
                         (op "block_move" {"uuid" (uid "first") "page" "Other"})))
                (.then (fn [_] (op "page_read" {"name" "Other"})))
                (.then (fn [r]
                         (is (= [["elsewhere"] ["first"]] (body-shape r)) "moves across pages")
                         (op-error "block_insert" {"content" "orphan"})))
                (.then (fn [msg]
                         (is (str/includes? msg "exactly one of page or uuid")
                             "validation errors reject instead of escaping the promise"))))))))))

(deftest rename-delete-and-journal
  (testing "rename refuses to merge, delete reports what it removed, journal appends by date"
    (let [conn (fake/install!)]
      (fake/seed! conn [{:name "Draft" :blocks [["one" "two"]]}
                        {:name "Taken" :blocks ["x"]}
                        {:name "Oct 9th, 2026" :journal-day 20261009 :blocks [""]}])
      (async done
        (run-flow done
          (-> (op-error "page_rename" {"name" "Draft" "new_name" "taken"})
              (.then (fn [msg]
                       (is (str/includes? msg "already exists"))
                       (op "page_rename" {"name" "draft" "new_name" "Final"})))
              (.then (fn [r]
                       (is (= {:from "Draft" :to "Final"} r))
                       (op "page_delete" {"name" "Final"})))
              (.then (fn [r]
                       (is (= {:deleted "Final" :blocks 2} r))
                       (op-error "page_read" {"name" "Final"})))
              (.then (fn [msg]
                       (is (str/includes? msg "Page not found"))
                       (op "journal_append" {"date" "2026-10-09" "content" "- logged\n  - detail"})))
              (.then (fn [r]
                       (is (= "Oct 9th, 2026" (:page r)))
                       (op "page_read" {"name" "Oct 9th, 2026"})))
              (.then (fn [r]
                       (is (= [["logged" ["detail"]]] (body-shape r))
                           "Logseq's blank placeholder block is filled, not left dangling")
                       (op-error "journal_append" {"date" "2026-10-10" "content" "x"})))
              (.then (fn [msg]
                       (is (str/includes? msg "No journal page exists"))
                       (op-error "journal_append" {"date" "10/09/2026" "content" "x"})))
              (.then (fn [msg] (is (str/includes? msg "YYYY-MM-DD"))))))))))

(deftest graph-query-truncation
  (testing "graph_query reports truncation instead of silently dropping rows"
    (let [conn (fake/install!)]
      (fake/seed! conn [{:name "P1" :blocks ["x"]} {:name "P2" :blocks ["y"]} {:name "P3" :blocks ["z"]}])
      (async done
        (run-flow done
          (-> (op "graph_query" {"query" "[:find ?n :where [?p :block/original-name ?n]]" "limit" 2})
              (.then (fn [r]
                       (is (= [2 3 true] [(count (:results r)) (:total r) (:truncated r)]))))))))))
