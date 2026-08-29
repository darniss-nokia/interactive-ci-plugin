// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.XStream2;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies the functional feature flags round-trip through the <em>Manage Jenkins → System</em> UI —
 * in particular the opt-in input-step bridge toggle — and that saving the form neither loses the
 * checked-by-default flags nor resets settings that are not on the form (polling / SLA / retention).
 */
@WithJenkins
class InteractiveInputGlobalConfigTest {

    @Test
    void inputStepBridgeTogglesThroughTheUiAndPreservesOtherSettings(JenkinsRule j) throws Exception {
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        assertNotNull(cfg, "global config must be registered");
        assertFalse(cfg.getFeatures().isInputStepBridge(), "the bridge is off by default");
        // A non-default retention (as JCasC / Script Console would set) must survive a System save.
        cfg.setRetentionDays(3);

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        HtmlCheckBoxInput bridge = form.getInputByName("_.inputStepBridge");
        bridge.setChecked(true);
        j.submit(form);

        InteractiveInputGlobalConfig after = InteractiveInputGlobalConfig.get();
        assertTrue(after.getFeatures().isInputStepBridge(), "the bridge must be enabled via the UI checkbox");
        assertTrue(after.getFeatures().isAskInteractiveStep(), "checked-by-default flags must stay on");
        assertTrue(after.getFeatures().isRestApi(), "checked-by-default flags must stay on");
        assertEquals(3, after.getRetentionDays(), "configure() must not reset settings that are not on the form");
    }

    @Test
    void uncheckingAFlagTurnsItOff(JenkinsRule j) throws Exception {
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        HtmlCheckBoxInput restApi = form.getInputByName("_.restApi");
        restApi.setChecked(false);
        j.submit(form);

        assertFalse(
                InteractiveInputGlobalConfig.get().getFeatures().isRestApi(),
                "unchecking a flag must turn it off (configure() starts all-off before binding)");
    }

    @Test
    void authorizationSwitchesDefaultOffAndRoundTripThroughTheUi(JenkinsRule j) throws Exception {
        // B18: userScopedNotifications + lockToBuildStarter moved here (System). Both default off.
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        assertNotNull(cfg);
        assertFalse(cfg.isUserScopedNotifications(), "user-scoped notifications off by default");
        assertFalse(cfg.isLockToBuildStarter(), "lock-to-build-starter off by default");
        assertFalse(InteractiveInputGlobalConfig.userScopedNotificationsEnabled());
        assertFalse(InteractiveInputGlobalConfig.lockToBuildStarterEnabled());

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        form.getInputByName("_.userScopedNotifications").setChecked(true);
        form.getInputByName("_.lockToBuildStarter").setChecked(true);
        j.submit(form);

        assertTrue(InteractiveInputGlobalConfig.userScopedNotificationsEnabled(), "enabled via the System UI");
        assertTrue(InteractiveInputGlobalConfig.lockToBuildStarterEnabled(), "enabled via the System UI");

        // Unchecking turns them back off (configure() starts them off before binding).
        HtmlForm form2 = j.createWebClient().goTo("configure").getFormByName("config");
        form2.getInputByName("_.userScopedNotifications").setChecked(false);
        form2.getInputByName("_.lockToBuildStarter").setChecked(false);
        j.submit(form2);

        assertFalse(InteractiveInputGlobalConfig.userScopedNotificationsEnabled());
        assertFalse(InteractiveInputGlobalConfig.lockToBuildStarterEnabled());
    }

    @Test
    void reopenBuildDialogEveryVisitDefaultsOffAndRoundTripsThroughTheUi(JenkinsRule j) throws Exception {
        // B5 auto-open: off by default selects mode A (open once per browser session); enabling it
        // selects mode B (re-open on every visit). Must round-trip through the System form like the
        // other System switches, since configure() rebinds all checkboxes from scratch.
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        assertNotNull(cfg);
        assertFalse(cfg.isReopenBuildDialogEveryVisit(), "every-visit auto-open (mode B) is off by default");
        assertFalse(InteractiveInputGlobalConfig.reopenBuildDialogEveryVisitEnabled());

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        form.getInputByName("_.reopenBuildDialogEveryVisit").setChecked(true);
        j.submit(form);
        assertTrue(
                InteractiveInputGlobalConfig.reopenBuildDialogEveryVisitEnabled(),
                "the auto-open every-visit toggle must enable via the System UI");

        HtmlForm form2 = j.createWebClient().goTo("configure").getFormByName("config");
        form2.getInputByName("_.reopenBuildDialogEveryVisit").setChecked(false);
        j.submit(form2);
        assertFalse(
                InteractiveInputGlobalConfig.reopenBuildDialogEveryVisitEnabled(),
                "unchecking must turn it back off (configure() starts it off before binding)");
    }

    @Test
    void automationReplyNameDefaultsAndRoundTripsThroughTheUi(JenkinsRule j) throws Exception {
        // Ask #2: the global display label for automation replies. Defaults to "AI response", round-trips
        // through the System form, and a blank value falls back to the default (never an empty label).
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        assertNotNull(cfg);
        assertEquals("AI response", cfg.getAutomationReplyName(), "default automation reply name");
        assertEquals("AI response", InteractiveInputGlobalConfig.automationReplyNameOrDefault());

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        form.getInputByName("_.automationReplyName").setValue("JENKINS response");
        j.submit(form);
        assertEquals(
                "JENKINS response",
                InteractiveInputGlobalConfig.get().getAutomationReplyName(),
                "the automation reply name must round-trip through the System UI");

        HtmlForm blank = j.createWebClient().goTo("configure").getFormByName("config");
        blank.getInputByName("_.automationReplyName").setValue("   ");
        j.submit(blank);
        assertEquals(
                "AI response",
                InteractiveInputGlobalConfig.get().getAutomationReplyName(),
                "a blank label falls back to the built-in default");
    }

    @Test
    void newFlagsDefaultOnWhenAbsentFromAnUpgradedConfigXml(JenkinsRule j) {
        // Regression (found in live validation on 2.568.1): a controller upgrading from a build that
        // predates interactiveView/interactiveOutput has a saved <features> block without those elements.
        // XStream instantiates the object without running field initialisers, so a plain boolean field
        // would load as false and silently hide the new surfaces. The nullable Boolean fields must
        // default these on when the element is absent, while still honouring an explicit value.
        assertNotNull(j.jenkins, "runs with a live Jenkins so XStream2 uses the same converters as load()");
        XStream2 xs = new XStream2();

        Features upgraded = (Features) xs.fromXML("<io.jenkins.plugins.interactiveinput.config.Features>"
                + "<askInteractiveStep>true</askInteractiveStep>"
                + "<richModal>true</richModal>"
                + "<restApi>true</restApi>"
                + "<inputStepBridge>true</inputStepBridge>"
                + "<dashboardTile>false</dashboardTile>"
                + "</io.jenkins.plugins.interactiveinput.config.Features>");
        assertTrue(upgraded.isInteractiveView(), "absent interactiveView must default on after an upgrade");
        assertTrue(upgraded.isInteractiveOutput(), "absent interactiveOutput must default on after an upgrade");

        Features explicitOff = (Features) xs.fromXML("<io.jenkins.plugins.interactiveinput.config.Features>"
                + "<interactiveView>false</interactiveView>"
                + "<interactiveOutput>false</interactiveOutput>"
                + "</io.jenkins.plugins.interactiveinput.config.Features>");
        assertFalse(explicitOff.isInteractiveView(), "an explicit false must still be honoured");
        assertFalse(explicitOff.isInteractiveOutput(), "an explicit false must still be honoured");
    }
}
