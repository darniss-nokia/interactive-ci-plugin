// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParameterDefinition;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;
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
 * Drives the real {@code bell.js} through HtmlUnit to guard the design-library adoption:
 *
 * <ul>
 *   <li><b>Item 1</b> — the B24 parameter form renders with core's native design-library control classes
 *       ({@code jenkins-input} for text/textarea/password, {@code jenkins-select}/{@code __input} for a
 *       choice, {@code jenkins-checkbox} for a boolean), verified present on the 2.568 baseline.</li>
 *   <li><b>Item 3</b> — a question with an SLA shows a live countdown in the job-list row plus the native
 *       {@code app-progress-bar}, and a countdown in the opened dialog.</li>
 * </ul>
 *
 * <p>The job action's own page ({@code job/<name>/interactive-input/}) mounts the same job widget the
 * inline job-page card uses, so it is a stable surface to assert the row rendering without depending on
 * the Appearance "job-page box" toggle.
 */
@WithJenkins
class DesignLibraryUiJsTest {

    private static JenkinsRule.WebClient jsClient(JenkinsRule j) {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(true);
        wc.getOptions().setThrowExceptionOnScriptError(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc;
    }

    @Test
    void parameterFormUsesNativeDesignLibraryControls(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("dl-params");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        List<ParameterDefinition> params = List.of(
                new StringParameterDefinition("env", "prod", "target environment"),
                new BooleanParameterDefinition("dryRun", true, "no side effects"),
                new ChoiceParameterDefinition("tier", new String[] {"gold", "silver"}, "service tier"),
                new PasswordParameterDefinition("token", "", "api token"),
                new TextParameterDefinition("notes", "", "free notes"));
        QuestionStore.get()
                .submit(new Question(
                        "dlp1",
                        "Provide inputs",
                        null,
                        false,
                        0L,
                        null,
                        null,
                        p.getFullName(),
                        b.getNumber(),
                        null,
                        System.currentTimeMillis(),
                        false,
                        params));

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = wc.goTo("job/dl-params/interactive-input/");
            wc.waitForBackgroundJavaScript(3000);
            HtmlElement row = (HtmlElement) page.querySelector(".interactive-input-widget .ii-item");
            assertNotNull(row, "the job widget must render a clickable row for the parameter question");
            row.click();
            wc.waitForBackgroundJavaScript(3000);

            assertNotNull(page.querySelector("dialog.ii-dialog fieldset.ii-params"), "parameter fieldset present");
            assertNotNull(
                    page.querySelector(".ii-params input.jenkins-input[type='text']"),
                    "string parameter uses the native jenkins-input class");
            assertNotNull(
                    page.querySelector(".ii-params textarea.jenkins-input"),
                    "text parameter uses the native jenkins-input class");
            assertNotNull(
                    page.querySelector(".ii-params input.jenkins-input[type='password']"),
                    "password parameter uses the native jenkins-input class");
            assertNotNull(
                    page.querySelector(".ii-params .jenkins-select select.jenkins-select__input"),
                    "choice parameter uses the native jenkins-select control");
            assertNotNull(
                    page.querySelector(".ii-params .jenkins-checkbox input[type='checkbox']"),
                    "boolean parameter uses the native jenkins-checkbox control");
        }
    }

    @Test
    void jobListAndDialogShowLiveSlaCountdown(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("dl-sla");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        QuestionStore.get()
                .submit(new Question(
                        "dls1",
                        "Approve deploy?",
                        List.of(new Choice("yes", "Yes")),
                        false,
                        600_000L,
                        null,
                        null,
                        p.getFullName(),
                        b.getNumber(),
                        null,
                        System.currentTimeMillis(),
                        false));

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = wc.goTo("job/dl-sla/interactive-input/");
            wc.waitForBackgroundJavaScript(3000);

            // Item 3 (job box row): a live countdown plus the native progress bar.
            assertNotNull(
                    page.querySelector(".interactive-input-widget .ii-item .ii-countdown .ii-countdown-text"),
                    "the row must show a live SLA countdown");
            assertNotNull(
                    page.querySelector(".interactive-input-widget .ii-item .app-progress-bar.ii-sla-bar"),
                    "the row must show the native app-progress-bar");

            HtmlElement row = (HtmlElement) page.querySelector(".interactive-input-widget .ii-item");
            assertNotNull(row, "row present");
            row.click();
            wc.waitForBackgroundJavaScript(3000);

            // Item 3 (dialog): a live countdown for the waiting question.
            assertNotNull(
                    page.querySelector("dialog.ii-dialog .ii-countdown .ii-countdown-text"),
                    "the dialog must show a live SLA countdown");
        }
    }
}
