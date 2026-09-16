// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;

/**
 * Immutable snapshot handed to {@link NotificationChannel#send}. Contains job metadata and a deep
 * link only — never the question context markdown or the review file body.
 */
public final class NotificationEvent {

    public enum Kind {
        QUESTION,
        REVIEW
    }

    /** Prompt/title cap so a huge string cannot blow a Slack/Teams/email payload. */
    static final int TITLE_MAX = 200;

    @NonNull
    private final Kind kind;

    @NonNull
    private final String id;

    @NonNull
    private final String jobFullName;

    private final int buildNumber;

    @CheckForNull
    private final String startedBy;

    @NonNull
    private final String title;

    @NonNull
    private final String status;

    @NonNull
    private final String url;

    NotificationEvent(
            @NonNull Kind kind,
            @NonNull String id,
            @NonNull String jobFullName,
            int buildNumber,
            @CheckForNull String startedBy,
            @NonNull String title,
            @NonNull String status,
            @NonNull String url) {
        this.kind = kind;
        this.id = id;
        this.jobFullName = jobFullName;
        this.buildNumber = buildNumber;
        this.startedBy = startedBy;
        this.title = title;
        this.status = status;
        this.url = url;
    }

    @NonNull
    static NotificationEvent question(@NonNull Question q, @NonNull String url) {
        return new NotificationEvent(
                Kind.QUESTION,
                q.getId(),
                q.getJobFullName(),
                q.getBuildNumber(),
                q.getStartedBy(),
                truncate(q.getPrompt()),
                q.getStatus().name(),
                url);
    }

    @NonNull
    static NotificationEvent review(@NonNull ReviewDocument doc, @NonNull String url) {
        return new NotificationEvent(
                Kind.REVIEW,
                doc.getId(),
                doc.getJobFullName(),
                doc.getBuildNumber(),
                doc.getCreatedBy(),
                truncate(doc.getTitle()),
                doc.getStatus().name(),
                url);
    }

    @NonNull
    static String truncate(@CheckForNull String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX - 1) + "…";
    }

    @NonNull
    public Kind getKind() {
        return kind;
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getJobFullName() {
        return jobFullName;
    }

    public int getBuildNumber() {
        return buildNumber;
    }

    @CheckForNull
    public String getStartedBy() {
        return startedBy;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    @NonNull
    public String getStatus() {
        return status;
    }

    @NonNull
    public String getUrl() {
        return url;
    }

    /** One-line summary for Slack/email text bodies. */
    @NonNull
    public String summaryLine() {
        String who = startedBy == null || startedBy.isBlank() ? "unknown" : startedBy;
        String kindLabel = kind == Kind.REVIEW ? "Interactive view" : "Interactive input";
        return kindLabel + " · " + jobFullName + " #" + buildNumber + " · started by " + who + " · " + status;
    }
}
