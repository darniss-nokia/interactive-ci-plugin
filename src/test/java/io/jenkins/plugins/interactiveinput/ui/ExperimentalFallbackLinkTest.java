// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.output.InteractiveOutputJobAction;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Experimental-layout reachability + overflow-menu count (Req 4 &amp; 5).
 *
 * <p>Earlier revisions emitted a hidden {@code [data-ii-exp-link]} fallback anchor from each action's
 * {@code jobMain.jelly}, revealed by {@code bell.js} when the classic {@code #tasks} sidebar was absent.
 * Under the experimental job layout that anchor landed in core's "Legacy" card, producing a <em>duplicate</em>
 * "Interactive View" entry (and {@code bell.js} could duplicate its label text). It has been removed:
 * reachability in the experimental layout is provided by core's native "more actions" overflow menu, which
 * lists any {@link hudson.model.Action} that exposes an icon + display name.
 *
 * <p>This test guards both halves so they cannot silently regress:
 * <ul>
 *   <li>#4 — no {@code [data-ii-exp-link]} anchor is ever rendered on the job page (no duplicate/Legacy
 *       link) for either the view or the output action;</li>
 *   <li>#4 — each job action still exposes an icon + stable URL once the job has something to show, so core
 *       surfaces it (classic sidebar and experimental overflow menu alike);</li>
 *   <li>#5 — {@link InteractiveViewJobAction#getDisplayName()} and
 *       {@link InteractiveInputJobAction#getDisplayName()} both carry the pending {@code (N)} count, which is
 *       exactly what the experimental overflow menu renders.</li>
 * </ul>
 */
@WithJenkins
class ExperimentalFallbackLinkTest {

    @Test
    void jobPageRendersNoFallbackLink(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("vjob");
        ViewStore.get().submit(review(p.getFullName()), "hello");

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(false); // assert the pure server render
            assertNull(
                    wc.goTo(p.getUrl()).querySelector("a[data-ii-exp-link]"),
                    "the redundant experimental fallback link must no longer be rendered");
        }
    }

    @Test
    void outputJobPageRendersNoFallbackLinkButStaysReachable(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "ojob");
        p.setDefinition(new CpsFlowDefinition(
                "interactiveOutput(reportName: 'Cost', chartType: 'bar', metrics: [[label:'C', value:'1', key:'c']])",
                true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(false);
            assertNull(
                    wc.goTo(p.getUrl()).querySelector("a[data-ii-exp-link]"),
                    "the output fallback link must no longer be rendered");
        }

        // Reachability is now via core's native surfaces: the action exposes an icon + stable URL.
        InteractiveOutputJobAction action = p.getAction(InteractiveOutputJobAction.class);
        assertNotNull(action, "the output job action is attached when the feature is on");
        assertNotNull(action.getIconFileName(), "icon present once a build has output — core surfaces the action");
        assertEquals("interactive-output", action.getUrlName());
    }

    @Test
    void viewJobActionIsReachableViaIconAndUrl(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("vjob2");
        InteractiveViewJobAction action = p.getAction(InteractiveViewJobAction.class);
        assertNotNull(action, "the view job action is attached when the feature is on");

        // No reviews yet → no icon, so core renders no sidebar/menu entry.
        assertNull(action.getIconFileName(), "no icon until the job has a readable review");

        ViewStore.get().submit(review(p.getFullName()), "hello");
        assertNotNull(action.getIconFileName(), "icon appears once a review exists — core surfaces the action");
        assertEquals("interactive-view", action.getUrlName());
    }

    @Test
    void viewDisplayNameCarriesPendingCountForOverflowMenu(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("vjob3");
        InteractiveViewJobAction action = p.getAction(InteractiveViewJobAction.class);
        assertNotNull(action);

        // Nothing pending → plain label (no "(0)").
        assertEquals(0, action.getPendingCount());
        assertEquals("Interactive View", action.getDisplayName());

        // One OPEN, notify-enabled review → the count is suffixed so the experimental overflow menu shows it.
        ViewStore.get().submit(review(p.getFullName()), "hello");
        assertEquals(1, action.getPendingCount());
        assertEquals("Interactive View (1)", action.getDisplayName());
    }

    @Test
    void inputDisplayNameCarriesPendingCountForOverflowMenu(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("ijob");
        InteractiveInputJobAction action = p.getAction(InteractiveInputJobAction.class);
        assertNotNull(action);

        // Nothing pending → plain label (no "(0)").
        assertEquals(0, action.getPendingCount());
        assertEquals("Interactive Input", action.getDisplayName());

        // One pending question → the count is suffixed so the experimental overflow menu shows it,
        // consistent with Interactive View.
        QuestionStore.get()
                .submit(new Question(
                        "ijob-q1",
                        "Approve?",
                        List.of(new Choice("yes", "Yes")),
                        false,
                        0L,
                        null,
                        null,
                        p.getFullName(),
                        1,
                        "tester",
                        System.currentTimeMillis(),
                        false));
        assertEquals(1, action.getPendingCount());
        assertEquals("Interactive Input (1)", action.getDisplayName());
    }

    private static ReviewDocument review(String jobFullName) {
        return new ReviewDocument(
                "rev-" + jobFullName,
                jobFullName,
                1,
                "Report",
                "Title",
                "file.txt",
                ReviewDocument.FORMAT_TEXT,
                "text",
                "alice",
                System.currentTimeMillis(),
                true, // commentable
                false, // editable
                true, // notify
                false, // blocking
                null, // submitterFilter
                0L, // slaMs
                null, // groupId
                null); // mode
    }
}
