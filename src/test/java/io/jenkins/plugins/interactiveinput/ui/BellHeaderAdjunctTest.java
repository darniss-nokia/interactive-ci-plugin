// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * B21: the bell CSS/JS adjunct must be contributed from {@code header.jelly} (rendered in {@code
 * <head>}) rather than {@code footer.jelly} at end-of-body, so it parses earlier. The data mount (a
 * {@code <div>}) stays in the body because a {@code <div>} is not valid inside {@code <head>}.
 */
@WithJenkins
class BellHeaderAdjunctTest {

    @Test
    void bellAdjunctLoadsInHeadWhileMountStaysInBody(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig.get().setNotificationCentre(true); // the global bell is off by default

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(false); // pure server render: WHERE is the adjunct emitted?
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            HtmlPage page = wc.goTo("");

            assertNotNull(
                    page.getFirstByXPath("//head//script[contains(@src,'interactiveinput/bell')]"),
                    "bell.js adjunct must be contributed in <head> (B21)");
            assertNotNull(
                    page.getElementById("interactive-input-bell"),
                    "the bell data mount must stay in the body (a <div> is invalid in <head>)");
        }
    }
}
