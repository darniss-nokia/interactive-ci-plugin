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
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Notify-only Microsoft Teams incoming webhook. The webhook URL is a Secret-text credential, never
 * stored on the job. No inbound actions — the card only opens Jenkins.
 */
public class TeamsChannel extends NotificationChannel {

    @CheckForNull
    private String webhookCredentialsId;

    @DataBoundConstructor
    public TeamsChannel() {}

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
        JSONObject card = new JSONObject();
        card.put("@type", "MessageCard");
        card.put("@context", "http://schema.org/extensions");
        card.put("summary", event.summaryLine());
        card.put(
                "title",
                event.getKind() == NotificationEvent.Kind.REVIEW
                        ? "Interactive view needs a decision"
                        : "Interactive input needed");
        card.put(
                "text",
                event.summaryLine()
                        + "<br/>"
                        + event.getTitle()
                                .replace("&", "&amp;")
                                .replace("<", "&lt;")
                                .replace(">", "&gt;"));
        JSONObject target = new JSONObject();
        target.put("os", "default");
        target.put("uri", event.getUrl());
        JSONObject open = new JSONObject();
        open.put("@type", "OpenUri");
        open.put("name", "Open in Jenkins");
        open.put("targets", new JSONArray().element(target));
        card.put("potentialAction", new JSONArray().element(open));
        return card.toString();
    }

    @Extension
    @Symbol("teams")
    public static final class DescriptorImpl extends Descriptor<NotificationChannel> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Microsoft Teams";
        }

        /**
         * Secret-text credentials dropdown. {@code @POST} so the Jenkins Security Scan CSRF check
         * accepts it (core already POSTs {@code f:select} fills). Permission-checked inside
         * {@link WebhookCredentials#listBox}: {@link Item#CONFIGURE} on the job, or
         * {@link Jenkins#ADMINISTER} when there is no item. Does not contact the webhook.
         */
        @POST
        @NonNull
        public ListBoxModel doFillWebhookCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String webhookCredentialsId) {
            return WebhookCredentials.listBox(item, webhookCredentialsId);
        }
    }
}
