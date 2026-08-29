// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * An HTML review document must be viewable as a <em>rendered</em> report, not only as escaped source.
 *
 * <p>Report generators embed their whole payload in JavaScript — a Robot Framework {@code log.html}
 * degrades to its "JavaScript disabled" error once scripts are stripped — so the document cannot be
 * sanitised into the page the way markdown is. It is instead loaded from {@code /views/{id}/rendered}
 * into an {@code <iframe sandbox="allow-scripts">}: withholding {@code allow-same-origin} gives the frame
 * a unique opaque origin, so the report's scripts run but cannot reach this page, the session cookie or a
 * CSRF crumb.
 *
 * <p>These drive the real {@code viewer.js} through HtmlUnit and assert the client contract: the
 * Rendered/Source toggle is offered, Rendered is the default view, the frame carries the sandbox
 * attribute and points at the endpoint, the document is never inlined into the page, and the whole
 * capability disappears when an operator turns the {@code htmlRendering} feature off.
 */
@WithJenkins
class HtmlRenderViewTest {

    private static final String JOB = "html-render-job";

    /** A self-contained scripted report, like the generated logs this feature exists to display. */
    private static final String SCRIPTED_REPORT = "<!DOCTYPE html><html><head><title>Report</title></head>"
            + "<body><div id=\"c\"></div><script>document.getElementById('c').textContent='generated'</script>"
            + "</body></html>";

    private static JenkinsRule.WebClient jsClient(JenkinsRule j) {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(true);
        wc.getOptions().setThrowExceptionOnScriptError(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc;
    }

    private static ReviewDocument seed(JenkinsRule j, String id, String fileName, String format, String content)
            throws Exception {
        FreeStyleProject p = j.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
        if (p == null) {
            p = j.createFreeStyleProject(JOB);
        }
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        ReviewDocument doc = new ReviewDocument(
                id,
                p.getFullName(),
                b.getNumber(),
                "Report",
                "Robot log",
                fileName,
                format,
                "markup",
                "tester",
                System.currentTimeMillis(),
                true,
                false,
                true,
                false,
                null,
                0L,
                null,
                null);
        return ViewStore.get().submit(doc, content);
    }

    private static HtmlPage openDoc(JenkinsRule.WebClient wc, int build, String id) throws Exception {
        HtmlPage page = wc.goTo("job/" + JOB + "/" + build + "/interactive-view/?doc=" + id);
        wc.waitForBackgroundJavaScript(5000); // viewer.js boots, fetches the detail, renders
        return page;
    }

    @Test
    void anHtmlDocumentRendersInASandboxedFrameByDefault(JenkinsRule j) throws Exception {
        ReviewDocument doc = seed(j, "h1", "log.html", ReviewDocument.FORMAT_HTML, SCRIPTED_REPORT);

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = openDoc(wc, doc.getBuildNumber(), "h1");

            HtmlElement frame = (HtmlElement) page.querySelector("iframe.iv-htmlframe");
            assertNotNull(frame, "an HTML document must open in the sandboxed render frame by default");
            // The isolation contract: scripts may run, but the frame must NOT be same-origin with Jenkins.
            String sandbox = frame.getAttribute("sandbox");
            assertTrue(sandbox.contains("allow-scripts"), "a scripted report needs allow-scripts: " + sandbox);
            assertFalse(
                    sandbox.contains("allow-same-origin"),
                    "allow-same-origin would let the report reach this Jenkins session: " + sandbox);
            assertFalse(sandbox.contains("allow-forms"), "the frame must not be able to submit forms: " + sandbox);
            assertFalse(
                    sandbox.contains("allow-top-navigation"),
                    "the frame must not be able to navigate the reviewer away: " + sandbox);
            assertTrue(
                    frame.getAttribute("src").endsWith("/rendered"),
                    "the frame loads the snapshot from the endpoint, not from this page: " + frame.getAttribute("src"));

            // The report is never inlined into the Jenkins page itself.
            assertNull(page.querySelector(".iv-rendered"), "an HTML snapshot must not be inserted into the page");
            assertFalse(
                    page.asXml().contains("textContent='generated'"),
                    "the report's script must never appear in the review page's own DOM");

            // The reviewer must still be able to act: the decision buttons live in the toolbar, OUTSIDE the
            // isolated frame, so rendering a report never costs the ability to approve it.
            boolean canApprove = page.querySelectorAll(".iv-toolbar button").stream()
                    .anyMatch(n -> "Approve".equals(((HtmlElement) n).asNormalizedText().trim()));
            assertTrue(canApprove, "the decision buttons must remain available alongside the rendered report");
        }
    }

    @Test
    void bothViewsAreOfferedAndSourceStillShowsTheMarkup(JenkinsRule j) throws Exception {
        ReviewDocument doc = seed(j, "h2", "log.html", ReviewDocument.FORMAT_HTML, SCRIPTED_REPORT);

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = openDoc(wc, doc.getBuildNumber(), "h2");

            // Two options, as asked for: the rendered report and the file's own markup.
            assertNotNull(segButton(page, "Rendered"), "the Rendered option must be offered for an HTML document");
            HtmlElement source = segButton(page, "Source");
            assertNotNull(source, "the Source option must remain available for an HTML document");

            source.click();
            wc.waitForBackgroundJavaScript(2000);
            assertNull(page.querySelector("iframe.iv-htmlframe"), "Source must replace the render frame");
            assertNotNull(page.querySelector(".iv-code, .iv-code-block"), "Source must show the escaped markup");
        }
    }

    @Test
    void turningTheFeatureOffLeavesHtmlSourceOnly(JenkinsRule j) throws Exception {
        ReviewDocument doc = seed(j, "h3", "log.html", ReviewDocument.FORMAT_HTML, SCRIPTED_REPORT);
        InteractiveInputGlobalConfig.get().getFeatures().setHtmlRendering(false);

        try (JenkinsRule.WebClient wc = jsClient(j)) {
            HtmlPage page = openDoc(wc, doc.getBuildNumber(), "h3");

            assertNull(page.querySelector("iframe.iv-htmlframe"), "no render frame when the feature is off");
            // Note: .iv-seg also hosts the Highlight/Plus comment-mode control, so assert on the labels
            // rather than counting segmented buttons.
            assertNull(segButton(page, "Rendered"), "with the feature off there is no rendered view to offer");
            assertNull(segButton(page, "Source"), "and therefore nothing to toggle between");
            assertNotNull(page.querySelector(".iv-code, .iv-code-block"), "the document stays readable as source");
        }
    }

    /** The Rendered/Source segmented button carrying {@code label}, or {@code null} if not offered. */
    private static HtmlElement segButton(HtmlPage page, String label) {
        for (DomNode node : page.querySelectorAll(".iv-seg button")) {
            HtmlElement b = (HtmlElement) node;
            if (label.equals(b.asNormalizedText().trim())) {
                return b;
            }
        }
        return null;
    }
}
