(ns logseq-ai-hub.code-repo.project-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [logseq-ai-hub.code-repo.project :as project]))

;;; parse-project-properties tests (synchronous)

(deftest test-parse-project-properties-complete
  (testing "Parses all known project properties"
    (let [content (str "project-name:: My Awesome App\n"
                       "project-repo:: https://github.com/user/repo\n"
                       "project-local-path:: /home/user/projects/myapp\n"
                       "project-branch-main:: main\n"
                       "project-tech-stack:: ClojureScript, Bun, PostgreSQL\n"
                       "project-description:: A cool application\n"
                       "project-status:: active\n"
                       "tags:: logseq-ai-hub-project")
          props (project/parse-project-properties content)]
      (is (= "My Awesome App" (:project-name props)))
      (is (= "https://github.com/user/repo" (:project-repo props)))
      (is (= "/home/user/projects/myapp" (:project-local-path props)))
      (is (= "main" (:project-branch-main props)))
      (is (= "ClojureScript, Bun, PostgreSQL" (:project-tech-stack props)))
      (is (= "A cool application" (:project-description props)))
      (is (= "active" (:project-status props))))))

(deftest test-parse-project-properties-partial
  (testing "Parses only known properties, ignores unknown keys"
    (let [content (str "project-name:: Partial Project\n"
                       "project-status:: draft\n"
                       "some-other-key:: ignored value\n"
                       "tags:: logseq-ai-hub-project")
          props (project/parse-project-properties content)]
      (is (= "Partial Project" (:project-name props)))
      (is (= "draft" (:project-status props)))
      (is (nil? (:project-repo props)))
      (is (nil? (:project-local-path props)))
      (is (nil? (get props :some-other-key))))))

(deftest test-parse-project-properties-empty
  (testing "Returns empty map for empty content"
    (let [props (project/parse-project-properties "")]
      (is (= {} props))))

  (testing "Returns empty map for content with no matching properties"
    (let [props (project/parse-project-properties "Just some free text\nNo properties here")]
      (is (= {} props)))))

(deftest test-parse-project-properties-trims-values
  (testing "Trims whitespace from values"
    (let [content "project-name::   Spaced Out Name   "
          props (project/parse-project-properties content)]
      (is (= "Spaced Out Name" (:project-name props))))))

;;; parse-project-page tests (synchronous)

(deftest test-parse-project-page-valid
  (testing "Returns valid entry when project-name is present"
    (let [content (str "project-name:: Test Project\n"
                       "project-repo:: https://github.com/test/proj\n"
                       "project-description:: A test project\n"
                       "project-status:: active\n"
                       "tags:: logseq-ai-hub-project")
          result (project/parse-project-page "Projects/test-project" content)]
      (is (true? (:valid result)))
      (is (= "Projects/test-project" (get-in result [:entry :id])))
      (is (= :project (get-in result [:entry :type])))
      (is (= "Test Project" (get-in result [:entry :name])))
      (is (= "A test project" (get-in result [:entry :description])))
      (is (= :graph-page (get-in result [:entry :source])))
      (is (= "active" (get-in result [:entry :properties :project-status]))))))

(deftest test-parse-project-page-valid-no-description
  (testing "Valid with no description - defaults to empty string"
    (let [content "project-name:: No Description Project\ntags:: logseq-ai-hub-project"
          result (project/parse-project-page "Projects/no-desc" content)]
      (is (true? (:valid result)))
      (is (= "" (get-in result [:entry :description]))))))

(deftest test-parse-project-page-invalid-missing-name
  (testing "Returns invalid when project-name is missing"
    (let [content (str "project-repo:: https://github.com/test/proj\n"
                       "project-status:: active\n"
                       "tags:: logseq-ai-hub-project")
          result (project/parse-project-page "Projects/no-name" content)]
      (is (false? (:valid result)))
      (is (vector? (:errors result)))
      (is (pos? (count (:errors result))))
      (is (some #(= (:field %) :project-name) (:errors result))))))

(deftest test-parse-project-page-invalid-empty-content
  (testing "Returns invalid for empty content"
    (let [result (project/parse-project-page "Projects/empty" "")]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :project-name) (:errors result))))))
