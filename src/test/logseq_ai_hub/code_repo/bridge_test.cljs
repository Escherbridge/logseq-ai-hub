(ns logseq-ai-hub.code-repo.bridge-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [logseq-ai-hub.code-repo.bridge :as bridge]))

;;; Mock data

(def mock-project-pages
  #js [#js [#js {"block/name" "projects/my-app"
                 "block/original-name" "Projects/My App"}]
       #js [#js {"block/name" "projects/old-site"
                 "block/original-name" "Projects/Old Site"}]])

(def mock-blocks-my-app
  #js [#js {:content "project-name:: My App\nproject-repo:: https://github.com/user/my-app\nproject-local-path:: /home/user/my-app\nproject-branch-main:: main\nproject-tech-stack:: ClojureScript, Bun\nproject-description:: My main application\nproject-status:: active\ntags:: logseq-ai-hub-project\n\nSome notes about the project here.\nMore context."}])

(def mock-blocks-old-site
  #js [#js {:content "project-name:: Old Site\nproject-repo:: https://github.com/user/old-site\nproject-description:: An archived website\nproject-status:: archived\ntags:: logseq-ai-hub-project"}])

(defn setup-mocks! []
  (set! js/logseq #js {})
  (set! (.-DB js/logseq)
    #js {:datascriptQuery
         (fn [_q]
           (js/Promise.resolve mock-project-pages))})
  (set! (.-Editor js/logseq)
    #js {:getPageBlocksTree
         (fn [page-name]
           (cond
             (= page-name "Projects/My App")
             (js/Promise.resolve mock-blocks-my-app)

             (= page-name "Projects/Old Site")
             (js/Promise.resolve mock-blocks-old-site)

             :else
             (js/Promise.resolve #js [])))}))

(use-fixtures :each {:before setup-mocks!})

;;; handle-project-list tests

(deftest test-handle-project-list-all
  (testing "Returns all projects when no filter given"
    (async done
      (-> (bridge/handle-project-list {})
          (.then (fn [result]
                   (is (= 2 (:count result)))
                   (is (= 2 (count (:projects result))))
                   (let [names (set (map :name (:projects result)))]
                     (is (contains? names "My App"))
                     (is (contains? names "Old Site")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Error: " err))
                    (done)))))))

(deftest test-handle-project-list-with-status-filter
  (testing "Filters projects by status"
    (async done
      (-> (bridge/handle-project-list {"status" "active"})
          (.then (fn [result]
                   (is (= 1 (:count result)))
                   (is (= "My App" (:name (first (:projects result)))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Error: " err))
                    (done)))))))

(deftest test-handle-project-list-archived-filter
  (testing "Filters for archived projects"
    (async done
      (-> (bridge/handle-project-list {"status" "archived"})
          (.then (fn [result]
                   (is (= 1 (:count result)))
                   (is (= "Old Site" (:name (first (:projects result)))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Error: " err))
                    (done)))))))

(deftest test-handle-project-list-no-match-filter
  (testing "Returns empty list when no projects match the filter"
    (async done
      (-> (bridge/handle-project-list {"status" "nonexistent-status"})
          (.then (fn [result]
                   (is (= 0 (:count result)))
                   (is (= [] (:projects result)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Error: " err))
                    (done)))))))

(deftest test-handle-project-list-maps-properties
  (testing "Project list entries have expected keys"
    (async done
      (-> (bridge/handle-project-list {"status" "active"})
          (.then (fn [result]
                   (let [p (first (:projects result))]
                     (is (= "My App" (:name p)))
                     (is (= "https://github.com/user/my-app" (:repo p)))
                     (is (= "/home/user/my-app" (:localPath p)))
                     (is (= "main" (:branchMain p)))
                     (is (= "ClojureScript, Bun" (:techStack p)))
                     (is (= "My main application" (:description p)))
                     (is (= "active" (:status p))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Error: " err))
                    (done)))))))

;;; handle-project-get tests

(deftest test-handle-project-get-found
  (testing "Returns full project details for existing project"
    (async done
      (-> (bridge/handle-project-get {"name" "My App"})
          (.then (fn [result]
                   (is (= "My App" (:name result)))
                   (is (= "https://github.com/user/my-app" (:repo result)))
                   (is (= "active" (:status result)))
                   ;; Body should contain non-property text
                   (is (string? (:body result)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Error: " err))
                    (done)))))))

(deftest test-handle-project-get-not-found
  (testing "Rejects with error message for non-existent project"
    (async done
      (-> (bridge/handle-project-get {"name" "Nonexistent Project"})
          (.then (fn [_]
                   (is false "Should have rejected")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"Project not found" (str err)))
                    (is (re-find #"Nonexistent Project" (str err)))
                    (done)))))))

(deftest test-handle-project-get-missing-name
  (testing "Rejects when name param is missing"
    (async done
      (-> (bridge/handle-project-get {})
          (.then (fn [_]
                   (is false "Should have rejected")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"Missing" (str err)))
                    (done)))))))

(deftest test-handle-project-get-body-content
  (testing "Body contains non-property text from the page"
    (async done
      (-> (bridge/handle-project-get {"name" "My App"})
          (.then (fn [result]
                   (is (string? (:body result)))
                   ;; The mock block content has notes after the properties
                   (is (re-find #"notes about the project" (:body result)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Error: " err))
                    (done)))))))
