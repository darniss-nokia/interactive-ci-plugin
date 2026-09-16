// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.ui.InteractiveInputRunAction;
import io.jenkins.plugins.interactiveinput.ui.InteractiveViewRunAction;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import jenkins.model.Jenkins;

/**
 * Absolute Jenkins URLs for outbound notifications. Same path shapes as the console deep-link
 * ({@code interactive-input/?open=}) and the review editor ({@code interactive-view/?doc=}).
 *
 * <p>Returns {@code null} when the controller has no configured root URL — a relative link is
 * useless in Slack/Teams/mail.
 */
public final class NotificationUrls {

    private NotificationUrls() {}

    @CheckForNull
    public static String forQuestion(@NonNull Question question) {
        return build(
                question.getJobFullName(),
                question.getBuildNumber(),
                InteractiveInputRunAction.URL_NAME,
                "open",
                question.getId());
    }

    @CheckForNull
    public static String forReview(@NonNull ReviewDocument doc) {
        return build(
                doc.getJobFullName(),
                doc.getBuildNumber(),
                InteractiveViewRunAction.URL_NAME,
                "doc",
                doc.getId());
    }

    @CheckForNull
    static String rootUrl() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        String root = j.getRootUrl();
        if (root == null || root.isBlank()) {
            return null;
        }
        return root.endsWith("/") ? root : root + "/";
    }

    @CheckForNull
    private static String build(
            @NonNull String jobFullName,
            int buildNumber,
            @NonNull String action,
            @NonNull String query,
            @NonNull String id) {
        String root = rootUrl();
        if (root == null) {
            return null;
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        Job<?, ?> job = j.getItemByFullName(jobFullName, Job.class);
        if (job == null) {
            return null;
        }
        Run<?, ?> run = job.getBuildByNumber(buildNumber);
        String scope = run != null ? run.getUrl() : job.getUrl();
        return root + scope + action + "/?" + query + "=" + Util.rawEncode(id);
    }
}
