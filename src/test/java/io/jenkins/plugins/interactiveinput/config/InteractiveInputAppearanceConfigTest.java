// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.ListBoxModel;
import org.jenkins.ui.symbol.Symbol;
import org.jenkins.ui.symbol.SymbolRequest;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Unit coverage for the appearance config: defaults, icon validation/normalisation, the rendered
 * symbol class, and that the dropdown offers exactly the whitelisted icons. Runtime scoping and
 * blink/rendering are covered by live testing (they are CSS/DOM concerns).
 */
@WithJenkins
class InteractiveInputAppearanceConfigTest {

    @Test
    void defaultsMatchDocumentedBehaviour(JenkinsRule j) {
        InteractiveInputAppearanceConfig c = InteractiveInputAppearanceConfig.get();
        assertNotNull(c);
        assertFalse(c.isNotificationCentre(), "global bell is off by default");
        assertTrue(c.isPerProjectCentre(), "per-project centre is on by default");
        assertTrue(c.isJobPageBox(), "job-page box is on by default");
        assertTrue(c.isTabNotificationBadge(), "browser-tab badge is on by default");
        assertTrue(c.isViewBuildCard(), "Interactive View build-page card is on by default");
        assertTrue(c.isOutputBuildCard(), "Interactive Output build-page card is on by default");
        assertEquals(InteractiveInputAppearanceConfig.DEFAULT_ICON, c.getIcon());
        assertEquals("megaphone", c.getIcon(), "default notification icon is the megaphone");

        // Null-safe static helpers agree with the instance.
        assertFalse(InteractiveInputAppearanceConfig.notificationCentreEnabled());
        assertTrue(InteractiveInputAppearanceConfig.perProjectCentreEnabled());
        assertTrue(InteractiveInputAppearanceConfig.jobPageBoxEnabled());
        assertTrue(InteractiveInputAppearanceConfig.tabNotificationBadgeEnabled());
        assertTrue(InteractiveInputAppearanceConfig.viewBuildCardEnabled());
        assertTrue(InteractiveInputAppearanceConfig.outputBuildCardEnabled());
    }

    @Test
    void tabNotificationBadgePersistsThroughSetter(JenkinsRule j) {
        InteractiveInputAppearanceConfig c = InteractiveInputAppearanceConfig.get();
        assertNotNull(c);

        c.setTabNotificationBadge(false);
        assertFalse(InteractiveInputAppearanceConfig.tabNotificationBadgeEnabled());

        c.setTabNotificationBadge(true);
        assertTrue(InteractiveInputAppearanceConfig.tabNotificationBadgeEnabled());
    }

    @Test
    void buildCardTogglesPersistThroughSetters(JenkinsRule j) {
        InteractiveInputAppearanceConfig c = InteractiveInputAppearanceConfig.get();
        assertNotNull(c);

        c.setViewBuildCard(false);
        assertFalse(InteractiveInputAppearanceConfig.viewBuildCardEnabled());
        c.setViewBuildCard(true);
        assertTrue(InteractiveInputAppearanceConfig.viewBuildCardEnabled());

        c.setOutputBuildCard(false);
        assertFalse(InteractiveInputAppearanceConfig.outputBuildCardEnabled());
        c.setOutputBuildCard(true);
        assertTrue(InteractiveInputAppearanceConfig.outputBuildCardEnabled());
    }

    @Test
    void iconRejectsUnknownAndNormalises(JenkinsRule j) {
        InteractiveInputAppearanceConfig c = InteractiveInputAppearanceConfig.get();
        assertNotNull(c);

        c.setIcon("hand-left");
        assertEquals("hand-left", c.getIcon());

        c.setIcon("not-a-real-icon");
        assertEquals(InteractiveInputAppearanceConfig.DEFAULT_ICON, c.getIcon(), "unknown icon falls back to default");

        c.setIcon(null);
        assertEquals(InteractiveInputAppearanceConfig.DEFAULT_ICON, c.getIcon(), "null icon falls back to default");
    }

    @Test
    void iconClassNameIsThemeAwareSymbolClass(JenkinsRule j) {
        assertEquals(
                "symbol-chatbubble-ellipses-outline plugin-ionicons-api",
                InteractiveInputAppearanceConfig.iconClassName("chatbubble-ellipses"));
        // The two new Ionicons resolve via ionicons-api like the rest.
        assertEquals(
                "symbol-hardware-chip-outline plugin-ionicons-api",
                InteractiveInputAppearanceConfig.iconClassName("hardware-chip"));
        assertEquals(
                "symbol-sparkles-outline plugin-ionicons-api",
                InteractiveInputAppearanceConfig.iconClassName("sparkles"));
        // Ionicons has no robot glyph, so the robot is a symbol this plugin ships itself: no -outline
        // suffix and the plugin-interactive-ci source rather than plugin-ionicons-api.
        assertEquals("symbol-robot plugin-interactive-ci", InteractiveInputAppearanceConfig.iconClassName("robot"));
        // Unknown stems fall back to the default so we never emit a class for a missing symbol.
        assertEquals(
                "symbol-" + InteractiveInputAppearanceConfig.DEFAULT_ICON + "-outline plugin-ionicons-api",
                InteractiveInputAppearanceConfig.iconClassName("bogus"));
    }

    @Test
    void robotSymbolResolvesFromThisPlugin(JenkinsRule j) {
        // Proves the whole chain for the bundled icon: the "symbol-robot plugin-interactive-ci" class
        // (from iconClassName) actually resolves to src/main/resources/images/symbols/robot.svg. A missing
        // symbol would resolve to a placeholder that lacks our distinctive path data.
        String svg = Symbol.get(new SymbolRequest.Builder()
                .withName("robot")
                .withPluginName("interactive-ci")
                .build());
        assertNotNull(svg);
        assertTrue(svg.contains("<svg"), "robot symbol must resolve to an inlined SVG");
        assertTrue(
                svg.contains("M256 176"),
                "the resolved SVG must be our bundled robot (antenna path), not a placeholder");
    }

    @Test
    void dropdownOffersExactlyTheWhitelistedIcons(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig c = InteractiveInputAppearanceConfig.get();
        assertNotNull(c);
        ListBoxModel items = c.doFillIconItems();
        assertEquals(InteractiveInputAppearanceConfig.ICON_CHOICES.size(), items.size());
        for (ListBoxModel.Option o : items) {
            assertTrue(
                    InteractiveInputAppearanceConfig.ICON_CHOICES.contains(o.value),
                    () -> "dropdown offers a non-whitelisted icon: " + o.value);
        }
        assertNotNull(
                InteractiveInputAppearanceConfig.class
                        .getMethod("doFillIconItems")
                        .getAnnotation(org.kohsuke.stapler.verb.POST.class),
                "icon fill must be @POST (Jenkins Security Scan CSRF)");
    }
}
