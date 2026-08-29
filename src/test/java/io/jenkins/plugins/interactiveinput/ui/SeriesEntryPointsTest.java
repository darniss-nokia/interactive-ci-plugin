// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A build's WAITING questions are one "series" (README: <em>several questions published on the same
 * build at once</em>), and every entry point must reach it as one numbered pager — not one question at
 * a time.
 *
 * <p>Root cause guarded here: the "more than one waiting question on this build → open the pager"
 * decision used to be re-implemented inside each of three call sites (the build-history badge, the
 * run-page box and the console auto-open), so the surfaces that open a <em>named</em> question — the
 * notification bell dropdown, the console {@code data-ii-open} link, the {@code ?open=} deep link, the
 * job-page box and the per-build audit list — bypassed it and always opened a single-question dialog.
 * All of them now route through one shared decision, and the pager opens on the question that was
 * actually clicked.
 *
 * <p>Drives the real {@code bell.js} through HtmlUnit. The console link is exercised through the
 * document-level delegated handler by injecting an anchor carrying {@code data-ii-open} — the same code
 * path a real console line takes (that the note is emitted and intercepted on the Console Output page is
 * already covered by {@link NativeDialogRunPageTest}), which keeps this test off the progressively
 * loading console page.
 */
@WithJenkins
class SeriesEntryPointsTest {

    private static final String JOB = "series-entrypoints-job";

    /** Three questions on the SAME build, submitted out of creation order to also pin the ordering. */
    private static void submitSeries(String job) {
        QuestionStore store = QuestionStore.get();
        long t0 = System.currentTimeMillis() - 30_000L;
        store.submit(question("e2", "Second question", job, t0 + 1_000L));
        store.submit(question("e3", "Third question", job, t0 + 2_000L));
        store.submit(question("e1", "First question", job, t0));
    }

    private static Question question(String id, String prompt, String job, long createdTs) {
        // No submitter filter and no starter => answerable and visible to everyone.
        return new Question(
                id,
                prompt,
                List.of(new Choice("yes", "Yes"), new Choice("no", "No")),
                false,
                0L,
                null,
                null,
                job,
                1,
                null,
                createdTs,
                false);
    }

    private static JenkinsRule.WebClient jsClient(JenkinsRule j) {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(true);
        wc.getOptions().setThrowExceptionOnScriptError(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc;
    }

    /** Open the dashboard bell dropdown and return its question rows. */
    private static HtmlPage openBellDropdown(JenkinsRule.WebClient wc) throws Exception {
        HtmlPage page = wc.goTo("");
        wc.waitForBackgroundJavaScript(3000); // bell.js boots, then the first poll populates the cache
        HtmlElement bell = (HtmlElement) page.querySelector(".ii-bell-btn");
        assertNotNull(bell, "the notification bell must mount on the dashboard");
        bell.click();
        wc.waitForBackgroundJavaScript(3000);
        return page;
    }

    private static String seriesPosition(HtmlPage page) {
        HtmlElement pos = (HtmlElement) page.querySelector("dialog.ii-dialog .ii-series-pos");
        return pos == null ? null : pos.asNormalizedText().trim();
    }

    private static String dialogTitle(HtmlPage page) {
        HtmlElement title = (HtmlElement) page.querySelector("dialog.ii-dialog .jenkins-dialog__title span");
        return title == null ? null : title.asNormalizedText().trim();
    }

    @Test
    void bellDropdownOpensTheWholeSeriesPositionedOnTheClickedQuestion(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig.get().setNotificationCentre(true); // the global bell is off by default
        j.createFreeStyleProject(JOB);
        submitSeries(JOB);

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = openBellDropdown(wc);

            // The dropdown must list the build's questions in the order the pipeline asked them.
            assertEquals(
                    List.of("First question", "Second question", "Third question"),
                    page.querySelectorAll(".ii-dropdown .ii-item-prompt").stream()
                            .map(n -> ((HtmlElement) n).asNormalizedText().trim())
                            .toList(),
                    "the notification centre must list a build's series in creation order");

            // Clicking the SECOND row must open the whole series, paged to that question (2 / 3) — not a
            // lone dialog for it.
            ((HtmlElement) page.querySelectorAll(".ii-dropdown .ii-item").get(1)).click();
            wc.waitForBackgroundJavaScript(5000); // sibling lookup -> pager render

            assertNotNull(
                    page.querySelector("dialog.ii-dialog .ii-series-nav"),
                    "a bell row for a build with several waiting questions must open the series pager");
            assertEquals("2 / 3", seriesPosition(page), "the pager must open on the question that was clicked");
            assertEquals("Second question", dialogTitle(page));
            assertNotNull(
                    page.querySelector("dialog.ii-dialog form.ii-form"), "the slide must still be actionable");
        }
    }

    @Test
    void consoleOpenLinkOpensTheWholeSeriesPositionedOnItsQuestion(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig.get().setNotificationCentre(true); // guarantees a config mount
        j.createFreeStyleProject(JOB);
        submitSeries(JOB);

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = wc.goTo("");
            wc.waitForBackgroundJavaScript(3000); // let bell.js boot and discover rootUrl

            // The console line's anchor: bell.js intercepts any [data-ii-open] click document-wide.
            page.executeJavaScript("var a=document.createElement('a');a.id='ii-test-console-link';"
                    + "a.className='ii-console-open';a.setAttribute('data-ii-open','e3');"
                    + "document.body.appendChild(a);");
            ((HtmlElement) page.querySelector("#ii-test-console-link")).click();
            wc.waitForBackgroundJavaScript(5000); // detail fetch -> sibling lookup -> pager render

            assertNotNull(
                    page.querySelector("dialog.ii-dialog .ii-series-nav"),
                    "a console link on a build with several waiting questions must open the series pager");
            assertEquals("3 / 3", seriesPosition(page), "the pager must open on the linked question");
            assertEquals("Third question", dialogTitle(page));
        }
    }

    @Test
    void aLoneWaitingQuestionStillOpensAPlainDialog(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig.get().setNotificationCentre(true);
        j.createFreeStyleProject(JOB);
        QuestionStore.get().submit(question("solo", "Only question", JOB, System.currentTimeMillis()));

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = openBellDropdown(wc);
            ((HtmlElement) page.querySelector(".ii-dropdown .ii-item")).click();
            wc.waitForBackgroundJavaScript(5000);

            assertNotNull(page.querySelector("dialog.ii-dialog form.ii-form"), "the question must open actionable");
            assertNull(
                    page.querySelector("dialog.ii-dialog .ii-series-nav"),
                    "a build with a single waiting question must NOT gain a pager");
        }
    }
}
