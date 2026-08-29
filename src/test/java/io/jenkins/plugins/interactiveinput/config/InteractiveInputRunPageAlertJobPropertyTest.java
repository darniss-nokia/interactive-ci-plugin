// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Round-trip coverage for {@link InteractiveInputRunPageAlertJobProperty} (the B5 per-pipeline "alert
 * user on run page" opt-in). As a field-less {@code OptionalJobProperty} its mere presence is the
 * setting, so it must survive a config submit when enabled and stay absent when not enabled.
 */
@WithJenkins
class InteractiveInputRunPageAlertJobPropertyTest {

    @Test
    void enabledPropertyPersistsThroughConfigRoundTrip(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("run-alert-on");
        p.addProperty(new InteractiveInputRunPageAlertJobProperty());
        assertTrue(InteractiveInputRunPageAlertJobProperty.isEnabledOn(p), "sanity: property added");

        j.configRoundtrip(p);

        assertNotNull(
                p.getProperty(InteractiveInputRunPageAlertJobProperty.class),
                "the enabled opt-in must persist through a Configure round-trip");
        assertTrue(InteractiveInputRunPageAlertJobProperty.isEnabledOn(p));
    }

    @Test
    void absentUnlessEnabled(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("run-alert-off");
        j.configRoundtrip(p);
        assertNull(
                p.getProperty(InteractiveInputRunPageAlertJobProperty.class),
                "the opt-in must not attach unless the operator enables it");
        assertFalse(InteractiveInputRunPageAlertJobProperty.isEnabledOn(p));
        assertFalse(InteractiveInputRunPageAlertJobProperty.isEnabledOn(null), "null-safe");
    }
}
