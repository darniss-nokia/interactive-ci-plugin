// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Behavioural (JavaScript-enabled) guard for the left-sidebar "Interactive Input (N)" count updating
 * live — the complement to {@link SidebarLiveCountTest}, which only checks the server-rendered hooks
 * with JavaScript off.
 *
 * <p>Two things are asserted, each a real defect this change fixes:
 * <ul>
 *   <li><b>No duplicate link.</b> Core renders the sidebar link WITHOUT a trailing slash
 *       ({@code …/interactive-input}) while the client builds its expected href WITH one; the client's
 *       href match must be slash-insensitive, otherwise it fails to find the existing link and clones a
 *       second one.</li>
 *   <li><b>Live count.</b> Answering a question and nudging the client (the {@code ii:answered} event
 *       the modals fire) must hide the row without a page reload — the bug was that the poller never
 *       ran on a job page at all, so the number went stale until refresh.</li>
 * </ul>
 */
@WithJenkins
class SidebarLiveUpdateJsTest {

    private static final String JOB = "nav-job";
    private static final String LINK_XPATH = "//a[contains(@href,'/job/" + JOB + "/interactive-input')]";

    @Test
    void sidebarCountUpdatesLiveAndTheLinkIsNotDuplicated(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(JOB); // per-project centre is on by default
        QuestionStore store = QuestionStore.get();
        store.submit(new Question(
                "n1",
                "Approve?",
                List.of(new Choice("yes", "Yes")),
                false,
                0L,
                null,
                null,
                JOB,
                1,
                null,
                System.currentTimeMillis(),
                false));

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.getOptions().setThrowExceptionOnScriptError(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

            HtmlPage page = wc.goTo(p.getUrl());
            wc.waitForBackgroundJavaScript(3000);

            List<?> links = page.getByXPath(LINK_XPATH);
            assertEquals(1, links.size(), "exactly one sidebar link — the client must not clone a duplicate");
            HtmlAnchor link = (HtmlAnchor) links.get(0);
            assertTrue(link.isDisplayed(), "the link is visible while a question is pending");
            assertTrue(
                    link.asNormalizedText().contains("Interactive Input"),
                    "the sidebar keeps the 'Interactive Input' label");
            assertFalse(
                    link.asNormalizedText().contains("Interactive Input (1)"),
                    "the count is a task-icon-badge, not a (N) suffix on the label");
            HtmlElement badge = sidebarBadge(link);
            assertNotNull(badge, "the pending count renders as a native jenkins-badge pill, not '(N)' text");
            assertTrue(
                    badge.getAttribute("class").contains("task-icon-badge"),
                    "the pill sits on the right of the task row via task-icon-badge");
            assertEquals("1", badge.getTextContent().trim(), "the badge shows the pending count");

            // Answer it and fire the same event the modals dispatch after a successful answer.
            store.answer("n1", "yes", null, "tester", "test");
            page.executeJavaScript("document.dispatchEvent(new CustomEvent('ii:answered',{detail:{}}))");
            wc.waitForBackgroundJavaScript(3000);

            List<?> linksAfter = page.getByXPath(LINK_XPATH);
            assertEquals(1, linksAfter.size(), "still exactly one link after the count drops");
            assertFalse(
                    ((HtmlAnchor) linksAfter.get(0)).isDisplayed(),
                    "the row hides itself live once nothing is pending — no reload needed");
        }
    }

    /** The live pill is a sibling of the {@code <a>} on the {@code .task} row, not a child of the link. */
    private static HtmlElement sidebarBadge(HtmlAnchor link) {
        HtmlElement row = link.getFirstByXPath(
                "./ancestor::*[contains(concat(' ', normalize-space(@class), ' '), ' task ')][1]");
        if (row == null) {
            row = (HtmlElement) link.getParentNode();
        }
        HtmlElement badge = row.querySelector(".ii-task-badge");
        if (badge == null) {
            badge = row.querySelector(".task-icon-badge");
        }
        return badge;
    }
}
