// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Notify-only Slack incoming webhook. The webhook URL is a Secret-text credential. No Slack App, no
 * interactive buttons, no inbound answering.
 */
public class SlackChannel extends NotificationChannel {

    @CheckForNull
    private String webhookCredentialsId;

    @DataBoundConstructor
    public SlackChannel() {}

    @CheckForNull
    public String getWebhookCredentialsId() {
        return webhookCredentialsId;
    }

    @DataBoundSetter
    public void setWebhookCredentialsId(@CheckForNull String webhookCredentialsId) {
        this.webhookCredentialsId = Util.fixEmptyAndTrim(webhookCredentialsId);
    }

    @Override
    public void send(@NonNull NotificationEvent event) {
        Item item = Jenkins.get().getItemByFullName(event.getJobFullName(), Item.class);
        String url = WebhookCredentials.secretText(item, webhookCredentialsId);
        if (url == null || url.isBlank()) {
            return;
        }
        WebhookPoster.postJson(url, json(event));
    }

    @NonNull
    static String json(@NonNull NotificationEvent event) {
        JSONObject o = new JSONObject();
        o.put("text", event.summaryLine() + "\n" + event.getTitle() + "\n" + event.getUrl());
        return o.toString();
    }

    @Extension
    @Symbol("slack")
    public static final class DescriptorImpl extends Descriptor<NotificationChannel> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Slack";
        }

        /**
         * Secret-text credentials dropdown. Permission-checked: {@link Item#CONFIGURE} on the job, or
         * {@link Jenkins#ADMINISTER} when there is no item. Does not contact the webhook.
         */
        @NonNull
        public ListBoxModel doFillWebhookCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String webhookCredentialsId) {
            return WebhookCredentials.listBox(item, webhookCredentialsId);
        }
    }
}
