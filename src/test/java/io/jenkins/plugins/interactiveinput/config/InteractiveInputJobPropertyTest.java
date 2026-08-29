// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Round-trip coverage for {@link InteractiveInputJobProperty} (pipeline/job {@code Configure}
 * notification preferences). As an {@code OptionalJobProperty} it must survive a config submit when
 * enabled and stay absent when not enabled.
 */
@WithJenkins
class InteractiveInputJobPropertyTest {

    @Test
    void roundTripPersistsPreferencesWhenEnabled(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("cfg-job");
        InteractiveInputJobProperty prop = new InteractiveInputJobProperty();
        prop.setEmail(true);
        prop.setTeams(false);
        prop.setRecipients("a@example.com, b@example.com");
        prop.setWebhookCredentialsId("cred-1");
        p.addProperty(prop);

        j.configRoundtrip(p);

        InteractiveInputJobProperty after = p.getProperty(InteractiveInputJobProperty.class);
        assertNotNull(after, "enabled optional property should persist through a config round-trip");
        assertTrue(after.isEmail());
        assertFalse(after.isTeams());
        assertEquals("a@example.com, b@example.com", after.getRecipients());
        assertEquals("cred-1", after.getWebhookCredentialsId());
    }

    @Test
    void absentUnlessEnabled(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("cfg-job-2");
        j.configRoundtrip(p);
        assertNull(
                p.getProperty(InteractiveInputJobProperty.class),
                "optional property must not attach unless the operator enables it");
    }
}
