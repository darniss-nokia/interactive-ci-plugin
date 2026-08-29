// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputRunPageAlertJobProperty;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Server-render guards for the B5 run-page surfaces. JavaScript is disabled on purpose: we assert the
 * server-rendered DOM hooks {@code bell.js} needs, not the browser timer — mirroring
 * {@link JobPageBoxLiveSyncTest}.
 *
 * <p>Three surfaces are involved once a build has interactive-input questions (the run action attaches):
 *
 * <ul>
 *   <li>the live sidebar-count controller {@code [data-ii-tasklink="build"]} — emitted on every run
 *       sub-page by the {@code NotificationBell} decorator so the side link's count badge stays live
 *       (fix #1, parity with the job page);
 *   <li>the auto-open controller {@code [data-ii-autopopup]} — emitted by the decorator on the
 *       <em>Console Output</em> page only (fix #2), never on the build main page; and
 *   <li>the attention box row {@code [data-ii-runbox]} in {@code summary.jelly} — rendered only when the
 *       per-pipeline "alert user on run page" property is enabled, starting hidden (jenkins-hidden)
 *       unless the build is waiting so {@code bell.js} can reveal/hide it live without a reload.
 * </ul>
 */
@WithJenkins
class RunPageAttentionBoxTest {

    private static Question waiting(String id, String job, int build) {
        return new Question(
                id,
                "Approve?",
                List.of(new Choice("yes", "Yes")),
                false,
                0L,
                null,
                null,
                job,
                build,
                "tester",
                System.currentTimeMillis(),
                false);
    }

    @Test
    void runPageEmitsLiveSidebarCount_notAutoOpen_andBoxGatedOnProperty(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("rp-gate");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore.get().submit(waiting("rq1", p.getFullName(), b.getNumber()));

        String withoutProp = runPageHtml(j, p, b);
        // Fix #1: the run page carries the build-scoped live sidebar-count controller, seeded from server.
        assertTrue(
                withoutProp.contains("data-ii-tasklink=\"build\""),
                "the run page must carry the build-scoped live sidebar-count controller");
        assertTrue(
                withoutProp.contains("data-initial-count=\"1\""),
                "the sidebar controller seeds this build's pending count from the server");
        // Fix #2: auto-open is console-only, so it must NOT be emitted on the build main page.
        assertFalse(
                withoutProp.contains("data-ii-autopopup"),
                "the auto-open controller must not render on the build main page (console-only)");
        // The attention box is still gated on the per-pipeline property.
        assertFalse(
                withoutProp.contains("data-ii-runbox=\"build\""),
                "the attention box must NOT render until the per-pipeline property is enabled");

        p.addProperty(new InteractiveInputRunPageAlertJobProperty());
        String withProp = runPageHtml(j, p, b);
        assertTrue(
                withProp.contains("data-ii-runbox=\"build\""),
                "with the property enabled the attention box row renders");
    }

    @Test
    void consolePageEmitsAutoOpenAndSidebarControllers(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("rp-console");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore.get().submit(waiting("rq3", p.getFullName(), b.getNumber()));

        String console = consolePageHtml(j, p, b);
        assertTrue(
                console.contains("data-ii-autopopup=\"build\""),
                "the auto-open controller must render on the Console Output page (fix #2)");
        assertTrue(
                console.contains("data-ii-tasklink=\"build\""),
                "the live sidebar count also renders on the console page (the run sidebar is shared)");
    }

    @Test
    void boxRowVisibleWhileWaitingHiddenOnceSettled(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("rp-hide");
        p.addProperty(new InteractiveInputRunPageAlertJobProperty());
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore store = QuestionStore.get();
        store.submit(waiting("rq2", p.getFullName(), b.getNumber()));

        String whileWaiting = runPageHtml(j, p, b);
        assertTrue(whileWaiting.contains("data-ii-runbox=\"build\""), "box mount present while waiting");
        assertFalse(
                whileWaiting.contains("ii-runbox-row jenkins-hidden"),
                "while waiting the box row is visible (not jenkins-hidden)");

        store.abort("rq2", "alice", "test"); // settle -> not waiting, but the build still has a question
        String afterSettled = runPageHtml(j, p, b);
        assertTrue(afterSettled.contains("data-ii-runbox=\"build\""), "box mount still present when settled");
        assertTrue(
                afterSettled.contains("ii-runbox-row jenkins-hidden"),
                "once settled the box row starts hidden so bell.js can reveal it live on the next question");
    }

    private static String runPageHtml(JenkinsRule j, FreeStyleProject p, FreeStyleBuild b) throws Exception {
        return pageHtml(j, p.getUrl() + b.getNumber() + "/");
    }

    private static String consolePageHtml(JenkinsRule j, FreeStyleProject p, FreeStyleBuild b) throws Exception {
        return pageHtml(j, p.getUrl() + b.getNumber() + "/console");
    }

    private static String pageHtml(JenkinsRule j, String url) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false); // assert server-rendered hooks, not the browser timer
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc.goTo(url).getWebResponse().getContentAsString();
    }
}
