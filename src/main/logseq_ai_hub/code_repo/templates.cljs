(ns logseq-ai-hub.code-repo.templates
  "Template generators for code repository page types.
   Creates skill and procedure pages with proper tags and properties.")

;;; ── Code Review Skill ──────────────────────────────────────────────────────

(defn code-review-skill-template
  "Returns a template map for a code-review skill page tied to PROJECT-NAME.
   The map has :page-name, :properties, and :content keys."
  [project-name]
  (let [page-name (str "Skills/code-review-" project-name)
        content   (str "## Context\n"
                       "This skill is scoped to [[Projects/" project-name "]].\n"
                       "Consult the project page for current architecture decisions "
                       "and active ADRs under [[ADR/" project-name "/*]].\n\n"
                       "## Steps\n"
                       "- [ ] Architecture alignment — does the change fit the patterns in [[Projects/" project-name "]]?\n"
                       "- [ ] Security — check for injection, auth, secrets exposure, and input validation\n"
                       "- [ ] Test coverage — unit, integration, and edge-case coverage adequate?\n"
                       "- [ ] ADR compliance — does the change violate any decision in [[ADR/" project-name "/*]]?\n"
                       "- [ ] Code style and readability — naming, comments, dead code\n"
                       "- [ ] Performance — N+1 queries, unnecessary allocations, blocking calls\n")]
    {:page-name  page-name
     :properties {:skill-type    "review"
                  :skill-project project-name
                  :tags          "logseq-ai-hub-skill"}
     :content    content}))

(defn create-code-review-skill!
  "Async. Creates the code-review skill page for PROJECT-NAME in the Logseq graph.
   Returns a Promise that resolves once the page and its blocks are created."
  [project-name]
  (let [{:keys [page-name properties content]} (code-review-skill-template project-name)
        js-props (clj->js properties)]
    (-> (js/logseq.Editor.createPage page-name js-props #js {:redirect false})
        (.then (fn [_page]
                 ;; Append content blocks after creating the page
                 (let [lines (remove empty? (clojure.string/split content #"\n"))]
                   (reduce (fn [chain line]
                             (.then chain (fn [_] (js/logseq.Editor.appendBlockInPage page-name line))))
                           (js/Promise.resolve nil)
                           lines))))
        (.then (fn [_]
                 (js/console.log "Code-repo: created skill page" page-name)
                 {:status "created" :page-name page-name}))
        (.catch (fn [err]
                  (js/console.warn "Code-repo: failed to create skill page" page-name err)
                  {:status "error" :error (str err)})))))

;;; ── Deployment Procedure ───────────────────────────────────────────────────

(defn deployment-procedure-template
  "Returns a template map for a deployment procedure page tied to PROJECT-NAME.
   OPTIONS may contain :contact (string) for the approval contact.
   The map has :page-name, :properties, and :content keys."
  [project-name options]
  (let [contact   (get options :contact "")
        page-name (str "Procedures/deploy-" project-name)
        content   (str "## Pre-deploy Checks\n"
                       "- [ ] All CI checks passing on the release branch\n"
                       "- [ ] Changelog / release notes updated\n"
                       "- [ ] Database migration scripts reviewed and backed up\n"
                       "- [ ] Feature flags configured for the environment\n"
                       "- [ ] Rollback artefacts (previous build) available\n\n"
                       "## Deploy Steps\n"
                       "- [ ] Notify stakeholders of deployment window\n"
                       "- [ ] [APPROVAL: deploy-to-staging] Deploy to staging environment\n"
                       "- [ ] Run smoke-tests against staging\n"
                       "- [ ] [APPROVAL: deploy-to-production] Deploy to production environment\n"
                       "- [ ] Monitor error rates and latency for 15 minutes post-deploy\n\n"
                       "## Post-deploy Verification\n"
                       "- [ ] Health-check endpoints return 200\n"
                       "- [ ] Key user journeys verified in production\n"
                       "- [ ] Metrics dashboards show expected baseline\n"
                       "- [ ] Alerts configured and firing correctly\n\n"
                       "## Rollback Procedure\n"
                       "- [ ] Identify rollback trigger criteria\n"
                       "- [ ] Re-deploy previous artefact via CI/CD pipeline\n"
                       "- [ ] Reverse database migrations if applicable\n"
                       "- [ ] Notify stakeholders of rollback and root-cause timeline\n")]
    {:page-name  page-name
     :properties (cond-> {:procedure-type              "deployment"
                          :procedure-project           project-name
                          :procedure-requires-approval "true"
                          :tags                        "logseq-ai-hub-procedure"}
                   (not (empty? contact))
                   (assoc :procedure-approval-contact contact))
     :content    content}))

(defn create-deployment-procedure!
  "Async. Creates the deployment procedure page for PROJECT-NAME in the Logseq graph.
   OPTIONS is a map; supports :contact key.
   Returns a Promise that resolves once the page and its blocks are created."
  [project-name options]
  (let [{:keys [page-name properties content]} (deployment-procedure-template project-name options)
        js-props (clj->js properties)]
    (-> (js/logseq.Editor.createPage page-name js-props #js {:redirect false})
        (.then (fn [_page]
                 (let [lines (remove empty? (clojure.string/split content #"\n"))]
                   (reduce (fn [chain line]
                             (.then chain (fn [_] (js/logseq.Editor.appendBlockInPage page-name line))))
                           (js/Promise.resolve nil)
                           lines))))
        (.then (fn [_]
                 (js/console.log "Code-repo: created procedure page" page-name)
                 {:status "created" :page-name page-name}))
        (.catch (fn [err]
                  (js/console.warn "Code-repo: failed to create procedure page" page-name err)
                  {:status "error" :error (str err)})))))
