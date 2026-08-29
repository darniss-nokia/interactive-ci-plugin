// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * UI regression guards for the shared dialog re-architecture:
 *
 * <ul>
 *   <li><b>B8</b> — every open uses core's native {@code <dialog class="jenkins-dialog">} shell.
 *   <li><b>B1</b> — the run/build audit page opens the <em>actionable</em> dialog (with the answer form)
 *       for a still-WAITING question, and stays read-only once the question has settled.
 *   <li><b>B6</b> — the console link opens that question's dialog <em>in place</em> (its
 *       {@code data-ii-open} attribute is intercepted by {@code bell.js} on the Console Output page),
 *       and the deep-link ({@code .../interactive-input/?open=<id>}) still auto-opens it on load.
 *   <li><b>B26</b> — an actionable dialog offers the answerer both reject outcomes: a destructive
 *       <em>Deny</em> (abort) and a neutral <em>Skip</em> (resume without approving), plus a note that
 *       spells out that Deny aborts the build.
 * </ul>
 *
 * <p>These drive the real {@code bell.js} through HtmlUnit. The run action attaches to a build only once
 * that build has a question (see {@link PerProjectSurfacesTest}), so each test builds once and then
 * submits a question for build #1 before visiting the per-build audit page.
 */
@WithJenkins
class NativeDialogRunPageTest {

    private static final String JOB = "native-dialog-job";

    private static Question waiting(String id, String jobName, int build) {
        // A single-choice question with context, so the actionable form has a radio + Answer button and
        // the dialog body is non-trivial. No submitter filter => answerable/visible to everyone.
        return new Question(
                id,
                "Approve deploy to prod?",
                List.of(new Choice("yes", "Yes, ship it"), new Choice("no", "No, hold")),
                false,
                0L,
                "Deploying **v2** to prod.",
                null,
                jobName,
                build,
                null,
                System.currentTimeMillis(),
                false);
    }

    private static JenkinsRule.WebClient jsClient(JenkinsRule j) {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(true);
        wc.getOptions().setThrowExceptionOnScriptError(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc;
    }

    @Test
    void runPageOpensActionableNativeDialogForWaitingQuestion(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(JOB);
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore.get().submit(waiting("wq1", p.getFullName(), b.getNumber()));

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = wc.goTo("job/" + JOB + "/" + b.getNumber() + "/interactive-input/");
            wc.waitForBackgroundJavaScript(3000); // audit widget fetches + renders the row

            HtmlElement row = (HtmlElement) page.querySelector(".interactive-input-widget .ii-item");
            assertNotNull(row, "the audit widget must render a clickable row for the waiting question");
            row.click();
            wc.waitForBackgroundJavaScript(3000); // openQuestion fetches detail, then opens the dialog

            // B8: native shell.
            assertNotNull(
                    page.querySelector("dialog.jenkins-dialog.ii-dialog"),
                    "the question must open in core's native <dialog class=jenkins-dialog>");
            // B1: actionable (answer form present), not the read-only audit view.
            assertNotNull(
                    page.querySelector("dialog.ii-dialog form.ii-form"),
                    "a WAITING question opened from the run page must be actionable (answer form present)");
        }
    }

    @Test
    void runPageKeepsSettledQuestionReadOnly(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(JOB + "-settled");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore store = QuestionStore.get();
        store.submit(waiting("sq1", p.getFullName(), b.getNumber()));
        store.abort("sq1", "alice", "test"); // settle it -> ABORTED

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = wc.goTo("job/" + JOB + "-settled/" + b.getNumber() + "/interactive-input/");
            wc.waitForBackgroundJavaScript(3000);

            HtmlElement row = (HtmlElement) page.querySelector(".interactive-input-widget .ii-item");
            assertNotNull(row, "the audit widget must still list a settled question");
            row.click();
            wc.waitForBackgroundJavaScript(3000);

            assertNotNull(
                    page.querySelector("dialog.jenkins-dialog.ii-dialog"),
                    "a settled question still opens in the native dialog");
            assertNotNull(
                    page.querySelector("dialog.ii-dialog .ii-audit-outcome"),
                    "a settled question must render the read-only outcome");
            assertNull(
                    page.querySelector("dialog.ii-dialog form.ii-form"),
                    "a settled question must NOT expose the actionable answer form");
        }
    }

    @Test
    void consoleDeepLinkAutoOpensDialogOnLoad(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(JOB + "-deeplink");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore.get().submit(waiting("dq1", p.getFullName(), b.getNumber()));

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            // The ?open=<id> param is exactly what AskInteractiveStepExecution now writes into the console
            // hyperlink; bell.js must auto-open that question's dialog with no click.
            HtmlPage page = wc.goTo("job/" + JOB + "-deeplink/" + b.getNumber() + "/interactive-input/?open=dq1");
            wc.waitForBackgroundJavaScript(5000); // crumb load -> deep-link fetch -> detail fetch -> open

            assertNotNull(
                    page.querySelector("dialog.jenkins-dialog.ii-dialog"),
                    "the deep-link must auto-open the native dialog");
            assertNotNull(
                    page.querySelector("dialog.ii-dialog form.ii-form"),
                    "the deep-linked WAITING question must open actionable, with no extra click");
        }
    }

    @Test
    void runPageWaitingDialogOffersDenyAndSkip(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(JOB + "-deny");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore.get().submit(waiting("dwq1", p.getFullName(), b.getNumber()));

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = wc.goTo("job/" + JOB + "-deny/" + b.getNumber() + "/interactive-input/");
            wc.waitForBackgroundJavaScript(3000);
            HtmlElement row = (HtmlElement) page.querySelector(".interactive-input-widget .ii-item");
            assertNotNull(row, "the audit widget must render a clickable row for the waiting question");
            row.click();
            wc.waitForBackgroundJavaScript(3000);

            assertNotNull(
                    page.querySelector("dialog.ii-dialog form.ii-form"),
                    "a WAITING question must open the actionable form");
            // B26 + rename: the reject controls give the answerer both outcomes — a destructive Deny and a
            // neutral Skip (was "Continue"; now returns the "__skip__" marker to match its label).
            List<DomNode> buttons = page.querySelectorAll("dialog.ii-dialog .ii-actions button");
            Set<String> labels = buttons.stream()
                    .map(n -> ((HtmlElement) n).asNormalizedText().trim())
                    .collect(Collectors.toSet());
            assertTrue(labels.contains("Answer"), () -> "buttons=" + labels);
            assertTrue(labels.contains("Skip"), () -> "buttons=" + labels);
            assertTrue(labels.contains("Deny"), () -> "buttons=" + labels);
            HtmlElement deny = buttons.stream()
                    .map(n -> (HtmlElement) n)
                    .filter(bt -> "Deny".equals(bt.asNormalizedText().trim()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(deny, "Deny button present");
            assertTrue(
                    deny.getAttribute("class").contains("jenkins-!-destructive-color"),
                    "Deny must be the destructive (abort) button; class=" + deny.getAttribute("class"));
            // Item 4: the dialog must spell out that Deny aborts the build.
            HtmlElement denyNote = (HtmlElement) page.querySelector("dialog.ii-dialog .ii-deny-note");
            assertNotNull(denyNote, "the dialog must carry the deny-abort note");
            assertTrue(
                    denyNote.asNormalizedText().contains("Deny will abort the build"),
                    () -> "deny note=" + denyNote.asNormalizedText());
        }
    }

    @Test
    void consoleLinkOpensDialogInPlaceOnConsolePage(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, JOB + "-console");
        p.setDefinition(new CpsFlowDefinition("askInteractive(prompt: 'Deploy?')", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        Question q = awaitWaiting(j);

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage console = wc.goTo("job/" + JOB + "-console/" + b.getNumber() + "/console");
            // The pipeline console loads progressively; poll for the annotated link to render.
            HtmlElement link = null;
            for (int i = 0; i < 40 && link == null; i++) {
                wc.waitForBackgroundJavaScript(500);
                link = (HtmlElement) console.querySelector("a.ii-console-open[data-ii-open]");
            }
            assertNotNull(link, "B6: the console line must render an in-place open link (data-ii-open)");
            assertEquals(q.getId(), link.getAttribute("data-ii-open"));

            // Clicking opens the actionable dialog IN PLACE on the console page (bell.js intercepts
            // data-ii-open links); there is no navigation to the audit page first.
            link.click();
            wc.waitForBackgroundJavaScript(5000);
            assertNotNull(
                    console.querySelector("dialog.jenkins-dialog.ii-dialog form.ii-form"),
                    "the console link must open the actionable dialog in place on the console page");
        } finally {
            QuestionStore.get().abort(q.getId(), "test", "test"); // release the paused build for teardown
            j.waitForCompletion(b);
        }
    }

    private static Question awaitWaiting(JenkinsRule j) throws InterruptedException {
        QuestionStore store = QuestionStore.get();
        for (int i = 0; i < 100; i++) {
            List<Question> all = store.listAll();
            if (all.size() == 1) {
                return all.get(0);
            }
            Thread.sleep(100L);
        }
        fail("no WAITING question appeared in the store");
        throw new AssertionError("unreachable");
    }
}
