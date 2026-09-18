// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Job;
import io.jenkins.plugins.interactiveinput.notify.EmailChannel;
import io.jenkins.plugins.interactiveinput.notify.NotificationChannel;
import io.jenkins.plugins.interactiveinput.notify.TeamsChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.OptionalJobProperty;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Per-pipeline outbound notification channels, shown in <em>Configure</em> as an opt-in checkbox
 * ("Interactive Input notifications") that reveals an add-channel list. Delivery is notify-only
 * (email / Teams / Slack webhooks) — nothing in this property answers a question.
 *
 * <p>Pre-channel XML ({@code email}/{@code teams} booleans plus {@code recipients}/
 * {@code webhookCredentialsId}) is migrated in {@link #readResolve()}.
 */
public class InteractiveInputJobProperty extends OptionalJobProperty<Job<?, ?>> {

    /**
     * Legacy fields kept only so XStream can populate them on load; {@link #readResolve()} copies them
     * into {@link #channels} and clears them so they are not re-migrated.
     */
    private boolean email;

    private boolean teams;

    @CheckForNull
    private String recipients;

    @CheckForNull
    private String webhookCredentialsId;

    /**
     * Channel list. Not {@code @NonNull}: XStream leaves this {@code null} when the XML has no
     * {@code <channels>} (legacy boolean form in {@link InteractiveInputJobPropertyTest}).
     * {@link #readResolve()} and {@link #getChannels()} treat that as empty.
     */
    private List<NotificationChannel> channels = new ArrayList<>();

    @DataBoundConstructor
    public InteractiveInputJobProperty() {
        // Fields populated via @DataBoundSetter from config-details.jelly.
    }

    @NonNull
    public List<NotificationChannel> getChannels() {
        return channels == null ? Collections.emptyList() : Collections.unmodifiableList(channels);
    }

    @DataBoundSetter
    public void setChannels(@CheckForNull List<NotificationChannel> channels) {
        this.channels = channels == null ? new ArrayList<>() : new ArrayList<>(channels);
    }

    /**
     * Convert the pre-ExtensionPoint boolean form into {@link EmailChannel} / {@link TeamsChannel}
     * once. After that {@link #channels} is the source of truth.
     */
    @NonNull
    private Object readResolve() {
        if (channels == null) {
            channels = new ArrayList<>();
        }
        if (channels.isEmpty()) {
            if (email || (recipients != null && !recipients.isBlank())) {
                EmailChannel mail = new EmailChannel();
                mail.setRecipients(recipients);
                channels.add(mail);
            }
            if (teams || (webhookCredentialsId != null && !webhookCredentialsId.isBlank())) {
                TeamsChannel t = new TeamsChannel();
                t.setWebhookCredentialsId(webhookCredentialsId);
                channels.add(t);
            }
        }
        email = false;
        teams = false;
        recipients = null;
        webhookCredentialsId = null;
        return this;
    }

    @Extension
    @Symbol("interactiveInputNotifications")
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Interactive Input notifications";
        }

        /** Channel types offered by the hetero-list (not a Stapler {@code do*} fill). */
        @NonNull
        public List<Descriptor<NotificationChannel>> getChannelDescriptors() {
            return new ArrayList<>(NotificationChannel.all());
        }
    }
}
