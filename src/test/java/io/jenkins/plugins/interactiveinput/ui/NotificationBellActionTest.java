// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.RootAction;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Hosting review (mawinter69, 2026-09-15): the notification centre is a primary {@link RootAction}
 * whose header button is rendered by core ({@code root-action-${simpleName}}). {@code bell.js} only
 * attaches the live pending-count badge.
 */
@WithJenkins
class NotificationBellActionTest {

    @Test
    void primaryRootActionIsHiddenUntilTheCentreIsEnabled(JenkinsRule j) {
        NotificationBellAction action =
                j.jenkins.getExtensionList(RootAction.class).get(NotificationBellAction.class);
        assertNotNull(action, "NotificationBellAction must be registered");
        assertEquals(NotificationBellAction.HEADER_BUTTON_ID, "root-action-NotificationBellAction");
        assertTrue(action.isPrimaryAction());
        assertNull(action.getUrlName(), "null urlName makes core render a header <button>, like SearchAction");

        InteractiveInputAppearanceConfig cfg = InteractiveInputAppearanceConfig.get();
        assertNotNull(cfg);
        cfg.setNotificationCentre(false);
        assertNull(action.getIconFileName(), "the header button stays hidden while the centre is off");

        cfg.setNotificationCentre(true);
        assertEquals(
                InteractiveInputAppearanceConfig.iconClassNameOrDefault(),
                action.getIconFileName(),
                "the header button uses the configured appearance icon when the centre is on");
    }

    @Test
    void jsAttachesNativeBadgeToTheCoreHeaderButton(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig.get().setNotificationCentre(true);

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.getOptions().setThrowExceptionOnScriptError(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

            HtmlPage dashboard = wc.goTo("");
            wc.waitForBackgroundJavaScript(3000);

            org.htmlunit.html.DomElement headerBtn = dashboard.getElementById(NotificationBellAction.HEADER_BUTTON_ID);
            assertNotNull(headerBtn, "core must render #root-action-NotificationBellAction");
            assertTrue(
                    headerBtn.getAttribute("class").contains("ii-bell-btn"),
                    "bell.js must reuse the core header button rather than inventing one");

            HtmlElement badge = (HtmlElement) dashboard.querySelector(".ii-bell-badge");
            assertNotNull(badge, "bell.js must attach the pending-count badge to the header action");
            assertTrue(badge.getAttribute("class").contains("jenkins-badge"), "badge uses core jenkins-badge");
            assertTrue(
                    badge.getAttribute("class").contains("jenkins-!-danger-color"),
                    "badge uses core jenkins-!-danger-color");
            assertTrue(
                    dashboard.querySelectorAll(".ii-bell-fallback").isEmpty(),
                    "must not fall back to a hand-built button when the RootAction is present");
        }
    }

    @Test
    void headerButtonIsAbsentWhenTheCentreIsOff(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig.get().setNotificationCentre(false);
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(false);
            HtmlPage dashboard = wc.goTo("");
            assertNull(
                    dashboard.getElementById(NotificationBellAction.HEADER_BUTTON_ID),
                    "core must not render the header button while the centre is off");
        }
    }
}
