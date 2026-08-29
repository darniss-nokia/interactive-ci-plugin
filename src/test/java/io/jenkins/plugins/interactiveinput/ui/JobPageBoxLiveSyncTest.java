// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Regression guard for the inline job-page box appearing only after a full browser reload
 * (§per-project surfaces).
 *
 * <p>Previously {@code jobMain.jelly} rendered the box (its {@code [data-ii-widget]} mount) only when
 * {@code pendingCount > 0} at server-render time, so a question that appeared <em>after</em> the page
 * loaded had no mount to poll and reveal it — unlike the always-present sidebar controller, which is
 * why the sidebar/bell updated live but the box did not. The fix always renders the box (gated only on
 * {@code jobPageBox}), starting it hidden with {@code jenkins-hidden} when nothing is pending;
 * {@code bell.js} then reveals/hides it as the polled count changes.
 *
 * <p>JavaScript is disabled here on purpose: we assert the <em>server-rendered</em> DOM hook the poller
 * needs (the always-present box mount) and its hidden/visible state, not the browser timer.
 */
@WithJenkins
class JobPageBoxLiveSyncTest {

    private static final String JOB = "box-job";

    @Test
    void boxMountIsAlwaysPresentAndTogglesHiddenWithPendingCount(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(JOB);
        QuestionStore store = QuestionStore.get();

        // Nothing pending: the box mount must STILL be in the DOM (so the poller can reveal it live),
        // but its wrapper is hidden via jenkins-hidden.
        String empty = jobPageHtml(j, p);
        assertTrue(
                empty.contains("data-ii-widget=\"job\""),
                "the box mount must render even at zero so bell.js can reveal it without a reload");
        assertTrue(empty.contains("ii-jobcard jenkins-hidden"), "with nothing pending the box card starts hidden");

        // A question appears: the same mount is present and the wrapper is no longer hidden.
        store.submit(new Question(
                "bq1",
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

        String pending = jobPageHtml(j, p);
        assertTrue(pending.contains("data-ii-widget=\"job\""), "the box mount renders while a question waits");
        assertFalse(
                pending.contains("ii-jobcard jenkins-hidden"),
                "with a pending question the box card is visible (not jenkins-hidden)");
    }

    private static String jobPageHtml(JenkinsRule j, FreeStyleProject p) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false); // assert server-rendered hooks, not the browser timer
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc.goTo(p.getUrl()).getWebResponse().getContentAsString();
    }
}
