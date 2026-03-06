(ns logseq-ai-hub.code-repo.lessons-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [logseq-ai-hub.code-repo.lessons :as lessons]))

;; ---------------------------------------------------------------------------
;; Mock state
;; ---------------------------------------------------------------------------

(def created-pages (atom []))
(def appended-blocks (atom []))

(defn setup-mocks! []
  (reset! created-pages [])
  (reset! appended-blocks [])
  (set! js/logseq #js {})
  (set! js/logseq.DB #js {:datascriptQuery (fn [_q] (js/Promise.resolve #js []))})
  (set! js/logseq.Editor
    #js {:createPage (fn [name props opts]
                       (swap! created-pages conj {:name name :props (js->clj props)})
                       (js/Promise.resolve #js {:name name}))
         :appendBlockInPage (fn [page content]
                              (swap! appended-blocks conj {:page page :content content})
                              (js/Promise.resolve #js {:uuid "mock-uuid"}))
         :getPageBlocksTree (fn [_] (js/Promise.resolve #js []))}))

(use-fixtures :each {:before setup-mocks!})

;; ---------------------------------------------------------------------------
;; Pure helper tests (synchronous)
;; ---------------------------------------------------------------------------

(deftest test-slugify
  (testing "basic slugification"
    (is (= "fix-null-pointer" (lessons/slugify "Fix Null Pointer")))
    (is (= "handle-edge-case" (lessons/slugify "Handle Edge Case!!!")))
    (is (= "my-lesson" (lessons/slugify "  my lesson  ")))
    (is (= "abc-123" (lessons/slugify "ABC 123")))
    (is (= "no-special-chars" (lessons/slugify "No @Special# Chars!"))))

  (testing "trims leading/trailing hyphens"
    (is (= "hello" (lessons/slugify "---hello---")))
    (is (= "a-b" (lessons/slugify "...a...b...")))))

(deftest test-lesson-page-name
  (testing "builds correct path"
    (is (= "AI-Memory/lessons/my-project/bug-fix/fix-null-pointer"
           (lessons/lesson-page-name "my-project" "bug-fix" "Fix Null Pointer"))))

  (testing "handles spaces in project/category"
    (is (= "AI-Memory/lessons/my-project/architecture/use-hexagonal-design"
           (lessons/lesson-page-name "my-project" "architecture" "Use Hexagonal Design")))))

(deftest test-escape-datalog-string
  (testing "escapes double quotes"
    (is (= "he said \\\"hello\\\"" (lessons/escape-datalog-string "he said \"hello\""))))
  (testing "leaves normal strings untouched"
    (is (= "ai-memory/lessons/" (lessons/escape-datalog-string "ai-memory/lessons/")))))

;; ---------------------------------------------------------------------------
;; Async tests — handle-lesson-store
;; ---------------------------------------------------------------------------

(deftest test-store-creates-page-with-correct-path
  (async done
    (-> (lessons/handle-lesson-store
          {"project"  "my-project"
           "category" "bug-fix"
           "title"    "Fix Null Pointer"
           "content"  "Always check for nil before dereferencing."})
        (.then (fn [result]
                 (testing "result has :stored true"
                   (is (true? (:stored result))))
                 (testing "result page is correct path"
                   (is (= "AI-Memory/lessons/my-project/bug-fix/fix-null-pointer"
                          (:page result))))
                 (testing "createPage was called once"
                   (is (= 1 (count @created-pages))))
                 (testing "createPage called with correct page name"
                   (is (= "AI-Memory/lessons/my-project/bug-fix/fix-null-pointer"
                          (:name (first @created-pages)))))
                 (testing "result preserves project/category/title"
                   (is (= "my-project"  (:project result)))
                   (is (= "bug-fix"     (:category result)))
                   (is (= "Fix Null Pointer" (:title result))))))
        (.catch (fn [err]
                  (is (nil? err) (str "Should not reject: " (.-message err)))))
        (.finally done))))

(deftest test-store-appends-content-as-block
  (async done
    (-> (lessons/handle-lesson-store
          {"project"  "proj"
           "category" "architecture"
           "title"    "Use Adapters"
           "content"  "Wrap external dependencies in adapter interfaces."})
        (.then (fn [_]
                 (testing "appendBlockInPage was called once"
                   (is (= 1 (count @appended-blocks))))
                 (testing "appended to correct page"
                   (is (= "AI-Memory/lessons/proj/architecture/use-adapters"
                          (:page (first @appended-blocks)))))
                 (testing "appended correct content"
                   (is (= "Wrap external dependencies in adapter interfaces."
                          (:content (first @appended-blocks)))))))
        (.catch (fn [err]
                  (is (nil? err) (str "Should not reject: " (.-message err)))))
        (.finally done))))

(deftest test-store-slugifies-title
  (async done
    (-> (lessons/handle-lesson-store
          {"project"  "p"
           "category" "testing"
           "title"    "Handle Edge Case!!! With Spaces"
           "content"  "Some content."})
        (.then (fn [result]
                 (testing "title is slugified in page name"
                   (is (= "AI-Memory/lessons/p/testing/handle-edge-case-with-spaces"
                          (:page result))))))
        (.catch (fn [err]
                  (is (nil? err) (str "Should not reject: " (.-message err)))))
        (.finally done))))

(deftest test-store-rejects-on-blank-project
  (async done
    (-> (lessons/handle-lesson-store
          {"project"  ""
           "category" "bug-fix"
           "title"    "Some Title"
           "content"  "Content."})
        (.then (fn [_]
                 (is false "Should have rejected")))
        (.catch (fn [err]
                  (is (string? (.-message err)))
                  (is (re-find #"project" (.-message err)))))
        (.finally done))))

(deftest test-store-rejects-on-blank-category
  (async done
    (-> (lessons/handle-lesson-store
          {"project"  "proj"
           "category" "   "
           "title"    "Some Title"
           "content"  "Content."})
        (.then (fn [_]
                 (is false "Should have rejected")))
        (.catch (fn [err]
                  (is (re-find #"category" (.-message err)))))
        (.finally done))))

(deftest test-store-rejects-on-blank-title
  (async done
    (-> (lessons/handle-lesson-store
          {"project"  "proj"
           "category" "bug-fix"
           "title"    ""
           "content"  "Content."})
        (.then (fn [_]
                 (is false "Should have rejected")))
        (.catch (fn [err]
                  (is (re-find #"title" (.-message err)))))
        (.finally done))))

(deftest test-store-rejects-on-blank-content
  (async done
    (-> (lessons/handle-lesson-store
          {"project"  "proj"
           "category" "bug-fix"
           "title"    "Some Title"
           "content"  "   "})
        (.then (fn [_]
                 (is false "Should have rejected")))
        (.catch (fn [err]
                  (is (re-find #"content" (.-message err)))))
        (.finally done))))

;; ---------------------------------------------------------------------------
;; Async tests — handle-lesson-search
;; ---------------------------------------------------------------------------

(deftest test-search-rejects-on-missing-query
  (async done
    (-> (lessons/handle-lesson-search {"query" ""})
        (.then (fn [_]
                 (is false "Should have rejected")))
        (.catch (fn [err]
                  (is (re-find #"query" (.-message err)))))
        (.finally done))))

(deftest test-search-returns-empty-results-when-no-lessons
  (async done
    ;; DB mock returns empty array (set up in setup-mocks!)
    (-> (lessons/handle-lesson-search {"query" "null pointer"})
        (.then (fn [result]
                 (testing "results is empty vector"
                   (is (= [] (:results result))))
                 (testing "count is 0"
                   (is (= 0 (:count result))))
                 (testing "query is echoed back"
                   (is (= "null pointer" (:query result))))))
        (.catch (fn [err]
                  (is (nil? err) (str "Should not reject: " (.-message err)))))
        (.finally done))))
