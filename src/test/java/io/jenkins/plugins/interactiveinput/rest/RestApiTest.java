// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.model.QuestionStatus;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.net.URL;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * REST API tests (§6.3, §7.3): happy paths, the {@code Overall/Read} + {@code Item.BUILD} permission
 * matrix, existence-hiding on detail, feature-flag gating, and a CSRF-crumbed answer POST.
 */
@WithJenkins
class RestApiTest {

    private static final String JOB = "job-a";
    private static final String BASE = "interactive-input/api/v1/";

    @Test
    void healthIsAnonymous(JenkinsRule j) throws Exception {
        secure(j);
        WebResponse r = get(j.createWebClient(), j, BASE + "health");
        assertEquals(200, r.getStatusCode());
        assertEquals("ok", json(r).getString("status"));
    }

    @Test
    void disabledApiReturns404(JenkinsRule j) throws Exception {
        secure(j);
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        cfg.getFeatures().setRestApi(false);
        cfg.save();
        WebResponse r = get(j.createWebClient(), j, BASE + "health");
        assertEquals(404, r.getStatusCode());
    }

    @Test
    void listRequiresOverallReadAndFiltersByAnswerable(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");

        assertEquals(403, get(j.createWebClient(), j, BASE + "questions").getStatusCode());

        WebResponse readerResp = get(j.createWebClient().login("reader"), j, BASE + "questions");
        assertEquals(200, readerResp.getStatusCode());
        assertEquals(0, json(readerResp).getInt("count"), "reader cannot answer, so lists none");

        WebResponse builderResp = get(j.createWebClient().login("builder"), j, BASE + "questions");
        assertEquals(200, builderResp.getStatusCode());
        assertEquals(1, json(builderResp).getInt("count"));
    }

    @Test
    void allParamRequiresAdminister(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");
        assertEquals(
                403,
                get(j.createWebClient().login("reader"), j, BASE + "questions?all=true")
                        .getStatusCode());
        assertEquals(
                200,
                get(j.createWebClient().login("admin"), j, BASE + "questions?all=true")
                        .getStatusCode());
    }

    @Test
    void scopedListByJobFiltersChecksItemReadAndExposesStartedBy(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");

        // builder can answer -> the job's answerable list has 1, and startedBy is exposed.
        WebResponse builderResp = get(j.createWebClient().login("builder"), j, BASE + "questions?job=" + JOB);
        assertEquals(200, builderResp.getStatusCode());
        assertEquals(1, json(builderResp).getInt("count"));
        JSONObject q0 = json(builderResp).getJSONArray("questions").getJSONObject(0);
        assertEquals("tester", q0.getString("startedBy"));

        // reader has Item.READ but not Item.BUILD -> answerable-for-job is empty.
        WebResponse readerResp = get(j.createWebClient().login("reader"), j, BASE + "questions?job=" + JOB);
        assertEquals(200, readerResp.getStatusCode());
        assertEquals(0, json(readerResp).getInt("count"));

        // Unknown job -> 404 (never reveal existence).
        assertEquals(
                404,
                get(j.createWebClient().login("builder"), j, BASE + "questions?job=does-not-exist")
                        .getStatusCode());

        // Overall/Read but no Item.READ on the job -> 404 (no leak).
        assertEquals(
                404,
                get(j.createWebClient().login("outsider"), j, BASE + "questions?job=" + JOB)
                        .getStatusCode());

        // No Overall/Read at all -> 403 at the endpoint gate.
        assertEquals(
                403, get(j.createWebClient(), j, BASE + "questions?job=" + JOB).getStatusCode());
    }

    @Test
    void buildScopedAuditIncludesAnswer(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "questions/q1/answer",
                        "{\"choiceId\":\"yes\"}"));

        WebResponse audit = get(j.createWebClient().login("builder"), j, BASE + "questions?job=" + JOB + "&build=1");
        assertEquals(200, audit.getStatusCode());
        JSONObject body = json(audit);
        assertEquals(1, body.getInt("count"), "settled question still visible in the per-build audit");
        JSONObject q0 = body.getJSONArray("questions").getJSONObject(0);
        assertEquals("ANSWERED", q0.getString("status"));
        assertEquals("yes", q0.getJSONObject("answer").getString("choiceId"));

        // A non-numeric build is rejected.
        assertEquals(
                400,
                get(j.createWebClient().login("builder"), j, BASE + "questions?job=" + JOB + "&build=x")
                        .getStatusCode());
    }

    @Test
    void detailHiddenWithoutItemRead(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");
        WebResponse ok = get(j.createWebClient().login("builder"), j, BASE + "questions/q1");
        assertEquals(200, ok.getStatusCode());
        assertEquals("q1", json(ok).getString("id"));
        // Anonymous lacks Item.READ -> 404 (never reveal existence).
        assertEquals(404, get(j.createWebClient(), j, BASE + "questions/q1").getStatusCode());
    }

    @Test
    void lockToBuildStarterIsEnforcedOverRestAndExposedAsCanAnswer(JenkinsRule j) throws Exception {
        secure(j);
        InteractiveInputGlobalConfig.get().setLockToBuildStarter(true);
        submit("q1", "builder"); // the build was started by "builder"

        // A non-owner who otherwise holds Item.BUILD can SEE it (lock surfaces readable questions) but
        // canAnswer is false and the answer POST is refused.
        WebResponse mallory = get(j.createWebClient().login("mallory"), j, BASE + "questions?job=" + JOB);
        assertEquals(200, mallory.getStatusCode());
        JSONObject mBody = json(mallory);
        assertEquals(1, mBody.getInt("count"), "lock surfaces the question as view-only to non-owners");
        assertFalse(
                mBody.getJSONArray("questions").getJSONObject(0).getBoolean("canAnswer"),
                "a non-owner must not be able to answer while locked");
        assertEquals(
                403,
                postJson(
                        j.createWebClient().login("mallory"),
                        j,
                        BASE + "questions/q1/answer",
                        "{\"choiceId\":\"yes\"}"),
                "locked answer POST from a non-owner is refused");

        // The owner sees canAnswer=true and can answer.
        WebResponse owner = get(j.createWebClient().login("builder"), j, BASE + "questions?job=" + JOB);
        assertTrue(
                owner.getStatusCode() == 200
                        && json(owner)
                                .getJSONArray("questions")
                                .getJSONObject(0)
                                .getBoolean("canAnswer"),
                "the build starter may answer their own build");
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "questions/q1/answer",
                        "{\"choiceId\":\"yes\"}"));
    }

    @Test
    void answerRequiresBuildPermissionThenResumes(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");

        assertEquals(
                403,
                postJson(
                        j.createWebClient().login("reader"),
                        j,
                        BASE + "questions/q1/answer",
                        "{\"choiceId\":\"yes\"}"));

        // Unknown choice -> 400 validation error.
        assertEquals(
                400,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "questions/q1/answer",
                        "{\"choiceId\":\"nope\"}"));

        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "questions/q1/answer",
                        "{\"choiceId\":\"yes\"}"));
        assertEquals(QuestionStatus.ANSWERED, QuestionStore.get().get("q1").getStatus());

        // Second answer on a settled question -> 409.
        assertEquals(
                409,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "questions/q1/answer",
                        "{\"choiceId\":\"yes\"}"));
    }

    @Test
    void skipSentinelIsAcceptedAndResumes(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");
        // B26: the skip sentinel (automation/AI) is valid even though it is not a declared choice of q1.
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "questions/q1/answer",
                        "{\"choiceId\":\"__skip__\"}"));
        JSONObject q = json(get(j.createWebClient().login("builder"), j, BASE + "questions/q1"));
        assertEquals("ANSWERED", q.getString("status"));
        assertEquals("__skip__", q.getJSONObject("answer").getString("choiceId"));
    }

    @Test
    void abortRequiresBuildPermissionAndSettlesAborted(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");
        // B26 (Deny): reader (Item.READ, no Item.BUILD) may not abort.
        assertEquals(403, postJson(j.createWebClient().login("reader"), j, BASE + "questions/q1/abort", "{}"));
        // Builder may abort -> the question settles ABORTED (the step then aborts the run).
        assertEquals(200, postJson(j.createWebClient().login("builder"), j, BASE + "questions/q1/abort", "{}"));
        assertEquals(QuestionStatus.ABORTED, QuestionStore.get().get("q1").getStatus());
        // Aborting an already-settled question -> 409.
        assertEquals(409, postJson(j.createWebClient().login("builder"), j, BASE + "questions/q1/abort", "{}"));
    }

    @Test
    void parameterizedQuestionExposesTypedParametersAndAcceptsValues(JenkinsRule j) throws Exception {
        // B24: a question that declares input-style parameters must expose them as a typed array the
        // modal can render, and accept a {parameters:{name:value}} answer, converting/validating each
        // value through Jenkins' own ParameterDefinition and recording it.
        secure(j);
        submitParams("qp");

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "questions/qp"));
        JSONArray params = detail.getJSONArray("parameters");
        assertEquals(2, params.size(), "both declared parameters must be exposed");
        assertEquals("ENV", params.getJSONObject(0).getString("name"));
        assertEquals("string", params.getJSONObject(0).getString("type"));
        assertEquals("dev", params.getJSONObject(0).getString("default"));
        assertEquals("choice", params.getJSONObject(1).getString("type"));
        assertTrue(params.getJSONObject(1).getJSONArray("choices").contains("a"), "choice options must be exposed");

        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "questions/qp/answer",
                        "{\"parameters\":{\"ENV\":\"prod\",\"TIER\":\"a\"}}"));
        JSONObject after = json(get(j.createWebClient().login("builder"), j, BASE + "questions/qp"));
        assertEquals("ANSWERED", after.getString("status"));
        assertEquals(
                "prod",
                after.getJSONObject("answer").getJSONObject("parameters").getString("ENV"));
        assertEquals(
                "a", after.getJSONObject("answer").getJSONObject("parameters").getString("TIER"));
    }

    @Test
    void parameterizedAnswerRejectsInvalidChoiceValue(JenkinsRule j) throws Exception {
        // B24: an out-of-range choice value is rejected with 400 via core's own ChoiceParameterDefinition
        // validation (reused, not re-implemented).
        secure(j);
        submitParams("qp2");
        assertEquals(
                400,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "questions/qp2/answer",
                        "{\"parameters\":{\"ENV\":\"prod\",\"TIER\":\"nope\"}}"));
        assertEquals(
                QuestionStatus.WAITING,
                QuestionStore.get().get("qp2").getStatus(),
                "an invalid answer must not settle the question");
    }

    @Test
    void bridgedParameterizedInputExposesForwardUrl(JenkinsRule j) throws Exception {
        secure(j);
        // A real build must exist so the server can resolve the native input page URL (B27).
        j.buildAndAssertSuccess(j.jenkins.getItemByFullName(JOB, FreeStyleProject.class));

        // These two mirrors are asserted through questionJson directly rather than over HTTP. A mirror
        // created by hand has no live native input behind it, and the SLA ticker reconciles the bridge
        // every 30s (SlaTicker -> InputStepBridge.sync -> dropBridged), so a mirror can legitimately be
        // reaped between submit() and an HTTP GET — which made this test fail intermittently with
        // "404 No such question". Asserting the JSON the endpoint would serve, from the Question itself,
        // tests exactly the same contract without racing a background reaper; the HTTP transport and its
        // permission/404 behaviour are covered by the other tests in this class.
        Question paramInput = new Question(
                "qparam",
                "Need params",
                null,
                false,
                0L,
                "params needed",
                null,
                JOB,
                1,
                "tester",
                System.currentTimeMillis(),
                true);
        // A bridged native input WITH parameters is mirrored with no choices and no free text — the
        // modal cannot answer it, so questionJson must hand the client the build's input page URL.
        JSONObject qParam = asUser("builder", () -> ApiRootAction.questionJson(paramInput, true));
        assertTrue(qParam.getJSONArray("choices").isEmpty(), "a parameterized bridged input has no choices");
        assertTrue(qParam.has("forwardUrl"), "the modal needs a forward URL to the input page: " + qParam);
        assertTrue(qParam.getString("forwardUrl").endsWith("/input/"), qParam.getString("forwardUrl"));

        // A bridged input that CAN be answered in-modal (a proceed choice) must NOT get a forward URL.
        Question proceedInput = new Question(
                "qproceed",
                "Proceed?",
                List.of(new Choice("__proceed__", "Approve / Proceed")),
                false,
                0L,
                null,
                null,
                JOB,
                1,
                "tester",
                System.currentTimeMillis(),
                true);
        JSONObject qProceed = asUser("builder", () -> ApiRootAction.questionJson(proceedInput, true));
        assertFalse(qProceed.has("forwardUrl"), "an answerable bridged input needs no forward URL");
    }

    // ---- helpers ----

    /** Evaluate {@code body} as {@code userId}, so a server-side call sees the same identity a request would. */
    private static <T> T asUser(String userId, java.util.function.Supplier<T> body) {
        try (ACLContext ignored = ACL.as2(User.getById(userId, true).impersonate2())) {
            return body.get();
        }
    }

    private static void secure(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        // "outsider" has Overall/Read but no Item.READ, to exercise the scoped-endpoint no-leak 404.
        // "mallory" is a second builder used to exercise lock-to-build-starter: she holds Item.BUILD but
        // is not the starter of the seeded build, so the lock (not a missing permission) blocks her.
        auth.grant(Jenkins.READ).everywhere().to("reader", "builder", "admin", "outsider", "mallory");
        auth.grant(Item.READ).everywhere().to("reader", "builder", "mallory");
        auth.grant(Item.BUILD).everywhere().to("builder", "mallory");
        auth.grant(Jenkins.ADMINISTER).everywhere().to("admin");
        j.jenkins.setAuthorizationStrategy(auth);
        j.createFreeStyleProject(JOB);
    }

    private static void submit(String id) {
        submit(id, "tester");
    }

    private static void submit(String id, String startedBy) {
        QuestionStore.get()
                .submit(new Question(
                        id,
                        "Approve?",
                        List.of(new Choice("yes", "Yes")),
                        false,
                        0L,
                        null,
                        null,
                        JOB,
                        1,
                        startedBy,
                        System.currentTimeMillis(),
                        false));
    }

    /** Seed a WAITING question that declares native input-style parameters (a string + a choice). B24. */
    private static void submitParams(String id) {
        List<ParameterDefinition> params = List.of(
                new StringParameterDefinition("ENV", "dev", "target environment"),
                new ChoiceParameterDefinition("TIER", new String[] {"a", "b"}, "tier"));
        QuestionStore.get()
                .submit(new Question(
                        id,
                        "Provide values",
                        null,
                        false,
                        0L,
                        null,
                        null,
                        JOB,
                        1,
                        "tester",
                        System.currentTimeMillis(),
                        false,
                        params));
    }

    private static WebResponse get(JenkinsRule.WebClient wc, JenkinsRule j, String path) throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        // Follow redirects like a real HTTP client: collection nodes emit a trailing-slash 302.
        wc.getOptions().setRedirectEnabled(true);
        return wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET))
                .getWebResponse();
    }

    private static int postJson(JenkinsRule.WebClient wc, JenkinsRule j, String path, String body) throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(false);
        WebRequest crumbReq = new WebRequest(new URL(j.getURL(), "crumbIssuer/api/json"), HttpMethod.GET);
        JSONObject crumb =
                JSONObject.fromObject(wc.getPage(crumbReq).getWebResponse().getContentAsString());
        WebRequest req = new WebRequest(new URL(j.getURL(), path), HttpMethod.POST);
        req.setAdditionalHeader(crumb.getString("crumbRequestField"), crumb.getString("crumb"));
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(body);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    private static JSONObject json(WebResponse r) {
        return JSONObject.fromObject(r.getContentAsString());
    }
}
