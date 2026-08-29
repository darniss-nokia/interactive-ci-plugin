// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.config.Features;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Drives the real {@code bell.js} through HtmlUnit to guard the B5 auto-open dialog. The auto-open
 * controller ({@code [data-ii-autopopup]}) is emitted by the {@code NotificationBell} page decorator
 * (footer.jelly) on the build's <em>Console Output</em> page only — never on the build main page.
 *
 * <p>Covers: landing on a waiting build's console page auto-opens the actionable dialog with no click
 * (mode A, the default); the build main page does <em>not</em> auto-open (the controller isn't there); a
 * settled build does not (answerable-only); and — the important footgun guard — with the rich modal off
 * the auto-open is suppressed entirely (never navigates to the native input page on load). Auto-open is
 * independent of the per-pipeline box property, so these builds do not enable it.
 */
@WithJenkins
class RunPageAutoPopupTest {

    private static Question waiting(String id, String job, int build) {
        return new Question(
                id,
                "Approve deploy to prod?",
                List.of(new Choice("yes", "Yes, ship it"), new Choice("no", "No, hold")),
                false,
                0L,
                "Deploying **v2** to prod.",
                null,
                job,
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
    void autoOpensActionableDialogOnConsoleWhenWaiting(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("ap-open");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore.get().submit(waiting("aq1", p.getFullName(), b.getNumber()));

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = wc.goTo("job/ap-open/" + b.getNumber() + "/console");
            wc.waitForBackgroundJavaScript(5000); // crumb -> auto-open fetch -> detail fetch -> open

            assertNotNull(
                    page.querySelector("dialog.jenkins-dialog.ii-dialog form.ii-form"),
                    "landing on a waiting build's console page must auto-open the actionable dialog with no click");
        }
    }

    @Test
    void doesNotAutoOpenOnBuildMainPage(JenkinsRule j) throws Exception {
        // Fix #2: the auto-open controller is scoped to the console page, so the build's main page must
        // never auto-open — even while the build is waiting.
        FreeStyleProject p = j.createFreeStyleProject("ap-main");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore.get().submit(waiting("aqm", p.getFullName(), b.getNumber()));

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = wc.goTo("job/ap-main/" + b.getNumber() + "/");
            wc.waitForBackgroundJavaScript(5000);

            assertNull(
                    page.querySelector("dialog.ii-dialog"),
                    "the build main page must NOT auto-open a dialog (auto-open is console-only)");
        }
    }

    @Test
    void doesNotAutoOpenForSettledQuestion(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("ap-settled");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore store = QuestionStore.get();
        store.submit(waiting("aq2", p.getFullName(), b.getNumber()));
        store.abort("aq2", "alice", "test"); // settle it -> nothing waiting

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = wc.goTo("job/ap-settled/" + b.getNumber() + "/console");
            wc.waitForBackgroundJavaScript(5000);

            assertNull(
                    page.querySelector("dialog.ii-dialog"),
                    "a settled build must not auto-open a dialog (answerable-only)");
        }
    }

    @Test
    void doesNotAutoOpenOrNavigateWhenRichModalOff(JenkinsRule j) throws Exception {
        // With the rich modal off, openQuestion navigates to the native /input/ page — so auto-open on
        // load MUST be suppressed, otherwise every waiting console page would redirect itself.
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        Features f = cfg.getFeatures();
        f.setRichModal(false);
        cfg.setFeatures(f);

        FreeStyleProject p = j.createFreeStyleProject("ap-nomodal");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore.get().submit(waiting("aq3", p.getFullName(), b.getNumber()));

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = wc.goTo("job/ap-nomodal/" + b.getNumber() + "/console");
            wc.waitForBackgroundJavaScript(5000);

            assertNull(
                    page.querySelector("dialog.ii-dialog"),
                    "with the rich modal off the console page must NOT auto-open a dialog");
            assertTrue(
                    page.getUrl().getPath().endsWith("/console"),
                    "auto-open must not navigate to /input/ when the rich modal is off; url=" + page.getUrl());
        }
    }
}
