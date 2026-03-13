(ns logseq-ai-hub.code-repo.adr-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [clojure.string :as str]
            [logseq-ai-hub.code-repo.adr :as adr]))

;;; Mock setup

(defn setup-mocks! []
  (set! js/logseq #js {})
  (set! (.-DB js/logseq) #js {:datascriptQuery (fn [_q] (js/Promise.resolve #js []))})
  (set! (.-Editor js/logseq) #js {:getPageBlocksTree (fn [_] (js/Promise.resolve #js []))
                                   :createPage (fn [name _props _opts] (js/Promise.resolve #js {:name name}))
                                   :appendBlockInPage (fn [_page _content] (js/Promise.resolve #js {:uuid "mock-uuid"}))}))

(use-fixtures :each {:before setup-mocks!})

;;; parse-adr-properties tests (synchronous)

(deftest test-parse-adr-properties-complete
  (testing "Parses all known ADR properties"
    (let [content (str "adr-project:: logseq-ai-hub\n"
                       "adr-title:: Use SSE for Bridge Transport\n"
                       "adr-status:: accepted\n"
                       "adr-date:: 2026-01-15\n"
                       "tags:: logseq-ai-hub-adr")
          props (adr/parse-adr-properties content)]
      (is (= "logseq-ai-hub" (:adr-project props)))
      (is (= "Use SSE for Bridge Transport" (:adr-title props)))
      (is (= "accepted" (:adr-status props)))
      (is (= "2026-01-15" (:adr-date props))))))

(deftest test-parse-adr-properties-partial
  (testing "Parses only known properties, ignores unknown keys"
    (let [content (str "adr-project:: my-project\n"
                       "adr-title:: Partial ADR\n"
                       "some-other-key:: ignored\n"
                       "tags:: logseq-ai-hub-adr")
          props (adr/parse-adr-properties content)]
      (is (= "my-project" (:adr-project props)))
      (is (= "Partial ADR" (:adr-title props)))
      (is (nil? (:adr-status props)))
      (is (nil? (:adr-date props)))
      (is (nil? (get props :some-other-key))))))

(deftest test-parse-adr-properties-empty
  (testing "Returns empty map for empty content"
    (let [props (adr/parse-adr-properties "")]
      (is (= {} props))))

  (testing "Returns empty map for content with no matching properties"
    (let [props (adr/parse-adr-properties "Just free text\nNo properties here")]
      (is (= {} props)))))

(deftest test-parse-adr-properties-trims-values
  (testing "Trims whitespace from values"
    (let [content "adr-title::   Spaced Title   "
          props (adr/parse-adr-properties content)]
      (is (= "Spaced Title" (:adr-title props))))))

;;; extract-adr-sections tests (synchronous)

(defn adr-content-with-sections []
  (str "adr-project:: my-project\n"
       "adr-title:: Test ADR\n"
       "adr-status:: proposed\n"
       "\n"
       "## Context\n"
       "We need to decide on a transport mechanism for real-time updates.\n"
       "\n"
       "## Decision\n"
       "We will use Server-Sent Events (SSE) for the bridge transport.\n"
       "\n"
       "## Consequences\n"
       "Positive: lightweight, native browser support.\n"
       "Negative: unidirectional only."))

(deftest test-extract-adr-sections-all-three
  (testing "Extracts all three standard sections"
    (let [content (adr-content-with-sections)
          sections (adr/extract-adr-sections content)]
      (is (str/includes? (:context sections) "transport mechanism"))
      (is (str/includes? (:decision sections) "Server-Sent Events"))
      (is (str/includes? (:consequences sections) "unidirectional")))))

(deftest test-extract-adr-sections-missing-section
  (testing "Returns empty string for missing section"
    (let [content (str "## Context\n"
                       "Some context text.\n"
                       "\n"
                       "## Decision\n"
                       "The decision text.")
          sections (adr/extract-adr-sections content)]
      (is (str/includes? (:context sections) "Some context text"))
      (is (str/includes? (:decision sections) "The decision text"))
      (is (= "" (:consequences sections))))))

(deftest test-extract-adr-sections-empty-content
  (testing "Returns empty strings for empty content"
    (let [sections (adr/extract-adr-sections "")]
      (is (= "" (:context sections)))
      (is (= "" (:decision sections)))
      (is (= "" (:consequences sections))))))

(deftest test-extract-adr-sections-no-headings
  (testing "Returns empty strings when no ## headings present"
    (let [sections (adr/extract-adr-sections "Just some properties\nadr-title:: My ADR")]
      (is (= "" (:context sections)))
      (is (= "" (:decision sections)))
      (is (= "" (:consequences sections))))))

;;; parse-adr-page tests (synchronous)

(deftest test-parse-adr-page-valid
  (testing "Returns valid entry when adr-project and adr-title are present"
    (let [content (str "adr-project:: logseq-ai-hub\n"
                       "adr-title:: Use SSE Bridge\n"
                       "adr-status:: accepted\n"
                       "adr-date:: 2026-01-15\n"
                       "tags:: logseq-ai-hub-adr\n"
                       "\n"
                       "## Context\nWe need real-time updates.\n"
                       "\n"
                       "## Decision\nSSE bridge.\n"
                       "\n"
                       "## Consequences\nLightweight.")
          result (adr/parse-adr-page "ADR/logseq-ai-hub/ADR-001-Use-SSE-Bridge" content)]
      (is (true? (:valid result)))
      (is (= "ADR/logseq-ai-hub/ADR-001-Use-SSE-Bridge" (get-in result [:entry :id])))
      (is (= :adr (get-in result [:entry :type])))
      (is (= "Use SSE Bridge" (get-in result [:entry :name])))
      (is (= "logseq-ai-hub" (get-in result [:entry :project])))
      (is (= "accepted" (get-in result [:entry :status])))
      (is (= "2026-01-15" (get-in result [:entry :date])))
      (is (= "" (get-in result [:entry :description])))
      (is (= :graph-page (get-in result [:entry :source])))
      (is (map? (get-in result [:entry :sections]))))))

(deftest test-parse-adr-page-valid-minimal
  (testing "Valid with only required fields - optional fields default to empty"
    (let [content (str "adr-project:: my-project\n"
                       "adr-title:: Minimal ADR\n"
                       "tags:: logseq-ai-hub-adr")
          result (adr/parse-adr-page "ADR/my-project/ADR-001-Minimal-ADR" content)]
      (is (true? (:valid result)))
      (is (= "" (get-in result [:entry :status])))
      (is (= "" (get-in result [:entry :date]))))))

(deftest test-parse-adr-page-invalid-missing-project
  (testing "Returns invalid when adr-project is missing"
    (let [content (str "adr-title:: No Project ADR\n"
                       "adr-status:: proposed\n"
                       "tags:: logseq-ai-hub-adr")
          result (adr/parse-adr-page "ADR/unknown/ADR-001" content)]
      (is (false? (:valid result)))
      (is (vector? (:errors result)))
      (is (some #(= (:field %) :adr-project) (:errors result))))))

(deftest test-parse-adr-page-invalid-missing-title
  (testing "Returns invalid when adr-title is missing"
    (let [content (str "adr-project:: my-project\n"
                       "adr-status:: proposed\n"
                       "tags:: logseq-ai-hub-adr")
          result (adr/parse-adr-page "ADR/my-project/ADR-001" content)]
      (is (false? (:valid result)))
      (is (vector? (:errors result)))
      (is (some #(= (:field %) :adr-title) (:errors result))))))

(deftest test-parse-adr-page-invalid-empty-content
  (testing "Returns invalid for empty content"
    (let [result (adr/parse-adr-page "ADR/empty/ADR-001" "")]
      (is (false? (:valid result))))))

;;; handle-adr-list tests (async, uses Logseq API mocks)

(deftest test-handle-adr-list-returns-filtered-results
  (async done
    ;; Mock DB query returns two ADR pages
    (set! (.-datascriptQuery (.-DB js/logseq))
          (fn [_q]
            (js/Promise.resolve
              (clj->js [[{"block/name" "adr/logseq-ai-hub/adr-001-use-sse"
                           "block/original-name" "ADR/logseq-ai-hub/ADR-001-Use-SSE"}]
                         [{"block/name" "adr/other-project/adr-001-other"
                           "block/original-name" "ADR/other-project/ADR-001-Other"}]]))))
    ;; Mock getPageBlocksTree to return appropriate content per page
    (set! (.-getPageBlocksTree (.-Editor js/logseq))
          (fn [page-name]
            (cond
              (str/includes? page-name "logseq-ai-hub")
              (js/Promise.resolve
                (clj->js [{:content (str "adr-project:: logseq-ai-hub\n"
                                         "adr-title:: Use SSE\n"
                                         "adr-status:: accepted\n"
                                         "adr-date:: 2026-01-15\n"
                                         "## Context\nContext text.\n"
                                         "## Decision\nDecision text.\n"
                                         "## Consequences\nConsequences text.")}]))
              (str/includes? page-name "other-project")
              (js/Promise.resolve
                (clj->js [{:content (str "adr-project:: other-project\n"
                                         "adr-title:: Other ADR\n"
                                         "adr-status:: proposed\n"
                                         "## Context\nOther context.\n"
                                         "## Decision\nOther decision.\n"
                                         "## Consequences\nOther consequences.")}]))
              :else
              (js/Promise.resolve #js []))))
    (-> (adr/handle-adr-list {"project" "logseq-ai-hub"})
        (.then (fn [result]
                 (is (= 1 (:count result)))
                 (is (= 1 (count (:adrs result))))
                 (let [first-adr (first (:adrs result))]
                   (is (= "Use SSE" (:title first-adr)))
                   (is (= "logseq-ai-hub" (:project first-adr)))
                   (is (= "accepted" (:status first-adr)))
                   (is (map? (:sections first-adr))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-handle-adr-list-no-filter-returns-all
  (async done
    (set! (.-datascriptQuery (.-DB js/logseq))
          (fn [_q]
            (js/Promise.resolve
              (clj->js [[{"block/name" "adr/proj-a/adr-001"
                           "block/original-name" "ADR/proj-a/ADR-001"}]
                         [{"block/name" "adr/proj-b/adr-001"
                           "block/original-name" "ADR/proj-b/ADR-001"}]]))))
    (set! (.-getPageBlocksTree (.-Editor js/logseq))
          (fn [page-name]
            (let [proj (if (str/includes? page-name "proj-a") "proj-a" "proj-b")]
              (js/Promise.resolve
                (clj->js [{:content (str "adr-project:: " proj "\n"
                                         "adr-title:: ADR for " proj "\n"
                                         "adr-status:: proposed")}])))))
    (-> (adr/handle-adr-list {})
        (.then (fn [result]
                 (is (= 2 (:count result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

;;; handle-adr-create tests (async, uses Logseq API mocks)

(deftest test-handle-adr-create-correct-page-name-format
  (async done
    ;; No existing ADRs for this project
    (set! (.-datascriptQuery (.-DB js/logseq))
          (fn [_q] (js/Promise.resolve #js [])))
    (let [created-pages (atom [])
          appended-blocks (atom [])]
      (set! (.-createPage (.-Editor js/logseq))
            (fn [name _props _opts]
              (swap! created-pages conj name)
              (js/Promise.resolve #js {:name name})))
      (set! (.-appendBlockInPage (.-Editor js/logseq))
            (fn [page content]
              (swap! appended-blocks conj {:page page :content content})
              (js/Promise.resolve #js {:uuid "mock-uuid"})))
      (-> (adr/handle-adr-create {"project" "logseq-ai-hub"
                                   "title" "Use SSE Bridge"
                                   "context" "We need real-time."
                                   "decision" "Use SSE."
                                   "consequences" "Lightweight."
                                   "status" "accepted"})
          (.then (fn [result]
                   (is (= "ADR/logseq-ai-hub/ADR-001-use-sse-bridge" (:page result)))
                   (is (= 1 (:adrNumber result)))
                   (is (= "Use SSE Bridge" (:title result)))
                   ;; Page was created once
                   (is (= 1 (count @created-pages)))
                   (is (= "ADR/logseq-ai-hub/ADR-001-use-sse-bridge" (first @created-pages)))
                   ;; Three sections appended
                   (is (= 3 (count @appended-blocks)))
                   (is (str/includes? (:content (nth @appended-blocks 0)) "## Context"))
                   (is (str/includes? (:content (nth @appended-blocks 1)) "## Decision"))
                   (is (str/includes? (:content (nth @appended-blocks 2)) "## Consequences"))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-handle-adr-create-auto-increments-number
  (async done
    ;; Existing ADR-001 for this project
    (set! (.-datascriptQuery (.-DB js/logseq))
          (fn [_q]
            (js/Promise.resolve
              (clj->js [[{"block/name" "adr/my-project/adr-001-first"
                           "block/original-name" "ADR/my-project/ADR-001-First"}]]))))
    (set! (.-getPageBlocksTree (.-Editor js/logseq))
          (fn [_page-name]
            (js/Promise.resolve
              (clj->js [{:content (str "adr-project:: my-project\n"
                                       "adr-title:: First ADR\n"
                                       "adr-status:: accepted")}]))))
    (set! (.-createPage (.-Editor js/logseq))
          (fn [name _props _opts]
            (js/Promise.resolve #js {:name name})))
    (set! (.-appendBlockInPage (.-Editor js/logseq))
          (fn [_page _content]
            (js/Promise.resolve #js {:uuid "mock-uuid"})))
    (-> (adr/handle-adr-create {"project" "my-project"
                                 "title" "Second Decision"
                                 "context" "More context."
                                 "decision" "Another decision."
                                 "consequences" "More consequences."
                                 "status" "proposed"})
        (.then (fn [result]
                 (is (= 2 (:adrNumber result)))
                 (is (str/includes? (:page result) "ADR-002"))
                 (is (str/includes? (:page result) "second-decision"))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))
