// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression guard for the multi-question "series" modal discarding a half-typed answer when the user
 * pages to another question and back.
 *
 * <p>Root cause: the pager re-fetched each question's detail and rebuilt the form from scratch on every
 * navigation, so the free-text {@code <textarea>} came back empty and any unsubmitted text was lost.
 * The fix snapshots per-question drafts before navigating and restores them, rendering each slide from
 * the already-fetched list item instead of re-fetching.
 *
 * <p>Drives the real UI in HtmlUnit through one entry point to the series pager: a build-history badge
 * for a single build that has more than one waiting question. (Every other surface reaches the same pager
 * — see {@link SeriesEntryPointsTest}.) The badge is normally emitted by the async build-history widget,
 * which is out of scope here, so the test injects one and clicks it; the click is handled by the
 * document-level delegated handler exactly as it is for a real badge. It then types into slide 1, pages to
 * slide 2 and back, and asserts the draft survived.
 */
@WithJenkins
class SeriesDraftRetentionTest {

    private static final String JOB = "series-job";
    private static final String DRAFT = "canary 10 -> 50 -> 100, rollback on 5xx";

    @Test
    void freeTextDraftSurvivesPagingBetweenSeriesQuestions(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig cfg = InteractiveInputAppearanceConfig.get();
        assertNotNull(cfg, "appearance config must be registered");
        // Turning the centre on guarantees a config mount (the bell) so bell.js discovers rootUrl; the
        // series pager itself is reached via the injected badge, not the bell.
        cfg.setNotificationCentre(true);

        j.createFreeStyleProject(JOB);
        QuestionStore store = QuestionStore.get();
        long now = System.currentTimeMillis();
        // Two free-text questions (no starter => visible/answerable to everyone) on the SAME build, so the
        // build badge opens the series pager and every slide shows a textarea.
        store.submit(new Question(
                "s1", "Deploy note (free text)?", List.of(), true, 0L, null, null, JOB, 1, null, now, false));
        store.submit(new Question(
                "s2", "Rollback note (free text)?", List.of(), true, 0L, null, null, JOB, 1, null, now, false));

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.getOptions().setThrowExceptionOnScriptError(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

            HtmlPage page = wc.goTo("");
            wc.waitForBackgroundJavaScript(3000); // let bell.js boot (DOMContentLoaded) + discover rootUrl

            // Inject the build-history badge for build #1 and click it. The delegated click handler
            // (document-level, wired unconditionally) routes to openBadge, which opens the series pager
            // because the build has more than one waiting question.
            page.executeJavaScript("var a=document.createElement('a');a.id='ii-test-badge';"
                    + "a.setAttribute('data-ii-badge','');a.setAttribute('data-job','" + JOB + "');"
                    + "a.setAttribute('data-build','1');document.body.appendChild(a);");
            ((HtmlElement) page.querySelector("#ii-test-badge")).click();
            wc.waitForBackgroundJavaScript(2000);

            HtmlTextArea slide1 = (HtmlTextArea) page.querySelector(".ii-dialog .ii-freetext textarea");
            assertNotNull(slide1, "the first series slide must show a free-text field");
            slide1.setText(DRAFT);

            // Page forward to slide 2, then back to slide 1.
            ((HtmlElement) page.querySelector(".ii-series-next")).click();
            wc.waitForBackgroundJavaScript(1000);
            ((HtmlElement) page.querySelector(".ii-series-prev")).click();
            wc.waitForBackgroundJavaScript(1000);

            HtmlTextArea slide1Again = (HtmlTextArea) page.querySelector(".ii-dialog .ii-freetext textarea");
            assertNotNull(slide1Again, "the first slide must render again after paging back");
            assertEquals(DRAFT, slide1Again.getText(), "the typed draft must survive paging away and back");
        }
    }
}
