// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The global notification centre must attribute an {@code interactiveView} review to whoever started its
 * triggering build ("started by &lt;user&gt;"), matching the existing behaviour for {@code askInteractive}
 * questions. Drives the real {@code bell.js} through HtmlUnit: the review row in the bell dropdown must
 * carry the starter, read from the review's {@code createdBy} (set server-side from the build cause).
 */
@WithJenkins
class NotificationBellReviewRowJsTest {

    @Test
    void bellReviewRowShowsWhoStartedTheBuild(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig.get().setNotificationCentre(true); // the global bell is off by default

        FreeStyleProject p = j.createFreeStyleProject("bell-review");
        FreeStyleBuild b = j.buildAndAssertSuccess(p); // build #1 exists
        ViewStore.get().submit(review("brv", p.getFullName(), b.getNumber(), "alice"), "content");

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.getOptions().setThrowExceptionOnScriptError(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

            HtmlPage dashboard = wc.goTo("");
            wc.waitForBackgroundJavaScript(3000); // let the initial poll populate the reviews cache

            HtmlElement bell = (HtmlElement) dashboard.querySelector(".ii-bell-btn");
            assertTrue(bell != null, "the notification bell must mount on the dashboard");
            bell.click();
            wc.waitForBackgroundJavaScript(3000); // render the dropdown from the populated cache

            boolean startedByShown = false;
            for (DomNode node : dashboard.querySelectorAll(".ii-dropdown .ii-item-by")) {
                if (node.getTextContent() != null && node.getTextContent().contains("started by alice")) {
                    startedByShown = true;
                    break;
                }
            }
            assertTrue(startedByShown, "the review row must show 'started by alice' in the notification centre");
        }
    }

    private static ReviewDocument review(String id, String job, int build, String createdBy) {
        return new ReviewDocument(
                id,
                job,
                build,
                "Report",
                "Title",
                "file",
                ReviewDocument.FORMAT_TEXT,
                "text",
                createdBy,
                System.currentTimeMillis(),
                true,
                false,
                true,
                false,
                null,
                0L,
                null,
                null);
    }
}
