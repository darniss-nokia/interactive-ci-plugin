// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Job;
import jenkins.model.OptionalJobProperty;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Per-pipeline notification preferences, shown in <em>Configure</em> alongside other plugins
 * (§future push notifications). Extends {@link OptionalJobProperty} so it appears as an opt-in
 * checkbox ("Interactive Input notifications") that reveals the channel preferences; the property is
 * attached only when enabled.
 *
 * <p>Scope note: this release <strong>persists</strong> the preferences only. Actual delivery
 * (email, Microsoft Teams, webhooks) is deferred to a future release — no notifier extension point
 * ships yet. Storing the intent now lets operators configure pipelines ahead of that work.
 */
public class InteractiveInputJobProperty extends OptionalJobProperty<Job<?, ?>> {

    private boolean email;
    private boolean teams;

    @CheckForNull
    private String recipients;

    @CheckForNull
    private String webhookCredentialsId;

    @DataBoundConstructor
    public InteractiveInputJobProperty() {
        // Fields populated via @DataBoundSetter from config-details.jelly.
    }

    public boolean isEmail() {
        return email;
    }

    @DataBoundSetter
    public void setEmail(boolean email) {
        this.email = email;
    }

    public boolean isTeams() {
        return teams;
    }

    @DataBoundSetter
    public void setTeams(boolean teams) {
        this.teams = teams;
    }

    @CheckForNull
    public String getRecipients() {
        return recipients;
    }

    @DataBoundSetter
    public void setRecipients(@CheckForNull String recipients) {
        this.recipients = recipients == null || recipients.trim().isEmpty() ? null : recipients.trim();
    }

    @CheckForNull
    public String getWebhookCredentialsId() {
        return webhookCredentialsId;
    }

    @DataBoundSetter
    public void setWebhookCredentialsId(@CheckForNull String webhookCredentialsId) {
        this.webhookCredentialsId =
                webhookCredentialsId == null || webhookCredentialsId.trim().isEmpty()
                        ? null
                        : webhookCredentialsId.trim();
    }

    @Extension
    @Symbol("interactiveInputNotifications")
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Interactive Input notifications";
        }
    }
}
