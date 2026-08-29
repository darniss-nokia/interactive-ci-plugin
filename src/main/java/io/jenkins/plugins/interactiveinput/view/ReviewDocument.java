// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * A durable, reviewable snapshot of a file published by the {@code interactiveView} pipeline step.
 *
 * <p>At step time the named file's content is copied ("snapshotted") into the plugin store so the
 * review still works after the workspace/build is cleaned. Reviewers can add {@link ReviewComment}s,
 * edit the durable review <em>copy</em> (each edit is a new {@link ContentVersion}; the original
 * workspace file is never touched), and record a {@link ReviewStatus} decision. For a blocking
 * ({@code wait:true}) view the decision resumes the paused pipeline.
 *
 * <p>Only metadata (this object, including comments and version metadata) is persisted via XStream to
 * {@code views.xml}; the version bytes live in per-document files. State transitions are guarded by
 * {@code ViewStore}, which synchronises on the individual document instance.
 */
public class ReviewDocument implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Content format; drives how the review page renders and highlights the snapshot. */
    public static final String FORMAT_MARKDOWN = "markdown";

    public static final String FORMAT_HTML = "html";
    public static final String FORMAT_TEXT = "text";
    public static final String FORMAT_CODE = "code";

    /** Review mode: a decision (approve/reject/acknowledge/request-changes) is required. */
    public static final String MODE_REVIEW = "review";

    /** Informational mode: a read-only viewer (still commentable) with no decision. */
    public static final String MODE_INFO = "info";

    @NonNull
    private final String id;

    @NonNull
    private final String jobFullName;

    private final int buildNumber;

    @NonNull
    private final String reportName;

    @NonNull
    private final String title;

    @NonNull
    private final String fileName;

    @NonNull
    private final String format;

    @NonNull
    private final String language;

    @NonNull
    private final String createdBy;

    private final long createdTs;

    private final boolean commentable;

    private final boolean editable;

    /** {@code true} if this review should appear in the notification bell (does not affect the page). */
    private final boolean notify;

    /** {@code true} when a {@code wait:true} step is paused on this document. */
    private final boolean blocking;

    @CheckForNull
    private final String submitterFilter;

    /**
     * Grouping key shared by files published together in one {@code interactiveView} call (a glob or
     * {@code dir}); {@code null} for a lone file. XStream-safe: absent in legacy {@code views.xml}, so
     * {@link #getGroupId()} falls back to this document's own id (a singleton group).
     */
    @CheckForNull
    private final String groupId;

    /**
     * {@link #MODE_REVIEW} (a decision is required) or {@link #MODE_INFO} (read-only viewer, still
     * commentable, no decision). XStream-safe: {@code null} in legacy documents → treated as review.
     */
    @CheckForNull
    private final String mode;

    /** SLA in milliseconds for a blocking view; {@code 0} means no SLA. */
    private final long slaMs;

    /** Epoch millis when a blocking view expires, or {@code 0} if no SLA. */
    private final long expiresAt;

    @NonNull
    private ReviewStatus status;

    @CheckForNull
    private String decidedBy;

    private long decidedTs;

    /** 1-based index of the latest content version. */
    private int currentVersion;

    /**
     * {@code true} once the owning build has been deleted. The review is kept as a durable audit record
     * (its comment/decision history survives the build), but it no longer generates a notification and
     * the per-job page shows it as "build deleted". XStream-safe: absent in legacy {@code views.xml}, so
     * it deserialises to {@code false} (the pre-existing "still live" behaviour).
     */
    private boolean buildDeleted;

    @NonNull
    private final List<ReviewComment> comments = new ArrayList<>();

    @NonNull
    private final List<ContentVersion> versions = new ArrayList<>();

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReviewDocument(
            @NonNull String id,
            @NonNull String jobFullName,
            int buildNumber,
            @NonNull String reportName,
            @NonNull String title,
            @NonNull String fileName,
            @NonNull String format,
            @NonNull String language,
            @NonNull String createdBy,
            long createdTs,
            boolean commentable,
            boolean editable,
            boolean notify,
            boolean blocking,
            @CheckForNull String submitterFilter,
            long slaMs,
            @CheckForNull String groupId,
            @CheckForNull String mode) {
        this.id = id;
        this.jobFullName = jobFullName;
        this.buildNumber = buildNumber;
        this.reportName = reportName;
        this.title = title;
        this.fileName = fileName;
        this.format = format;
        this.language = language;
        this.createdBy = createdBy;
        this.createdTs = createdTs;
        this.commentable = commentable;
        this.editable = editable;
        this.notify = notify;
        this.blocking = blocking;
        this.submitterFilter = submitterFilter;
        this.groupId = groupId != null && !groupId.trim().isEmpty() ? groupId.trim() : null;
        this.mode = MODE_INFO.equals(mode) ? MODE_INFO : (mode != null ? MODE_REVIEW : null);
        this.slaMs = Math.max(0L, slaMs);
        this.expiresAt = blocking && this.slaMs > 0 ? createdTs + this.slaMs : 0L;
        this.status = ReviewStatus.OPEN;
        this.currentVersion = 1;
        this.versions.add(new ContentVersion(1, createdBy, createdTs, "original snapshot"));
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

    @NonNull
    public String getReportName() {
        return reportName;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    @NonNull
    public String getFileName() {
        return fileName;
    }

    @NonNull
    public String getFormat() {
        return format;
    }

    @NonNull
    public String getLanguage() {
        return language;
    }

    @NonNull
    public String getCreatedBy() {
        return createdBy;
    }

    public long getCreatedTs() {
        return createdTs;
    }

    public boolean isCommentable() {
        return commentable;
    }

    public boolean isEditable() {
        return editable;
    }

    public boolean isNotify() {
        return notify;
    }

    public boolean isBlocking() {
        return blocking;
    }

    @CheckForNull
    public String getSubmitterFilter() {
        return submitterFilter;
    }

    /**
     * @return the grouping key for files published together, or this document's own id when it was
     *     published on its own — so callers can always group by a stable, non-null key. Legacy
     *     documents (no stored group) return their id and therefore form singleton groups.
     */
    @NonNull
    public String getGroupId() {
        return groupId != null && !groupId.isEmpty() ? groupId : id;
    }

    /** @return {@code true} if this review was published as one of a multi-file group (glob/dir). */
    public boolean isGrouped() {
        return groupId != null && !groupId.isEmpty();
    }

    /**
     * @return {@link #MODE_INFO} for a read-only informational viewer, otherwise {@link #MODE_REVIEW}
     *     (a decision is required). Legacy documents with no stored mode are treated as review.
     */
    @NonNull
    public String getMode() {
        return MODE_INFO.equals(mode) ? MODE_INFO : MODE_REVIEW;
    }

    /** @return {@code true} if this review needs an approve/reject/acknowledge decision (review mode). */
    public boolean isDecisionRequired() {
        return MODE_REVIEW.equals(getMode());
    }

    public long getSlaMs() {
        return slaMs;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    @NonNull
    public ReviewStatus getStatus() {
        return status;
    }

    @CheckForNull
    public String getDecidedBy() {
        return decidedBy;
    }

    public long getDecidedTs() {
        return decidedTs;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    /**
     * @return {@code true} if the owning build has been deleted. The review is retained (its history is
     *     durable) but is excluded from the notification centre and the sidebar/badge counts, and the
     *     per-job interactive-view page renders it as "build deleted" without a live build link.
     */
    public boolean isBuildDeleted() {
        return buildDeleted;
    }

    /** @return an unmodifiable view of the comments (never {@code null}). */
    @NonNull
    public List<ReviewComment> getComments() {
        return Collections.unmodifiableList(comments);
    }

    /** @return the number of comments (convenience for Jelly views). */
    public int getCommentCount() {
        return comments.size();
    }

    /** @return the creation time as a {@link java.util.Date} (convenience for Jelly date formatting). */
    @NonNull
    public java.util.Date getCreatedDate() {
        return new java.util.Date(createdTs);
    }

    /** @return an unmodifiable view of the content-version metadata (never {@code null}). */
    @NonNull
    public List<ContentVersion> getVersions() {
        return Collections.unmodifiableList(versions);
    }

    // ---- Store-only mutators (invoked by ViewStore while holding this document's monitor) ----

    void addComment(@NonNull ReviewComment comment) {
        comments.add(comment);
    }

    @CheckForNull
    ReviewComment findComment(@NonNull String commentId) {
        for (ReviewComment c : comments) {
            if (c.getId().equals(commentId)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Record a new content version (the review copy edit) and make it current. A blank/{@code null} note
     * falls back to the default "edited" label so the version dropdown always shows something meaningful.
     */
    void addVersion(@NonNull String editedBy, long editedTs, @CheckForNull String note) {
        int next = currentVersion + 1;
        String label = (note == null || note.trim().isEmpty()) ? "edited" : note.trim();
        versions.add(new ContentVersion(next, editedBy, editedTs, label));
        currentVersion = next;
    }

    void markDecided(@NonNull ReviewStatus decision, @NonNull String by, long ts) {
        this.status = decision;
        this.decidedBy = by;
        this.decidedTs = ts;
    }

    void markExpired() {
        this.status = ReviewStatus.EXPIRED;
        this.decidedTs = System.currentTimeMillis();
    }

    void markAborted() {
        this.status = ReviewStatus.ABORTED;
        this.decidedTs = System.currentTimeMillis();
    }

    /**
     * Mark the owning build as deleted (idempotent). Invoked by {@link ViewStore} while holding this
     * document's monitor. Does not change {@link #status}: a decided review stays decided, an open one
     * stays open — it is simply no longer surfaced as a notification.
     */
    void markBuildDeleted() {
        this.buildDeleted = true;
    }

    /** @return {@code true} if a blocking SLA is configured and it has elapsed relative to {@code now}. */
    public boolean isExpired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }

    /** @return remaining SLA milliseconds ({@code -1} when no SLA, {@code 0} when already due). */
    public long remainingMs(long now) {
        if (expiresAt <= 0) {
            return -1L;
        }
        return Math.max(0L, expiresAt - now);
    }

    /**
     * Serialise the document metadata to JSON for the REST API and the review page.
     *
     * @param includeComments whether to include the comment list (raw markdown bodies; the caller adds
     *     sanitised {@code bodyHtml})
     * @param now             reference time for {@code remainingMs}
     */
    @NonNull
    public JSONObject toJson(boolean includeComments, long now) {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("jobFullName", jobFullName);
        o.put("buildNumber", buildNumber);
        o.put("reportName", reportName);
        o.put("title", title);
        o.put("fileName", fileName);
        o.put("format", format);
        o.put("language", language);
        o.put("createdBy", createdBy);
        o.put("createdTs", createdTs);
        o.put("commentable", commentable);
        o.put("editable", editable);
        o.put("notify", notify);
        o.put("blocking", blocking);
        o.put("groupId", getGroupId());
        o.put("grouped", isGrouped());
        o.put("mode", getMode());
        o.put("status", status.name());
        o.put("decidedBy", decidedBy == null ? "" : decidedBy);
        o.put("decidedTs", decidedTs);
        o.put("buildDeleted", buildDeleted);
        o.put("currentVersion", currentVersion);
        o.put("slaMs", slaMs);
        o.put("expiresAt", expiresAt);
        o.put("remainingMs", remainingMs(now));
        o.put("commentCount", comments.size());
        JSONArray vers = new JSONArray();
        for (ContentVersion v : versions) {
            vers.add(v.toJson());
        }
        o.put("versions", vers);
        if (includeComments) {
            JSONArray arr = new JSONArray();
            for (ReviewComment c : comments) {
                arr.add(c.toJson());
            }
            o.put("comments", arr);
        }
        return o;
    }
}
