// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.rest;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.PasswordParameterDefinition;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;
import hudson.model.UnprotectedRootAction;
import io.jenkins.plugins.interactiveinput.bridge.InputStepBridge;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.model.Answer;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import io.jenkins.plugins.interactiveinput.ui.InteractiveViewRunAction;
import io.jenkins.plugins.interactiveinput.util.MarkdownRenderer;
import io.jenkins.plugins.interactiveinput.view.ReviewComment;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ReviewStatus;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import jenkins.model.Jenkins;
import jenkins.security.stapler.StaplerAccessibleType;
import net.sf.json.JSONArray;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.GET;

/**
 * Versioned JSON REST API rooted at {@code /interactive-input/api/v1/} (§6.3, §8.6).
 *
 * <p>This is an {@link UnprotectedRootAction} so the anonymous {@code /health} liveness probe is
 * reachable; every other endpoint enforces permissions explicitly. Mutating endpoints are
 * {@code @RequirePOST} and therefore also require the standard Jenkins CSRF crumb (enforced by
 * {@code CrumbFilter} at the framework level). Responses are always JSON via {@code net.sf.json}.
 *
 * <p>Routing (Stapler getter/do-method traversal):
 * <pre>
 *   GET  /interactive-input/api/v1/questions              -&gt; Questions#doIndex        (Overall/Read; ?all=true =&gt; Administer)
 *   GET  /interactive-input/api/v1/questions/{id}          -&gt; QuestionEndpoint#doIndex  (Item.READ, else 404)
 *   POST /interactive-input/api/v1/questions/{id}/answer   -&gt; QuestionEndpoint#doAnswer (Item.BUILD, else 403; 409 if settled)
 *   POST /interactive-input/api/v1/questions/{id}/abort    -&gt; QuestionEndpoint#doAbort  (Item.CANCEL, else 403; 409 if settled)
 *   POST /interactive-input/api/v1/preview                 -&gt; V1#doPreview              (Overall/Read; safe markdown preview)
 *   GET  /interactive-input/api/v1/health                  -&gt; V1#doHealth               (anonymous)
 *   GET  /interactive-input/api/v1/views                   -&gt; Views#doIndex             (Overall/Read; ?all=true =&gt; Administer)
 *   GET  /interactive-input/api/v1/views/{id}              -&gt; ViewEndpoint#doIndex      (Item.READ, else 404)
 *   GET  /interactive-input/api/v1/views/{id}/raw          -&gt; ViewEndpoint#doRaw        (Item.READ, else 404)
 *   GET  /interactive-input/api/v1/views/{id}/rendered     -&gt; ViewEndpoint#doRendered   (Item.READ + HTML + flag, else 404)
 *   GET  /interactive-input/api/v1/views/{id}/download     -&gt; ViewEndpoint#doDownload   (Item.READ, else 404)
 *   GET  /interactive-input/api/v1/views/{id}/downloadGroup -&gt; ViewEndpoint#doDownloadGroup (Item.READ, else 404)
 *   POST /interactive-input/api/v1/views/{id}/comments     -&gt; ViewEndpoint#doComments   (Item.BUILD/submitter, else 403)
 *   POST /interactive-input/api/v1/views/{id}/edit         -&gt; ViewEndpoint#doEdit       (Item.BUILD/submitter, else 403)
 *   POST /interactive-input/api/v1/views/{id}/decision     -&gt; ViewEndpoint#doDecision   (Item.BUILD/submitter, else 403)
 *   POST /interactive-input/api/v1/views/{id}/resolveComment -&gt; ViewEndpoint#doResolveComment (Item.BUILD/submitter)
 * </pre>
 */
@Extension
public class ApiRootAction implements UnprotectedRootAction {

    public static final String URL_NAME = "interactive-input";

    private static final Logger LOGGER = Logger.getLogger(ApiRootAction.class.getName());

    @Override
    @CheckForNull
    public String getIconFileName() {
        return null;
    }

    @Override
    @CheckForNull
    public String getDisplayName() {
        return null;
    }

    @Override
    @NonNull
    public String getUrlName() {
        return URL_NAME;
    }

    public Api getApi() {
        return new Api();
    }

    static boolean apiEnabled() {
        return InteractiveInputGlobalConfig.featuresOrDefault().isRestApi();
    }

    @CheckForNull
    static HttpResponse apiDisabledOrNull() {
        return apiEnabled() ? null : JsonHttpResponse.error(404, "Interactive Input REST API is disabled");
    }

    /**
     * Gate for the {@code /views} endpoints: the REST API and the {@code interactiveView} feature must
     * both be on. Returns a 404 (no-leak) response when either is off, otherwise {@code null}.
     */
    @CheckForNull
    static HttpResponse viewsDisabledOrNull() {
        HttpResponse apiOff = apiDisabledOrNull();
        if (apiOff != null) {
            return apiOff;
        }
        return InteractiveInputGlobalConfig.featuresOrDefault().isInteractiveView()
                ? null
                : JsonHttpResponse.error(404, "interactiveView is disabled");
    }

    /**
     * @return {@code true} if this document may be served for rendering: the {@code htmlRendering}
     *     feature is on and the snapshot is HTML. Every other format already has a safe path (markdown
     *     through {@link MarkdownRenderer}, everything else as escaped source), so it never needs the
     *     sandboxed frame.
     */
    static boolean htmlRenderable(@NonNull ReviewDocument doc) {
        return InteractiveInputGlobalConfig.featuresOrDefault().isHtmlRendering()
                && ReviewDocument.FORMAT_HTML.equals(doc.getFormat());
    }

    @NonNull
    static JSONObject questionJson(@NonNull Question q, boolean includeAnswer) {
        JSONObject o = q.toJson(includeAnswer, System.currentTimeMillis());
        o.put("contextHtml", MarkdownRenderer.render(q.getContextMd()));
        // Whether the current viewer may actually answer (lock-to-build-starter aware). The modal
        // uses this to lock its controls for a viewer who can see but not answer.
        o.put("canAnswer", QuestionStore.get().canAnswerEffective(q));
        // Aborting the run is Job/Cancel (not Job/Build). The modal disables Deny when this is false.
        o.put("canAbort", QuestionStore.get().canAbortEffective(q));
        // B27: a bridged native input that declares parameters is mirrored with no choices and no
        // free text, so it cannot be answered in our modal. Hand the client the build's own input
        // page URL so it can offer "Open the build's input page" instead of a submit that can only
        // dead-end on "Pick a choice or type an answer".
        String forwardUrl = bridgedInputUrl(q);
        if (forwardUrl != null) {
            o.put("forwardUrl", forwardUrl);
        }
        // B24: expose native input-style parameters as a typed array the modal renders (name, type,
        // description, default, and choices for a choice parameter). Values are never resolved here —
        // only definitions — so nothing secret is emitted.
        JSONArray params = parametersJson(q);
        if (params != null) {
            o.put("parameters", params);
        }
        return o;
    }

    /**
     * The question's {@code input}-style parameters as a client-renderable array, or {@code null} when
     * the question has none (B24). Each entry carries {@code name}, {@code description}, {@code type}
     * (one of {@code string} / {@code text} / {@code boolean} / {@code choice} / {@code password}, or
     * {@code unsupported} for a type the dialog cannot render), an optional {@code default}, and for a
     * choice its {@code choices}. A password's default is deliberately omitted (never expose a secret).
     */
    @CheckForNull
    static JSONArray parametersJson(@NonNull Question q) {
        if (!q.hasParameters()) {
            return null;
        }
        JSONArray arr = new JSONArray();
        for (ParameterDefinition def : q.getParameters()) {
            JSONObject p = new JSONObject();
            p.put("name", def.getName());
            p.put("description", def.getDescription() == null ? "" : def.getDescription());
            String type;
            if (def instanceof TextParameterDefinition) {
                type = "text"; // must precede StringParameterDefinition (it is a subclass)
            } else if (def instanceof PasswordParameterDefinition) {
                type = "password";
            } else if (def instanceof BooleanParameterDefinition) {
                type = "boolean";
            } else if (def instanceof ChoiceParameterDefinition) {
                type = "choice";
                JSONArray choices = new JSONArray();
                choices.addAll(((ChoiceParameterDefinition) def).getChoices());
                p.put("choices", choices);
            } else if (def instanceof StringParameterDefinition) {
                type = "string";
            } else {
                type = "unsupported";
            }
            p.put("type", type);
            if (!"password".equals(type)) {
                ParameterValue dv = def.getDefaultParameterValue();
                Object dvv = dv == null ? null : dv.getValue();
                if (dvv != null) {
                    p.put("default", dvv);
                }
            }
            arr.add(p);
        }
        return arr;
    }

    /**
     * The build's native {@code input} page URL for a bridged question that cannot be answered in our
     * modal (a native input with parameters: no choices and no free text), or {@code null} for any
     * other question. Lets the client forward the user to the built-in input form rather than
     * dead-ending (B27). Mirrors the URL the bridge itself builds for the context link.
     */
    @CheckForNull
    static String bridgedInputUrl(@NonNull Question q) {
        if (!q.isBridged() || !q.getChoices().isEmpty() || q.isAllowFreeText()) {
            return null;
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        Job<?, ?> job = j.getItemByFullName(q.getJobFullName(), Job.class);
        if (job == null) {
            return null;
        }
        Run<?, ?> run = job.getBuildByNumber(q.getBuildNumber());
        if (run == null) {
            return null;
        }
        String root = j.getRootUrl();
        return (root != null && !root.isEmpty() ? root : "/") + run.getUrl() + "input/";
    }

    // ==========================================================================================
    // /api
    // ==========================================================================================
    // Stapler's getter-routing hardening (jenkins.security.stapler.TypedFilter) only traverses a
    // getter (here getApi()) when its return type is a "Stapler-relevant" node: one that is
    // @StaplerAccessibleType, implements a Stapler interface, or exposes at least one web method.
    // Api is a pure container (only getV1()), so without this annotation getApi() is blocked and the
    // entire /interactive-input/api/** tree 404s. V1/Questions already have web methods, and
    // QuestionEndpoint is reached via getDynamic (which bypasses this filter), so only Api needs it.
    @StaplerAccessibleType
    public static class Api {
        public V1 getV1() {
            return new V1();
        }
    }

    // ==========================================================================================
    // /api/v1
    // ==========================================================================================
    public static class V1 {

        public Questions getQuestions() {
            return new Questions();
        }

        public Views getViews() {
            return new Views();
        }

        /**
         * GET /health — anonymous liveness probe (Jenkins Security Scan follow-up on #5166): consumed
         * by load balancers / uptime monitors that cannot present credentials, so it is intentionally
         * reachable without a permission check, and read-only with no side effects, so a CSRF token is
         * not applicable. {@code pending} is only populated for callers who already have
         * {@code Jenkins.READ} (the same instance-wide count {@link Questions#doIndex} would already
         * disclose to them via {@code ?all=true}, at {@code Overall/Administer}, or scoped per-job
         * without extra permission) — an anonymous or otherwise unprivileged caller only ever learns
         * liveness ({@code status: ok}), never operational counts.
         */
        // lgtm[jenkins/csrf] -- read-only, no side effects; nothing here to protect with a CSRF token
        @GET
        public HttpResponse doHealth() {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            JSONObject o = new JSONObject();
            o.put("status", "ok");
            if (Jenkins.get().hasPermission(Jenkins.READ)) {
                o.put("pending", QuestionStore.get().listAll().size());
            }
            return new JsonHttpResponse(200, o);
        }

        /** POST /preview — render markdown to safe HTML for the modal's free-text preview. */
        @RequirePOST
        public HttpResponse doPreview(StaplerRequest2 req) {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            Jenkins.get().checkPermission(Jenkins.READ);
            JSONObject body = readBody(req);
            String md = body.optString("markdown", "");
            JSONObject o = new JSONObject();
            o.put("html", MarkdownRenderer.render(md));
            return new JsonHttpResponse(200, o);
        }
    }

    // ==========================================================================================
    // /api/v1/questions
    // ==========================================================================================
    public static class Questions {

        /**
         * GET /questions — list WAITING questions visible to the caller.
         *
         * <p>Scoping:
         * <ul>
         *   <li>{@code ?job=<fullName>} — questions for one job the caller may answer (per-project
         *       notification centre). Requires {@code Item.READ} on that job; 404 otherwise (no leak).</li>
         *   <li>{@code ?job=<fullName>&build=<n>} — every readable question (any status, with answers)
         *       for one build (per-build audit view).</li>
         *   <li>{@code ?all=true} — every WAITING question (requires {@code Overall/Administer}).</li>
         *   <li>default — every WAITING question the caller may answer, across all jobs.</li>
         * </ul>
         */
        // lgtm[jenkins/csrf] -- read-only listing, no side effects; already permission-checked below
        @GET
        public HttpResponse doIndex(StaplerRequest2 req) {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            Jenkins j = Jenkins.get();
            if (!j.hasPermission(Jenkins.READ)) {
                return JsonHttpResponse.error(403, "Overall/Read required");
            }
            QuestionStore store = QuestionStore.get();
            String jobParam = req.getParameter("job");
            boolean all = "true".equalsIgnoreCase(req.getParameter("all"));
            List<Question> list;
            boolean includeAnswer = false;
            if (jobParam != null && !jobParam.isEmpty()) {
                Job<?, ?> job = j.getItemByFullName(jobParam, Job.class);
                if (job == null || !job.hasPermission(Item.READ)) {
                    return JsonHttpResponse.error(404, "No such job: " + jobParam);
                }
                String buildParam = req.getParameter("build");
                if (buildParam != null && !buildParam.isEmpty()) {
                    int buildNumber;
                    try {
                        buildNumber = Integer.parseInt(buildParam.trim());
                    } catch (NumberFormatException e) {
                        return JsonHttpResponse.error(400, "build must be an integer");
                    }
                    list = store.listForBuild(jobParam, buildNumber);
                    includeAnswer = true; // audit view: show what was chosen
                } else {
                    // Self-heal bridged mirrors for this pipeline so answers made through the native
                    // input UI (or a mirror just answered here) drop out within one poll instead of
                    // waiting for the 30s ticker. reconcile() is a cheap near-no-op when the bridge is
                    // off, and must never break the list response.
                    try {
                        InputStepBridge.get().reconcile(jobParam);
                    } catch (RuntimeException e) {
                        LOGGER.log(Level.FINE, e, () -> "bridge reconcile failed for " + jobParam);
                    }
                    list = store.listNotificationsForJob(jobParam);
                }
            } else if (all) {
                if (!j.hasPermission(Jenkins.ADMINISTER)) {
                    return JsonHttpResponse.error(403, "Overall/Administer required for ?all=true");
                }
                list = store.listAll();
            } else {
                list = store.listNotifications();
            }
            JSONArray arr = new JSONArray();
            for (Question q : list) {
                arr.add(questionJson(q, includeAnswer));
            }
            JSONObject o = new JSONObject();
            o.put("count", arr.size());
            o.put("questions", arr);
            return new JsonHttpResponse(200, o);
        }

        public QuestionEndpoint getDynamic(String id) {
            return new QuestionEndpoint(id);
        }
    }

    // ==========================================================================================
    // /api/v1/questions/{id}
    // ==========================================================================================
    public static class QuestionEndpoint {

        private final String id;

        public QuestionEndpoint(String id) {
            this.id = id;
        }

        /** GET /questions/{id} — detail. 404 if not found or not readable (avoids existence leak). */
        // lgtm[jenkins/csrf] -- read-only, no side effects; store.canView(q) below is the permission check
        @GET
        public HttpResponse doIndex() {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            QuestionStore store = QuestionStore.get();
            Question q = store.get(id);
            if (q == null || !store.canView(q)) {
                return JsonHttpResponse.error(404, "No such question: " + id);
            }
            return new JsonHttpResponse(200, questionJson(q, true));
        }

        /** POST /questions/{id}/answer — submit an answer. */
        @RequirePOST
        public HttpResponse doAnswer(StaplerRequest2 req) {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            QuestionStore store = QuestionStore.get();
            Question q = store.get(id);
            if (q == null) {
                return JsonHttpResponse.error(404, "No such question: " + id);
            }
            if (!store.canAnswerEffective(q)) {
                return JsonHttpResponse.error(403, "Job/Build permission (or submitter membership) required");
            }
            if (q.getStatus().isTerminal()) {
                return JsonHttpResponse.error(409, "Question already " + q.getStatus());
            }
            JSONObject body = readBody(req);
            String choiceId = optString(body, "choiceId");
            String freeText = optString(body, "freeText");

            // B24: a parameterized question is answered by submitting parameter values, unless the caller
            // resolves it without answering via a deny/skip sentinel (which any question accepts).
            boolean sentinel = Answer.DENY_CHOICE_ID.equals(choiceId) || Answer.SKIP_CHOICE_ID.equals(choiceId);
            if (q.hasParameters() && !sentinel) {
                Map<String, Object> values;
                try {
                    values = convertParameters(q, body.optJSONObject("parameters"));
                } catch (IllegalArgumentException e) {
                    return JsonHttpResponse.error(400, e.getMessage());
                }
                try {
                    store.answerParameters(id, values, QuestionStore.currentUserId(), QuestionStore.SOURCE_REST);
                } catch (IllegalStateException e) {
                    return JsonHttpResponse.error(409, e.getMessage());
                }
                return new JsonHttpResponse(200, questionJson(q, true));
            }

            String validationError = validateAnswer(q, choiceId, freeText);
            if (validationError != null) {
                return JsonHttpResponse.error(400, validationError);
            }
            try {
                store.answer(id, choiceId, freeText, QuestionStore.currentUserId(), QuestionStore.SOURCE_REST);
            } catch (IllegalStateException e) {
                return JsonHttpResponse.error(409, e.getMessage());
            }
            return new JsonHttpResponse(200, questionJson(q, true));
        }

        /** POST /questions/{id}/abort — cancel the input (delivers an abort to the pipeline). */
        @RequirePOST
        public HttpResponse doAbort() {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            QuestionStore store = QuestionStore.get();
            Question q = store.get(id);
            if (q == null) {
                return JsonHttpResponse.error(404, "No such question: " + id);
            }
            if (!store.canAbortEffective(q)) {
                return JsonHttpResponse.error(403, "Job/Cancel permission (or submitter membership) required");
            }
            if (q.getStatus().isTerminal()) {
                return JsonHttpResponse.error(409, "Question already " + q.getStatus());
            }
            try {
                store.abort(id, QuestionStore.currentUserId(), QuestionStore.SOURCE_REST);
            } catch (IllegalStateException e) {
                return JsonHttpResponse.error(409, e.getMessage());
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("id", id);
            return new JsonHttpResponse(200, o);
        }
    }

    // ==========================================================================================
    // /api/v1/views
    // ==========================================================================================
    public static class Views {

        /**
         * GET /views — list review documents visible to the caller.
         *
         * <p>Scoping mirrors {@code /questions}:
         * <ul>
         *   <li>{@code ?job=<fullName>} — OPEN, notify-enabled reviews for one job the caller may read
         *       (the per-project notification list). Requires {@code Item.READ}; 404 otherwise (no leak).</li>
         *   <li>{@code ?job=<fullName>&build=<n>} — every readable review (any status) for one build
         *       (per-build list/audit).</li>
         *   <li>{@code ?all=true} — every OPEN readable review (requires {@code Overall/Administer}).</li>
         *   <li>default — every OPEN, notify-enabled review the caller may read, across all jobs.</li>
         * </ul>
         */
        // lgtm[jenkins/csrf] -- read-only listing, no side effects; already permission-checked below
        @GET
        public HttpResponse doIndex(StaplerRequest2 req) {
            HttpResponse disabled = viewsDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            Jenkins j = Jenkins.get();
            if (!j.hasPermission(Jenkins.READ)) {
                return JsonHttpResponse.error(403, "Overall/Read required");
            }
            ViewStore store = ViewStore.get();
            String jobParam = req.getParameter("job");
            boolean all = "true".equalsIgnoreCase(req.getParameter("all"));
            List<ReviewDocument> list;
            if (jobParam != null && !jobParam.isEmpty()) {
                Job<?, ?> job = j.getItemByFullName(jobParam, Job.class);
                if (job == null || !job.hasPermission(Item.READ)) {
                    return JsonHttpResponse.error(404, "No such job: " + jobParam);
                }
                String buildParam = req.getParameter("build");
                if (buildParam != null && !buildParam.isEmpty()) {
                    int buildNumber;
                    try {
                        buildNumber = Integer.parseInt(buildParam.trim());
                    } catch (NumberFormatException e) {
                        return JsonHttpResponse.error(400, "build must be an integer");
                    }
                    list = store.listForBuild(jobParam, buildNumber);
                } else {
                    list = store.listNotificationsForJob(jobParam);
                }
            } else if (all) {
                if (!j.hasPermission(Jenkins.ADMINISTER)) {
                    return JsonHttpResponse.error(403, "Overall/Administer required for ?all=true");
                }
                list = store.listOpenReadable();
            } else {
                list = store.listNotifications();
            }
            JSONArray arr = new JSONArray();
            for (ReviewDocument d : list) {
                arr.add(viewSummaryJson(d));
            }
            JSONObject o = new JSONObject();
            o.put("count", arr.size());
            o.put("views", arr);
            return new JsonHttpResponse(200, o);
        }

        public ViewEndpoint getDynamic(String id) {
            return new ViewEndpoint(id);
        }
    }

    // ==========================================================================================
    // /api/v1/views/{id}
    // ==========================================================================================
    public static class ViewEndpoint {

        /** Bound comment size so a single comment cannot flood the store. */
        private static final int MAX_COMMENT_CHARS = 10_000;

        /** Bound an edited review copy (chars), matching the step's snapshot cap order of magnitude. */
        private static final int MAX_EDIT_CHARS = 2 * 1024 * 1024;

        private final String id;

        public ViewEndpoint(String id) {
            this.id = id;
        }

        /** GET /views/{id} — detail (metadata + comments + current content). 404 if not readable. */
        // lgtm[jenkins/csrf] -- read-only, no side effects; store.canView(doc) below is the permission check
        @GET
        public HttpResponse doIndex() {
            HttpResponse disabled = viewsDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            ViewStore store = ViewStore.get();
            ReviewDocument doc = store.get(id);
            if (doc == null || !store.canView(doc)) {
                return JsonHttpResponse.error(404, "No such review: " + id);
            }
            return new JsonHttpResponse(200, viewJson(doc, true));
        }

        /** GET /views/{id}/raw?version=n — one content version as JSON {@code {version, content}}. */
        // lgtm[jenkins/csrf] -- read-only, no side effects; store.canView(doc) below is the permission check
        @GET
        public HttpResponse doRaw(StaplerRequest2 req) {
            HttpResponse disabled = viewsDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            ViewStore store = ViewStore.get();
            ReviewDocument doc = store.get(id);
            if (doc == null || !store.canView(doc)) {
                return JsonHttpResponse.error(404, "No such review: " + id);
            }
            int version = doc.getCurrentVersion();
            String v = req.getParameter("version");
            if (v != null && !v.isEmpty()) {
                try {
                    version = Integer.parseInt(v.trim());
                } catch (NumberFormatException e) {
                    return JsonHttpResponse.error(400, "version must be an integer");
                }
                if (version < 1 || version > doc.getCurrentVersion()) {
                    return JsonHttpResponse.error(404, "No such version: " + version);
                }
            }
            String content = store.readContent(id, version);
            if (content == null) {
                return JsonHttpResponse.error(404, "No such version: " + version);
            }
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("version", version);
            o.put("content", content);
            return new JsonHttpResponse(200, o);
        }

    /**
     * GET /views/{id}/rendered?version=n — the snapshot as {@code text/html} for display inside the
     * review page's sandboxed frame ("Rendered" view), so a generated report is readable as a report
     * rather than as escaped source.
     *
     * <p>Read-only (no CSRF needed) and permission-checked exactly like {@link #doRaw}:
     * {@code Item.READ} via {@link ViewStore#canView}, else 404 (no existence leak). Two further gates
     * keep this from becoming a general "serve arbitrary HTML from Jenkins" endpoint: it 404s unless the
     * {@code htmlRendering} feature is on, and unless the document really is an HTML snapshot
     * ({@link ApiRootAction#htmlRenderable}) — a markdown or code document is never served this way.
     *
     * <p>The bytes are untrusted, so the isolation lives entirely in the response headers — see
     * {@link SandboxedHtmlResponse}, which serves them under {@code Content-Security-Policy: sandbox
     * allow-scripts} (an opaque origin that cannot touch this Jenkins session, even if the URL is opened
     * directly).
     */
    // lgtm[jenkins/csrf] -- read-only, no side effects; store.canView(doc) below is the permission check
    @GET
    public HttpResponse doRendered(StaplerRequest2 req) {
        HttpResponse disabled = viewsDisabledOrNull();
        if (disabled != null) {
            return disabled;
        }
        ViewStore store = ViewStore.get();
        ReviewDocument doc = store.get(id);
        if (doc == null || !store.canView(doc) || !htmlRenderable(doc)) {
            return JsonHttpResponse.error(404, "No such renderable review: " + id);
        }
        int version = doc.getCurrentVersion();
        String v = req.getParameter("version");
        if (v != null && !v.isEmpty()) {
            try {
                version = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return JsonHttpResponse.error(400, "version must be an integer");
            }
            if (version < 1 || version > doc.getCurrentVersion()) {
                return JsonHttpResponse.error(404, "No such version: " + version);
            }
        }
        String content = store.readContent(id, version);
        if (content == null) {
            return JsonHttpResponse.error(404, "No content for review: " + id);
        }
        return new SandboxedHtmlResponse(content);
    }

    /**
     * GET /views/{id}/download — the current version's content as a file attachment.
     *
     * <p>Read-only (no CSRF needed) and permission-checked exactly like {@link #doIndex()}:
     * {@code Item.READ} via {@link ViewStore#canView}, else 404 (no existence leak). The download name
     * is the snapshot's basename, further sanitised by {@link DownloadHttpResponse}.
     */
        // lgtm[jenkins/csrf] -- read-only download, no side effects; store.canView(doc) below is the permission check
        @GET
        public HttpResponse doDownload() {
            HttpResponse disabled = viewsDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            ViewStore store = ViewStore.get();
            ReviewDocument doc = store.get(id);
            if (doc == null || !store.canView(doc)) {
                return JsonHttpResponse.error(404, "No such review: " + id);
            }
            String content = store.readCurrentContent(id);
            if (content == null) {
                return JsonHttpResponse.error(404, "No content for review: " + id);
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            return new DownloadHttpResponse("application/octet-stream", downloadBaseName(doc), bytes);
        }

        /**
         * GET /views/{id}/downloadGroup — every readable document published together with this one (same
         * {@code groupId} on the same build) as a single ZIP; a lone document yields a one-entry archive.
         *
         * <p>Read-only and permission-checked like {@link #doDownload()}: the anchor document must be
         * readable ({@code Item.READ}, else 404), and only co-group members the caller can also read are
         * included ({@link ViewStore#listForBuild} already applies {@code canView}). ZIP entry names are
         * sanitised (no CR/LF, no leading {@code /}, no {@code .}/{@code ..} segments) and de-duplicated.
         */
        // lgtm[jenkins/csrf] -- read-only download, no side effects; store.canView(doc) below is the permission check
        @GET
        public HttpResponse doDownloadGroup() {
            HttpResponse disabled = viewsDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            ViewStore store = ViewStore.get();
            ReviewDocument doc = store.get(id);
            if (doc == null || !store.canView(doc)) {
                return JsonHttpResponse.error(404, "No such review: " + id);
            }
            String group = doc.getGroupId();
            List<ReviewDocument> members = new ArrayList<>();
            for (ReviewDocument d : store.listForBuild(doc.getJobFullName(), doc.getBuildNumber())) {
                if (group.equals(d.getGroupId())) {
                    members.add(d);
                }
            }
            byte[] zip;
            try {
                zip = zipMembers(store, members);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, e, () -> "failed to build download archive for group " + group);
                return JsonHttpResponse.error(500, "Failed to build download archive");
            }
            if (zip == null) {
                return JsonHttpResponse.error(404, "No downloadable content for review: " + id);
            }
            return new DownloadHttpResponse("application/zip", zipDownloadName(doc), zip);
        }

        /**
         * ZIPs the current content of each member into an in-memory archive, or returns {@code null} if no
         * member had readable content. A member whose bytes are missing is skipped (not fatal).
         */
        @CheckForNull
        private static byte[] zipMembers(@NonNull ViewStore store, @NonNull List<ReviewDocument> members)
                throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Set<String> used = new HashSet<>();
            int entries = 0;
            try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
                for (ReviewDocument d : members) {
                    String content = store.readCurrentContent(d.getId());
                    if (content == null) {
                        continue;
                    }
                    ZipEntry entry = new ZipEntry(uniqueEntryName(used, safeEntryName(d.getFileName(), d.getId())));
                    entry.setTime(d.getCreatedTs());
                    zos.putNextEntry(entry);
                    zos.write(content.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                    entries++;
                }
            }
            return entries == 0 ? null : baos.toByteArray();
        }

        /** The single-file download name: the snapshot's basename, sanitised, with a safe fallback. */
        @NonNull
        private static String downloadBaseName(@NonNull ReviewDocument doc) {
            String base = stripDownloadName(baseName(doc.getFileName()));
            if (!base.isEmpty()) {
                return base;
            }
            String fallback = stripDownloadName(doc.getReportName());
            return fallback.isEmpty() ? "download.txt" : fallback;
        }

        /** The group ZIP download name: {@code <reportName>.zip} (sanitised, with a safe fallback). */
        @NonNull
        private static String zipDownloadName(@NonNull ReviewDocument doc) {
            String base = stripDownloadName(doc.getReportName());
            if (base.isEmpty()) {
                base = "reviews";
            }
            return base.endsWith(".zip") ? base : base + ".zip";
        }

        /** Strip control chars, path separators and quotes from a suggested download name. */
        @NonNull
        private static String stripDownloadName(@NonNull String raw) {
            return raw.replaceAll("[\\p{Cntrl}/\\\\\"]", "").trim();
        }

        /** @return the last path segment of {@code path} (handles both {@code /} and {@code \\}). */
        @NonNull
        private static String baseName(@NonNull String path) {
            String p = path.replace('\\', '/');
            int slash = p.lastIndexOf('/');
            return slash >= 0 ? p.substring(slash + 1) : p;
        }

        /**
         * A ZIP entry name derived from the file path: control chars removed and any leading {@code /},
         * {@code .} or {@code ..} segments dropped (so the archive can never write outside its root when a
         * recipient extracts it). Falls back to {@code file-<id>} if nothing usable remains.
         */
        @NonNull
        private static String safeEntryName(@NonNull String fileName, @NonNull String fallbackId) {
            String cleaned = fileName.replace('\\', '/').replaceAll("\\p{Cntrl}", "");
            StringBuilder sb = new StringBuilder();
            for (String seg : cleaned.split("/")) {
                String s = seg.trim();
                if (s.isEmpty() || ".".equals(s) || "..".equals(s)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(s);
            }
            String name = sb.toString();
            return name.isEmpty() ? "file-" + fallbackId : name;
        }

        /** @return {@code desired} if unused, else {@code stem-2.ext}, {@code stem-3.ext}, … (ZIP forbids dupes). */
        @NonNull
        private static String uniqueEntryName(@NonNull Set<String> used, @NonNull String desired) {
            if (used.add(desired)) {
                return desired;
            }
            int dot = desired.lastIndexOf('.');
            String stem = dot > 0 ? desired.substring(0, dot) : desired;
            String ext = dot > 0 ? desired.substring(dot) : "";
            for (int n = 2; n < 10_000; n++) {
                String candidate = stem + "-" + n + ext;
                if (used.add(candidate)) {
                    return candidate;
                }
            }
            return stem + "-" + java.util.UUID.randomUUID() + ext;
        }

        /**
         * POST /views/{id}/comments — add an inline or general comment, optionally as a threaded reply
         * ({@code parentId}) and/or with a display-only author label ({@code authorLabel}, or set
         * {@code automated:true} to use the configured global label). The audit author is always the
         * authenticated Jenkins identity — never taken from the request — so a label cannot spoof it.
         */
        @RequirePOST
        public HttpResponse doComments(StaplerRequest2 req) {
            HttpResponse gate = mutationGate();
            if (gate != null) {
                return gate;
            }
            ViewStore store = ViewStore.get();
            ReviewDocument doc = store.get(id);
            if (doc == null) {
                return JsonHttpResponse.error(404, "No such review: " + id);
            }
            if (!doc.isCommentable()) {
                return JsonHttpResponse.error(409, "This review is not commentable");
            }
            JSONObject body = readViewBody(req);
            String text = optString(body, "body");
            if (text == null) {
                return JsonHttpResponse.error(400, "A comment body is required");
            }
            if (text.length() > MAX_COMMENT_CHARS) {
                return JsonHttpResponse.error(400, "Comment too long (max " + MAX_COMMENT_CHARS + " chars)");
            }
            int line = optInt(body, "line", ReviewComment.GENERAL);
            if (line < 1) {
                line = ReviewComment.GENERAL;
            }
            String parentId = optString(body, "parentId");
            // Display label resolution: an explicit authorLabel wins; otherwise an automated reply uses the
            // configured global default; a plain comment has none. The label is display-only (the client
            // renders it via textContent) and length-bounded here.
            String authorLabel = boundLabel(optString(body, "authorLabel"));
            if (authorLabel == null && body.optBoolean("automated", false)) {
                authorLabel = boundLabel(InteractiveInputGlobalConfig.automationReplyNameOrDefault());
            }
            // Optional verbatim snippet of the exact text the reviewer highlighted (viewer highlight-select
            // flow). Display-only context shown back via textContent; length-bounded here. Only meaningful for
            // a line-anchored comment, so it is dropped for a general note. Newlines in a multi-line selection
            // are collapsed to spaces by boundText, matching the viewer's single-line context chips.
            String quote = line >= 1 ? boundText(optString(body, "quote"), MAX_QUOTE_CHARS) : null;
            try {
                store.addComment(id, line, text, ViewStore.currentUserId(), parentId, authorLabel, quote);
            } catch (IllegalArgumentException e) {
                return JsonHttpResponse.error(400, e.getMessage());
            } catch (IllegalStateException e) {
                return JsonHttpResponse.error(409, e.getMessage());
            }
            // Return the FULL document (content + renderedHtml), like doEdit/doDecision. The client
            // replaces its detail state with this response and re-renders; omitting content blanked the
            // left pane until a manual refresh (the inline-comment "crash").
            return new JsonHttpResponse(200, viewJson(doc, true));
        }

        /** POST /views/{id}/edit — save an edit to the durable review copy as a new version. */
        @RequirePOST
        public HttpResponse doEdit(StaplerRequest2 req) {
            HttpResponse gate = mutationGate();
            if (gate != null) {
                return gate;
            }
            ViewStore store = ViewStore.get();
            ReviewDocument doc = store.get(id);
            if (doc == null) {
                return JsonHttpResponse.error(404, "No such review: " + id);
            }
            if (!doc.isEditable()) {
                return JsonHttpResponse.error(409, "This review is not editable");
            }
            JSONObject body = readViewBody(req);
            String content = body.optString("content", null);
            if (content == null) {
                return JsonHttpResponse.error(400, "content is required");
            }
            if (content.length() > MAX_EDIT_CHARS) {
                return JsonHttpResponse.error(400, "content too large (max " + MAX_EDIT_CHARS + " chars)");
            }
            // Optional, display-only edit summary (e.g. an AI course-correction reason) recorded in the
            // version history; a blank/absent note keeps the default "edited" label.
            String note = boundText(body.optString("note", null), MAX_VERSION_NOTE_CHARS);
            try {
                store.saveEdit(id, content, ViewStore.currentUserId(), note);
            } catch (IllegalStateException e) {
                return JsonHttpResponse.error(409, e.getMessage());
            }
            return new JsonHttpResponse(200, viewJson(doc, true));
        }

        /** POST /views/{id}/decision — record approve / reject / acknowledge (resolves a waiting step). */
        @RequirePOST
        public HttpResponse doDecision(StaplerRequest2 req) {
            HttpResponse gate = mutationGate();
            if (gate != null) {
                return gate;
            }
            ViewStore store = ViewStore.get();
            ReviewDocument doc = store.get(id);
            if (doc == null) {
                return JsonHttpResponse.error(404, "No such review: " + id);
            }
            JSONObject body = readViewBody(req);
            ReviewStatus target = decisionOf(optString(body, "decision"));
            if (target == null) {
                return JsonHttpResponse.error(
                        400, "decision must be one of approve, reject, acknowledge, request-changes");
            }
            try {
                store.decide(id, target, ViewStore.currentUserId(), QuestionStore.SOURCE_REST);
            } catch (IllegalStateException e) {
                return JsonHttpResponse.error(409, e.getMessage());
            } catch (IllegalArgumentException e) {
                return JsonHttpResponse.error(400, e.getMessage());
            }
            return new JsonHttpResponse(200, viewJson(doc, true));
        }

        /** POST /views/{id}/resolveComment — toggle a comment's resolved flag. */
        @RequirePOST
        public HttpResponse doResolveComment(StaplerRequest2 req) {
            HttpResponse gate = mutationGate();
            if (gate != null) {
                return gate;
            }
            ViewStore store = ViewStore.get();
            ReviewDocument doc = store.get(id);
            if (doc == null) {
                return JsonHttpResponse.error(404, "No such review: " + id);
            }
            JSONObject body = readViewBody(req);
            String commentId = optString(body, "commentId");
            if (commentId == null) {
                return JsonHttpResponse.error(400, "commentId is required");
            }
            boolean resolved = body.optBoolean("resolved", true);
            try {
                store.setCommentResolved(id, commentId, resolved);
            } catch (IllegalStateException e) {
                return JsonHttpResponse.error(404, e.getMessage());
            }
            // Full document (content + renderedHtml) so the left pane survives a resolve toggle — see
            // doComments above for the rationale.
            return new JsonHttpResponse(200, viewJson(doc, true));
        }

        /**
         * Shared guard for every mutation: API enabled, document readable (404 no-leak) and the caller
         * may contribute (403). Returns the error response, or {@code null} when the caller may proceed.
         */
        @CheckForNull
        private HttpResponse mutationGate() {
            HttpResponse disabled = viewsDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            ViewStore store = ViewStore.get();
            ReviewDocument doc = store.get(id);
            if (doc == null || !store.canView(doc)) {
                return JsonHttpResponse.error(404, "No such review: " + id);
            }
            if (!store.canContributeEffective(doc)) {
                return JsonHttpResponse.error(403, "Job/Build permission (or submitter membership) required");
            }
            return null;
        }
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    /** Compact review summary for list endpoints (no comments or content). Includes the editor URL. */
    @NonNull
    static JSONObject viewSummaryJson(@NonNull ReviewDocument doc) {
        JSONObject o = doc.toJson(false, System.currentTimeMillis());
        ViewStore store = ViewStore.get();
        o.put("canContribute", store.canContributeEffective(doc));
        String url = editorUrl(doc);
        if (url != null) {
            o.put("url", url);
        }
        return o;
    }

    /** Full review detail: metadata, comments (with sanitised {@code bodyHtml}) and current content. */
    @NonNull
    static JSONObject viewJson(@NonNull ReviewDocument doc, boolean includeContent) {
        long now = System.currentTimeMillis();
        JSONObject o = doc.toJson(false, now);
        ViewStore store = ViewStore.get();
        o.put("canView", store.canView(doc));
        o.put("canContribute", store.canContributeEffective(doc));
        String url = editorUrl(doc);
        if (url != null) {
            o.put("url", url);
        }
        JSONArray comments = new JSONArray();
        for (ReviewComment c : doc.getComments()) {
            JSONObject cj = c.toJson();
            // Comment bodies are untrusted markdown; render to sanitised HTML server-side (never stored
            // as HTML, never inserted raw by the client).
            cj.put("bodyHtml", MarkdownRenderer.render(c.getBody()));
            comments.add(cj);
        }
        o.put("comments", comments);
        if (includeContent) {
            String content = store.readCurrentContent(doc.getId());
            o.put("content", content == null ? "" : content);
            // Markdown is rendered to sanitised HTML for display; every other format (HTML/code/text) is
            // sent as raw text only and shown ESCAPED by the client (never executed). Block elements carry
            // data-source-line so the client can anchor inline comments on the rendered view too.
            if (ReviewDocument.FORMAT_MARKDOWN.equals(doc.getFormat())) {
                o.put("renderedHtml", MarkdownRenderer.renderWithSourceLines(content == null ? "" : content));
            }
        }
        // An HTML snapshot is never inlined as renderedHtml (sanitising it away would leave a generated
        // report blank). Instead the client is told it may offer the "Rendered" view and loads it from
        // /rendered into a sandboxed frame — see doRendered.
        o.put("htmlRenderable", htmlRenderable(doc));
        return o;
    }

    /** The review editor page URL ({@code .../<build>/interactive-view/?doc=<id>}), or {@code null}. */
    @CheckForNull
    static String editorUrl(@NonNull ReviewDocument doc) {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        Job<?, ?> job = j.getItemByFullName(doc.getJobFullName(), Job.class);
        if (job == null) {
            return null;
        }
        Run<?, ?> run = job.getBuildByNumber(doc.getBuildNumber());
        String root = j.getRootUrl();
        String base = (root != null && !root.isEmpty()) ? root : "/";
        String scope = run != null ? run.getUrl() : job.getUrl();
        return base + scope + InteractiveViewRunAction.URL_NAME + "/?doc=" + Util.rawEncode(doc.getId());
    }

    @NonNull
    static JSONObject readViewBody(@NonNull StaplerRequest2 req) {
        JSONObject o = new JSONObject();
        String ct = req.getContentType();
        if (ct != null && ct.toLowerCase(Locale.ROOT).contains("application/json")) {
            try (BufferedReader r = req.getReader()) {
                if (r != null) {
                    String body = r.lines().collect(Collectors.joining("\n"));
                    if (!body.trim().isEmpty()) {
                        return JSONObject.fromObject(body);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Malformed/unavailable JSON body: fall through to form parameters / empty object.
            }
            return o;
        }
        putIfPresent(o, "body", req.getParameter("body"));
        putIfPresent(o, "content", req.getParameter("content"));
        putIfPresent(o, "note", req.getParameter("note"));
        putIfPresent(o, "line", req.getParameter("line"));
        putIfPresent(o, "decision", req.getParameter("decision"));
        putIfPresent(o, "commentId", req.getParameter("commentId"));
        putIfPresent(o, "resolved", req.getParameter("resolved"));
        putIfPresent(o, "parentId", req.getParameter("parentId"));
        putIfPresent(o, "authorLabel", req.getParameter("authorLabel"));
        putIfPresent(o, "automated", req.getParameter("automated"));
        return o;
    }

    /** @return the {@link ReviewStatus} for a decision verb, or {@code null} if unrecognised. */
    @CheckForNull
    static ReviewStatus decisionOf(@CheckForNull String decision) {
        if (decision == null) {
            return null;
        }
        switch (decision.trim().toLowerCase(Locale.ROOT)) {
            case "approve":
            case "approved":
                return ReviewStatus.APPROVED;
            case "reject":
            case "rejected":
                return ReviewStatus.REJECTED;
            case "acknowledge":
            case "acknowledged":
            case "ack":
                return ReviewStatus.ACKNOWLEDGED;
            case "changes":
            case "request-changes":
            case "request_changes":
            case "regenerate":
            case "revise":
                return ReviewStatus.CHANGES_REQUESTED;
            default:
                return null;
        }
    }

    private static int optInt(@NonNull JSONObject o, @NonNull String key, int dflt) {
        if (!o.containsKey(key) || o.get(key) == null) {
            return dflt;
        }
        try {
            return o.getInt(key);
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    /**
     * Resolve a submitted {@code {parameters:{name:value}}} body into the ordered name&#8594;value map
     * the step returns (B24). Each declared {@link ParameterDefinition} is converted through Jenkins'
     * own {@link SimpleParameterDefinition#createValue(String)} (so choice membership, boolean parsing,
     * etc. reuse core validation); a missing value falls back to the parameter's default. Declaration
     * order is preserved so a single-parameter question returns that one value with the built-in
     * {@code input} step's contract.
     *
     * @throws IllegalArgumentException if a value is invalid, missing with no default, or the parameter
     *     type cannot be answered in the dialog (caught by the caller and returned as {@code 400}).
     */
    @NonNull
    static Map<String, Object> convertParameters(@NonNull Question q, @CheckForNull JSONObject submitted) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ParameterDefinition def : q.getParameters()) {
            String name = def.getName();
            if (!(def instanceof SimpleParameterDefinition)) {
                throw new IllegalArgumentException("Parameter '" + name + "' has a type not supported in the dialog ("
                        + def.getClass().getSimpleName() + "); answer it on the build's input page.");
            }
            SimpleParameterDefinition sp = (SimpleParameterDefinition) def;
            ParameterValue pv;
            boolean present = submitted != null
                    && submitted.containsKey(name)
                    && !JSONNull.getInstance().equals(submitted.get(name));
            if (present) {
                try {
                    pv = sp.createValue(String.valueOf(submitted.get(name)));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Invalid value for parameter '" + name + "': " + e.getMessage());
                }
            } else {
                pv = def.getDefaultParameterValue();
                if (pv == null) {
                    throw new IllegalArgumentException("Missing value for parameter '" + name + "'");
                }
            }
            out.put(name, pv == null ? null : pv.getValue());
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("This question expects parameters but none were provided");
        }
        return out;
    }

    /** Validate an answer against the question; returns an error message or {@code null} if valid. */
    @CheckForNull
    static String validateAnswer(@NonNull Question q, @CheckForNull String choiceId, @CheckForNull String freeText) {
        if (choiceId != null && !choiceId.isEmpty()) {
            // The deny (continue) and skip sentinels are valid on any question regardless of its declared
            // choices — they resolve the input without picking one. An outright abort uses /abort instead.
            if (io.jenkins.plugins.interactiveinput.model.Answer.DENY_CHOICE_ID.equals(choiceId)
                    || io.jenkins.plugins.interactiveinput.model.Answer.SKIP_CHOICE_ID.equals(choiceId)) {
                return null;
            }
            for (Choice c : q.getChoices()) {
                if (c.getId().equals(choiceId)) {
                    return null;
                }
            }
            return "Unknown choiceId: " + choiceId;
        }
        if (freeText != null && !freeText.isEmpty()) {
            if (!q.isAllowFreeText()) {
                return "This question does not allow free-text answers";
            }
            return null;
        }
        return "An answer must include a choiceId or freeText";
    }

    @CheckForNull
    private static String optString(@NonNull JSONObject o, @NonNull String key) {
        if (!o.containsKey(key) || o.get(key) == null) {
            return null;
        }
        String s = o.getString(key);
        return s.isEmpty() ? null : s;
    }

    /** Bound for a display-only comment author label (mirrors the global config cap). */
    private static final int MAX_LABEL_CHARS = 64;

    /** Bound for the verbatim highlighted-selection snippet stored with a comment (see the viewer). */
    private static final int MAX_QUOTE_CHARS = 500;

    /** Bound for a version note (e.g. an AI edit summary) shown in the viewer's version dropdown. */
    private static final int MAX_VERSION_NOTE_CHARS = 280;

    /**
     * Normalise short display text: control characters (incl. newlines) collapsed to spaces, trimmed and
     * length-bounded to {@code max}. Returns {@code null} for a blank/absent value. These strings are
     * rendered by the client via {@code textContent} (never innerHTML), so bounding here just keeps a
     * runaway value from breaking the layout — no HTML escaping is needed.
     */
    @CheckForNull
    private static String boundText(@CheckForNull String raw, int max) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("\\p{Cntrl}", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    /** Normalise a display-only comment author label (see {@link #boundText}). */
    @CheckForNull
    private static String boundLabel(@CheckForNull String raw) {
        return boundText(raw, MAX_LABEL_CHARS);
    }

    @NonNull
    static JSONObject readBody(@NonNull StaplerRequest2 req) {
        JSONObject o = new JSONObject();
        String ct = req.getContentType();
        if (ct != null && ct.toLowerCase(Locale.ROOT).contains("application/json")) {
            try (BufferedReader r = req.getReader()) {
                if (r != null) {
                    String body = r.lines().collect(Collectors.joining("\n"));
                    if (!body.trim().isEmpty()) {
                        return JSONObject.fromObject(body);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Malformed or unavailable JSON body (I/O error, UncheckedIOException from lines(),
                // or JSONException from parsing): fall through and return an empty object.
            }
            return o;
        }
        putIfPresent(o, "choiceId", req.getParameter("choiceId"));
        putIfPresent(o, "freeText", req.getParameter("freeText"));
        putIfPresent(o, "markdown", req.getParameter("markdown"));
        return o;
    }

    private static void putIfPresent(@NonNull JSONObject o, @NonNull String key, @CheckForNull String value) {
        if (value != null) {
            o.put(key, value);
        }
    }
}
