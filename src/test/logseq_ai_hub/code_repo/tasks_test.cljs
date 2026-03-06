(ns logseq-ai-hub.code-repo.tasks-test
  (:require [cljs.test :refer-macros [deftest testing is async use-fixtures]]
            [clojure.string :as str]
            [logseq-ai-hub.code-repo.tasks :as tasks]))

;;; Mock setup

(defn setup-mocks! []
  (set! js/logseq #js {})
  (set! (.-DB js/logseq) #js {:datascriptQuery (fn [_q] (js/Promise.resolve #js []))})
  (set! (.-Editor js/logseq) #js {:createPage         (fn [name _props _opts]
                                                          (js/Promise.resolve #js {:name name}))
                                   :appendBlockInPage   (fn [_name _content]
                                                          (js/Promise.resolve #js {:uuid "mock-uuid"}))
                                   :getPage             (fn [name]
                                                          (js/Promise.resolve #js {:name name :properties #js {}}))
                                   :getPageBlocksTree   (fn [_name]
                                                          (js/Promise.resolve #js []))
                                   :upsertBlockProperty (fn [_uuid _k _v]
                                                          (js/Promise.resolve nil))
                                   :updateBlock         (fn [_uuid _content]
                                                          (js/Promise.resolve nil))}))

(use-fixtures :each {:before setup-mocks!})

;;; ---------------------------------------------------------------------------
;;; handle-track-create tests
;;; ---------------------------------------------------------------------------

(deftest handle-track-create-rejects-missing-project
  (testing "Rejects when project parameter is blank"
    (async done
      (-> (tasks/handle-track-create {"project" "" "trackId" "feature-auth"})
          (.then (fn [_]
                   (is false "Should have rejected")
                   (done)))
          (.catch (fn [err]
                    (is (str/includes? (.-message err) "project"))
                    (done)))))))

(deftest handle-track-create-rejects-missing-trackId
  (testing "Rejects when trackId parameter is blank"
    (async done
      (-> (tasks/handle-track-create {"project" "myproj" "trackId" ""})
          (.then (fn [_]
                   (is false "Should have rejected")
                   (done)))
          (.catch (fn [err]
                    (is (str/includes? (.-message err) "trackId"))
                    (done)))))))

(deftest handle-track-create-builds-correct-page-name
  (testing "Creates page with correct page name"
    (async done
      (let [original    js/logseq.Editor
            created     (atom nil)]
        (set! js/logseq.Editor
          #js {:createPage       (fn [name _props _opts]
                                   (reset! created name)
                                   (js/Promise.resolve #js {:name name}))
               :appendBlockInPage (fn [_name _content]
                                    (js/Promise.resolve #js {:uuid "test"}))})
        (-> (tasks/handle-track-create {"project"     "myproj"
                                         "trackId"     "feature-auth"
                                         "description" "Add auth"})
            (.then (fn [result]
                     (is (= "Projects/myproj/tracks/feature-auth" @created))
                     (is (= true (:created result)))
                     (set! js/logseq.Editor original)
                     (done)))
            (.catch (fn [err]
                      (set! js/logseq.Editor original)
                      (is false (str "Unexpected error: " err))
                      (done))))))))

;;; ---------------------------------------------------------------------------
;;; handle-task-add tests
;;; ---------------------------------------------------------------------------

(deftest handle-task-add-rejects-missing-description
  (testing "Rejects when description parameter is blank"
    (async done
      (-> (tasks/handle-task-add {"project" "myproj" "trackId" "feat-x" "description" ""})
          (.then (fn [_]
                   (is false "Should have rejected")
                   (done)))
          (.catch (fn [err]
                    (is (str/includes? (.-message err) "description"))
                    (done)))))))

(deftest handle-task-add-appends-todo-block
  (testing "Appends a block starting with TODO"
    (async done
      (let [original  js/logseq.Editor
            appended  (atom nil)]
        (set! js/logseq.Editor
          #js {:appendBlockInPage (fn [_page content]
                                    (reset! appended content)
                                    (js/Promise.resolve #js {:uuid "test"}))})
        (-> (tasks/handle-task-add {"project"     "myproj"
                                     "trackId"     "feat-x"
                                     "description" "Implement login"})
            (.then (fn [result]
                     (is (str/starts-with? @appended "TODO "))
                     (is (str/includes? @appended "Implement login"))
                     (is (= true (:added result)))
                     (set! js/logseq.Editor original)
                     (done)))
            (.catch (fn [err]
                      (set! js/logseq.Editor original)
                      (is false (str "Unexpected error: " err))
                      (done))))))))

;;; ---------------------------------------------------------------------------
;;; handle-task-list tests
;;; ---------------------------------------------------------------------------

(deftest handle-task-list-rejects-missing-project
  (testing "Rejects when project parameter is blank"
    (async done
      (-> (tasks/handle-task-list {"project" "" "trackId" "feat-x"})
          (.then (fn [_]
                   (is false "Should have rejected")
                   (done)))
          (.catch (fn [err]
                    (is (str/includes? (.-message err) "project"))
                    (done)))))))

;;; ---------------------------------------------------------------------------
;;; handle-track-list tests
;;; ---------------------------------------------------------------------------

(deftest handle-track-list-rejects-missing-project
  (testing "Rejects when project parameter is blank"
    (async done
      (-> (tasks/handle-track-list {"project" ""})
          (.then (fn [_]
                   (is false "Should have rejected")
                   (done)))
          (.catch (fn [err]
                    (is (str/includes? (.-message err) "project"))
                    (done)))))))

;;; ---------------------------------------------------------------------------
;;; handle-project-dashboard tests
;;; ---------------------------------------------------------------------------

(deftest handle-project-dashboard-rejects-missing-project
  (testing "Rejects when project parameter is blank"
    (async done
      (-> (tasks/handle-project-dashboard {"project" ""})
          (.then (fn [_]
                   (is false "Should have rejected")
                   (done)))
          (.catch (fn [err]
                    (is (str/includes? (.-message err) "project"))
                    (done)))))))
