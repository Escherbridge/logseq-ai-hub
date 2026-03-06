(ns logseq-ai-hub.code-repo.templates-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [logseq-ai-hub.code-repo.templates :as templates]))

;;; code-review-skill-template tests (synchronous)

(deftest test-code-review-skill-template-page-name
  (testing "Returns correct page-name format"
    (let [result (templates/code-review-skill-template "my-project")]
      (is (= "Skills/code-review-my-project" (:page-name result)))))

  (testing "Slugifies project name into page name"
    (let [result (templates/code-review-skill-template "alpha")]
      (is (= "Skills/code-review-alpha" (:page-name result))))))

(deftest test-code-review-skill-template-properties
  (testing "Properties include required skill keys and tags"
    (let [props (:properties (templates/code-review-skill-template "my-project"))]
      (is (= "review" (:skill-type props)))
      (is (= "my-project" (:skill-project props)))
      (is (= "logseq-ai-hub-skill" (:tags props))))))

(deftest test-code-review-skill-template-content-project-refs
  (testing "Content contains [[Projects/{project}]] references"
    (let [content (:content (templates/code-review-skill-template "my-project"))]
      (is (str/includes? content "[[Projects/my-project]]"))))

  (testing "Content contains [[ADR/{project}/*]] reference"
    (let [content (:content (templates/code-review-skill-template "my-project"))]
      (is (str/includes? content "[[ADR/my-project/*]]"))))

  (testing "Content has ## Context section"
    (let [content (:content (templates/code-review-skill-template "my-project"))]
      (is (str/includes? content "## Context"))))

  (testing "Content has ## Steps section"
    (let [content (:content (templates/code-review-skill-template "my-project"))]
      (is (str/includes? content "## Steps"))))

  (testing "Content includes architecture alignment check"
    (let [content (:content (templates/code-review-skill-template "my-project"))]
      (is (str/includes? content "Architecture alignment"))))

  (testing "Content includes security check"
    (let [content (:content (templates/code-review-skill-template "my-project"))]
      (is (str/includes? content "Security"))))

  (testing "Content includes test coverage check"
    (let [content (:content (templates/code-review-skill-template "my-project"))]
      (is (str/includes? content "Test coverage"))))

  (testing "Content includes ADR compliance check"
    (let [content (:content (templates/code-review-skill-template "my-project"))]
      (is (str/includes? content "ADR compliance")))))

;;; deployment-procedure-template tests (synchronous)

(deftest test-deployment-procedure-template-page-name
  (testing "Returns correct page-name format"
    (let [result (templates/deployment-procedure-template "my-project" {})]
      (is (= "Procedures/deploy-my-project" (:page-name result)))))

  (testing "Uses project name in page name"
    (let [result (templates/deployment-procedure-template "backend-api" {})]
      (is (= "Procedures/deploy-backend-api" (:page-name result))))))

(deftest test-deployment-procedure-template-properties
  (testing "Properties include required procedure keys and tags"
    (let [props (:properties (templates/deployment-procedure-template "my-project" {}))]
      (is (= "deployment" (:procedure-type props)))
      (is (= "my-project" (:procedure-project props)))
      (is (= "true" (:procedure-requires-approval props)))
      (is (= "logseq-ai-hub-procedure" (:tags props))))))

(deftest test-deployment-procedure-template-content-approval-markers
  (testing "Content includes [APPROVAL: deploy-to-staging] marker"
    (let [content (:content (templates/deployment-procedure-template "my-project" {}))]
      (is (str/includes? content "[APPROVAL: deploy-to-staging]"))))

  (testing "Content includes [APPROVAL: deploy-to-production] marker"
    (let [content (:content (templates/deployment-procedure-template "my-project" {}))]
      (is (str/includes? content "[APPROVAL: deploy-to-production]"))))

  (testing "Content has ## Pre-deploy Checks section"
    (let [content (:content (templates/deployment-procedure-template "my-project" {}))]
      (is (str/includes? content "## Pre-deploy Checks"))))

  (testing "Content has ## Deploy Steps section"
    (let [content (:content (templates/deployment-procedure-template "my-project" {}))]
      (is (str/includes? content "## Deploy Steps"))))

  (testing "Content has ## Post-deploy Verification section"
    (let [content (:content (templates/deployment-procedure-template "my-project" {}))]
      (is (str/includes? content "## Post-deploy Verification"))))

  (testing "Content has ## Rollback Procedure section"
    (let [content (:content (templates/deployment-procedure-template "my-project" {}))]
      (is (str/includes? content "## Rollback Procedure")))))

(deftest test-deployment-procedure-template-approval-contact
  (testing "Includes approval contact in properties when provided"
    (let [props (:properties (templates/deployment-procedure-template "my-project" {:contact "alice@example.com"}))]
      (is (= "alice@example.com" (:procedure-approval-contact props)))))

  (testing "Does not include approval contact key when not provided"
    (let [props (:properties (templates/deployment-procedure-template "my-project" {}))]
      (is (nil? (:procedure-approval-contact props)))))

  (testing "Does not include approval contact key when contact is empty string"
    (let [props (:properties (templates/deployment-procedure-template "my-project" {:contact ""}))]
      (is (nil? (:procedure-approval-contact props))))))
