// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression guard for the left-sidebar "Interactive Input (N)" count going stale until a full page
 * reload (§side-nav live update).
 *
 * <p>The sidebar link is server-rendered by {@link InteractiveInputJobAction} (label + icon) and the
 * pending count is a native {@code jenkins-badge} pill added client-side, so the number only changed on
 * reload. The fix keeps it live by always rendering an invisible {@code [data-ii-tasklink]} controller
 * in {@code jobMain.jelly} (even at zero) that {@code bell.js} uses to update the badge and hide/show
 * the row as the scoped count changes.
 *
 * <p>JavaScript is disabled here on purpose: we assert the <em>server-rendered</em> DOM hooks the poller
 * needs (the always-present controller with the correct {@code data-job}/{@code data-initial-count}) and
 * the count contract the poller applies, rather than trying to drive the browser timer in a unit test.
 */
@WithJenkins
class SidebarLiveCountTest {

    private static final String JOB = "sidebar-job";

    @Test
    void sidebarCountHasLiveControllerAndReflectsStoreChanges(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(JOB);
        QuestionStore store = QuestionStore.get();

        InteractiveInputJobAction action = p.getAction(InteractiveInputJobAction.class);
        assertEquals(0, action.getPendingCount(), "nothing pending initially");
        assertNull(action.getIconFileName(), "no sidebar link while nothing is pending");

        store.submit(new Question(
                "sq1",
                "Approve?",
                List.of(new Choice("yes", "Yes")),
                false,
                0L,
                null,
                null,
                JOB,
                1,
                "tester",
                System.currentTimeMillis(),
                false));

        // With one pending, the page must carry the always-present live controller AND the server-rendered
        // sidebar link. The label now carries the "(1)" count (so the same value shows in the experimental
        // "more actions" overflow menu); with JavaScript disabled bell.js has not run, so the raw server
        // label is visible here. In the live classic sidebar bell.js resets it to a plain label + a
        // jenkins-badge pill, so there is no double count.
        String pending = jobPageHtml(j, p);
        assertTrue(pending.contains("data-ii-tasklink=\"job\""), "always-present tasklink controller must render");
        assertTrue(pending.contains("data-job=\"" + JOB + "\""), "controller must be scoped to this job");
        assertTrue(pending.contains("data-initial-count=\"1\""), "controller seeds the live count from the server");
        assertTrue(
                pending.contains("Interactive Input (1)"),
                "server-rendered label carries the count; bell.js resets it to a plain label + pill in the classic sidebar");

        // Draining the store is exactly what the client poller observes: count -> 0, link hidden. The
        // controller stays in the DOM (seeded at 0) so the poller can re-show it if a new question arrives.
        store.answer("sq1", "yes", null, "tester", "test");
        assertEquals(0, action.getPendingCount());
        assertNull(action.getIconFileName(), "link hides itself once the count reaches zero");

        String drained = jobPageHtml(j, p);
        assertTrue(drained.contains("data-ii-tasklink=\"job\""), "controller stays present at zero for live re-show");
        assertTrue(drained.contains("data-initial-count=\"0\""), "controller reseeds at zero");
        assertFalse(drained.contains("Interactive Input (1)"), "at zero the label carries no count suffix");
    }

    private static String jobPageHtml(JenkinsRule j, FreeStyleProject p) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false); // assert server-rendered hooks, not the browser timer
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc.goTo(p.getUrl()).getWebResponse().getContentAsString();
    }
}
