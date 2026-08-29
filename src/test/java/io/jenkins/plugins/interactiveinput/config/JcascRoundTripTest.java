// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * JCasC round-trip (§7.3, §8.8): loading the documented YAML must wire the extensions, and exporting
 * must reproduce the configured values. Functional flags live under the {@code interactiveInput}
 * symbol (unclassified); appearance surfaces live under {@code appearance.interactiveInputAppearance}
 * (the {@code AppearanceCategory} root — verified against JCasC's
 * {@code GlobalConfigurationCategoryConfigurator}, which strips the {@code Category} suffix and
 * lower-cases the category class name when it has no {@code @Symbol}).
 */
@WithJenkins
class JcascRoundTripTest {

    @Test
    void loadsFeatureFlagsFromYaml(JenkinsRule j) throws Exception {
        ConfigurationAsCode.get().configure(resource("jcasc-interactive-input.yml"));

        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        assertNotNull(cfg);
        Features f = cfg.getFeatures();
        assertTrue(f.isAskInteractiveStep());
        assertTrue(f.isRestApi());
        assertTrue(f.isInputStepBridge(), "inputStepBridge overridden to true in YAML");
        assertFalse(f.isDashboardTile());
        assertFalse(f.isInteractiveView(), "interactiveView overridden to false in YAML");
        assertTrue(f.isInteractiveOutput(), "interactiveOutput stays on (default) in YAML");
        assertFalse(f.isHtmlRendering(), "htmlRendering overridden to false in YAML");
        assertEquals(30, cfg.getPolling().getIntervalSeconds());
        assertEquals(5, cfg.getSla().getDefaultMinutes());
        assertEquals(14, cfg.getRetentionDays());
        // B18: the authorization switches moved from Appearance to System (unclassified.interactiveInput).
        assertTrue(cfg.isUserScopedNotifications(), "userScopedNotifications enabled in YAML (System)");
        assertTrue(cfg.isLockToBuildStarter(), "lockToBuildStarter enabled in YAML (System)");
        assertEquals("JENKINS response", cfg.getAutomationReplyName(), "automationReplyName loaded from YAML");
    }

    @Test
    void loadsAppearanceFromYaml(JenkinsRule j) throws Exception {
        ConfigurationAsCode.get().configure(resource("jcasc-interactive-input.yml"));

        InteractiveInputAppearanceConfig a = InteractiveInputAppearanceConfig.get();
        assertNotNull(a);
        assertTrue(a.isNotificationCentre(), "notificationCentre enabled in YAML");
        assertTrue(a.isPerProjectCentre());
        assertFalse(a.isJobPageBox(), "jobPageBox disabled in YAML");
        assertFalse(a.isTabNotificationBadge(), "tabNotificationBadge disabled in YAML");
        assertEquals("hand-left", a.getIcon());
    }

    @Test
    void exportsConfiguredValues(JenkinsRule j) throws Exception {
        ConfigurationAsCode.get().configure(resource("jcasc-interactive-input.yml"));

        String exported = export();
        assertTrue(exported.contains("interactiveInput"), () -> "export missing symbol:\n" + exported);
        assertTrue(exported.contains("inputStepBridge: true"), () -> "export missing bridge flag:\n" + exported);
        assertTrue(
                exported.contains("interactiveView: false"),
                () -> "export missing interactiveView feature flag:\n" + exported);
        assertTrue(
                exported.contains("htmlRendering: false"),
                () -> "export missing htmlRendering feature flag:\n" + exported);
        assertTrue(exported.contains("intervalSeconds: 30"), () -> "export missing polling:\n" + exported);
        assertTrue(
                exported.contains("lockToBuildStarter: true"),
                () -> "export missing moved authorization flag (should be under interactiveInput):\n" + exported);
        assertTrue(
                exported.contains("interactiveInputAppearance"), () -> "export missing appearance block:\n" + exported);
        assertTrue(exported.contains("hand-left"), () -> "export missing configured icon:\n" + exported);
        assertTrue(
                exported.contains("JENKINS response"),
                () -> "export missing configured automation reply name:\n" + exported);
    }

    private static String resource(String name) {
        return Objects.requireNonNull(JcascRoundTripTest.class.getResource(name), "missing test resource " + name)
                .toString();
    }

    private static String export() throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ConfigurationAsCode.get().export(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
