// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Behavioural (JavaScript-enabled) guard for fix #1: the run page's left-sidebar "Interactive Input"
 * link must carry a live pending-count badge, exactly like the job page does — the observation was that
 * the badge showed on the job page but not the run page.
 *
 * <p>The complement to {@link SidebarLiveUpdateJsTest} (which covers the job-scoped link): here the
 * build-scoped {@code [data-ii-tasklink="build"]} controller emitted by the {@code NotificationBell}
 * decorator must find the run's side link ({@code …/<n>/interactive-input}) and badge it with the count
 * of that build's WAITING questions, then hide it live once the build has nothing pending — no reload.
 */
@WithJenkins
class RunPageSidebarCountJsTest {

    private static final String JOB = "rp-nav";
    private static final String LINK_XPATH = "//a[contains(@href,'/job/" + JOB + "/1/interactive-input')]";

    @Test
    void runPageSidebarShowsLiveBuildCount(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(JOB); // per-project centre is on by default
        FreeStyleBuild b = j.buildAndAssertSuccess(p); // build #1
        QuestionStore store = QuestionStore.get();
        store.submit(new Question(
                "rn1",
                "Approve?",
                List.of(new Choice("yes", "Yes")),
                false,
                0L,
                null,
                null,
                JOB,
                b.getNumber(),
                null,
                System.currentTimeMillis(),
                false));

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.getOptions().setThrowExceptionOnScriptError(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

            HtmlPage page = wc.goTo("job/" + JOB + "/" + b.getNumber() + "/");
            wc.waitForBackgroundJavaScript(3000);

            List<?> links = page.getByXPath(LINK_XPATH);
            assertEquals(1, links.size(), "exactly one run-page sidebar link — the client must not clone a duplicate");
            HtmlAnchor link = (HtmlAnchor) links.get(0);
            assertTrue(link.asNormalizedText().contains("Interactive Input"), "keeps the 'Interactive Input' label");
            var badge = link.querySelector(".ii-task-badge");
            assertNotNull(badge, "the run page's side link must gain a pending-count badge (fix #1)");
            assertEquals("1", badge.getTextContent().trim(), "the badge shows this build's pending count");

            // Answer it and fire the event the modals dispatch: the badge clears, but — unlike the job
            // link — the per-build AUDIT link must REMAIN visible so past inputs stay reviewable.
            store.answer("rn1", "yes", null, "tester", "test");
            page.executeJavaScript("document.dispatchEvent(new CustomEvent('ii:answered',{detail:{}}))");
            wc.waitForBackgroundJavaScript(3000);

            List<?> after = page.getByXPath(LINK_XPATH);
            assertEquals(1, after.size(), "the per-build audit link must remain in the DOM after settlement");
            HtmlAnchor auditLink = (HtmlAnchor) after.get(0);
            assertTrue(
                    auditLink.isDisplayed(),
                    "the per-build AUDIT link stays visible after the question settles (review past inputs)");
            var settledBadge = auditLink.querySelector(".ii-task-badge");
            assertTrue(
                    settledBadge == null || !settledBadge.isDisplayed(),
                    "the pending-count badge is removed once nothing is waiting");
        }
    }
}
