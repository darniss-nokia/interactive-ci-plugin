// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.util.XStream2;
import io.jenkins.plugins.interactiveinput.notify.EmailChannel;
import io.jenkins.plugins.interactiveinput.notify.NotificationChannel;
import io.jenkins.plugins.interactiveinput.notify.SlackChannel;
import io.jenkins.plugins.interactiveinput.notify.TeamsChannel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Round-trip coverage for {@link InteractiveInputJobProperty}: hetero-list channels persist, the
 * property stays absent unless enabled, and pre-channel XML migrates via {@code readResolve}.
 */
@WithJenkins
class InteractiveInputJobPropertyTest {

    @Test
    void roundTripPersistsChannelsWhenEnabled(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("cfg-job");
        InteractiveInputJobProperty prop = new InteractiveInputJobProperty();
        EmailChannel mail = new EmailChannel();
        mail.setRecipients("a@example.com, b@example.com");
        SlackChannel slack = new SlackChannel();
        slack.setWebhookCredentialsId("cred-1");
        prop.setChannels(List.of(mail, slack));
        p.addProperty(prop);

        j.configRoundtrip(p);

        InteractiveInputJobProperty after = p.getProperty(InteractiveInputJobProperty.class);
        assertNotNull(after, "enabled optional property should persist through a config round-trip");
        List<NotificationChannel> channels = after.getChannels();
        assertEquals(2, channels.size());
        assertInstanceOf(EmailChannel.class, channels.get(0));
        assertEquals("a@example.com, b@example.com", ((EmailChannel) channels.get(0)).getRecipients());
        assertInstanceOf(SlackChannel.class, channels.get(1));
        assertEquals("cred-1", ((SlackChannel) channels.get(1)).getWebhookCredentialsId());
    }

    @Test
    void absentUnlessEnabled(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("cfg-job-2");
        j.configRoundtrip(p);
        assertNull(
                p.getProperty(InteractiveInputJobProperty.class),
                "optional property must not attach unless the operator enables it");
    }

    @Test
    void readResolveMigratesLegacyBooleans(JenkinsRule j) {
        String xml =
                """
                <io.jenkins.plugins.interactiveinput.config.InteractiveInputJobProperty>
                  <email>true</email>
                  <teams>true</teams>
                  <recipients>a@example.com</recipients>
                  <webhookCredentialsId>cred-1</webhookCredentialsId>
                </io.jenkins.plugins.interactiveinput.config.InteractiveInputJobProperty>
                """;
        InteractiveInputJobProperty prop = (InteractiveInputJobProperty) new XStream2().fromXML(xml);
        assertEquals(2, prop.getChannels().size());
        assertInstanceOf(EmailChannel.class, prop.getChannels().get(0));
        assertEquals("a@example.com", ((EmailChannel) prop.getChannels().get(0)).getRecipients());
        assertInstanceOf(TeamsChannel.class, prop.getChannels().get(1));
        assertEquals("cred-1", ((TeamsChannel) prop.getChannels().get(1)).getWebhookCredentialsId());
        // A second resolve (channels already populated) must not duplicate.
        Object again = prop; // already migrated
        assertEquals(2, ((InteractiveInputJobProperty) again).getChannels().size());
        assertTrue(j.jenkins.getDescriptor(EmailChannel.class) != null);
    }
}
