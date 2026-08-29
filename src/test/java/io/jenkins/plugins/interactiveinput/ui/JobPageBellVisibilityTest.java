// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression guard for the global notification bell being ABSENT on a job/pipeline page while present
 * on the dashboard.
 *
 * <p>Root cause: the shared {@code bell.js} adjunct is emitted by {@code jobMain.jelly} in the MAIN
 * PANEL of a job page, i.e. earlier in the document than the footer {@code #interactive-input-bell}
 * mount (a {@code PageDecorator} rendered at end-of-body). The script used to collect its mounts at
 * top-level execution, so on a job page {@code #interactive-input-bell} did not exist yet and the bell
 * never mounted — while on the dashboard (no {@code jobMain.jelly}) the adjunct runs after the mount,
 * so it worked. Deferring discovery to {@code DOMContentLoaded} fixes it.
 *
 * <p>Unlike {@link SidebarLiveCountTest}, JavaScript is enabled here on purpose: the defect is a
 * client-side timing bug that the server-rendered DOM cannot reveal. The assertion is the deterministic
 * outcome of {@code mountBell} having run — the {@code .ii-bell-btn} control exists in the page.
 */
@WithJenkins
class JobPageBellVisibilityTest {

    @Test
    void bellMountsInsideAJobPageNotOnlyOnTheDashboard(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig cfg = InteractiveInputAppearanceConfig.get();
        assertNotNull(cfg, "appearance config must be registered");
        cfg.setNotificationCentre(true); // the global bell is off by default

        FreeStyleProject p = j.createFreeStyleProject("bell-job");

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            // The bell mounts synchronously (icon + anchor) before it starts polling; the async poll
            // (fetch) is irrelevant to this assertion, so a poll hiccup must not fail the page.
            wc.getOptions().setThrowExceptionOnScriptError(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

            // Dashboard: the bell has always mounted here.
            HtmlPage dashboard = wc.goTo("");
            wc.waitForBackgroundJavaScript(3000);
            assertFalse(
                    dashboard.querySelectorAll(".ii-bell-btn").isEmpty(), "control bell must mount on the dashboard");

            // Job page: the adjunct is emitted in the main panel, before the footer bell. The bell must
            // STILL mount here — it did not before the DOMContentLoaded fix.
            HtmlPage jobPage = wc.goTo(p.getUrl());
            wc.waitForBackgroundJavaScript(3000);
            assertFalse(
                    jobPage.querySelectorAll(".ii-bell-btn").isEmpty(),
                    "bell must also mount inside a job/pipeline page (adjunct runs before the footer mount)");
        }
    }
}
