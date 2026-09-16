// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.SecurityRealm;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import io.jenkins.plugins.interactiveinput.util.CauseResolver;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.IdStrategy;
import jenkins.model.Jenkins;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * In-memory registry of {@link ReviewDocument}s published by the {@code interactiveView} step, with
 * XStream persistence of the metadata to {@code $JENKINS_HOME/interactive-input/views.xml} and the
 * content versions stored as separate files under {@code $JENKINS_HOME/interactive-input/views/<id>/}.
 *
 * <p>Deliberately mirrors {@link QuestionStore}: a {@link ConcurrentHashMap} of documents, all
 * transitions on a single document serialised via {@code synchronized (doc)}, a transient resolver
 * map that resumes a paused {@code wait:true} step when a decision is recorded, and SLA/retention
 * driven by the shared {@code SlaTicker}. Keeping content out of the XML (in per-version files) keeps
 * the metadata file small even for large reports.
 */
@Extension
public class ViewStore {

    private static final Logger LOGGER = Logger.getLogger(ViewStore.class.getName());

    /** Callback invoked once when a blocking review reaches a decided (or expired) state. */
    @FunctionalInterface
    public interface Resolution {
        void onResolved(@NonNull ReviewDocument doc);
    }

    private final Map<String, ReviewDocument> docs = new ConcurrentHashMap<>();

    /** Transient (in-memory only) resolvers for currently-attached paused ({@code wait:true}) steps. */
    private final transient Map<String, Resolution> resolvers = new ConcurrentHashMap<>();

    public ViewStore() {
        load();
    }

    @NonNull
    public static ViewStore get() {
        return ExtensionList.lookupSingleton(ViewStore.class);
    }

    // ----------------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------------

    /**
     * Register a new review document and persist its metadata plus the original content snapshot.
     *
     * @param doc     the document metadata (its version 1 is the original snapshot)
     * @param content the snapshotted file content (UTF-8 text)
     * @return the same document for convenience
     */
    @NonNull
    public ReviewDocument submit(@NonNull ReviewDocument doc, @NonNull String content) {
        writeContent(doc.getId(), 1, content);
        docs.put(doc.getId(), doc);
        save();
        LOGGER.log(Level.FINE, "published review {0} for {1} #{2}", new Object[] {
            doc.getId(), doc.getJobFullName(), doc.getBuildNumber()
        });
        fireSubmitted(doc);
        return doc;
    }

    /**
     * Attach a paused ({@code wait:true}) step's resolver. If the document has already been decided or
     * expired (e.g. it settled while the step was resuming after a restart), the resolver runs at once.
     */
    public void register(@NonNull String id, @NonNull Resolution resolution) {
        resolvers.put(id, resolution);
        ReviewDocument doc = docs.get(id);
        if (doc != null && doc.getStatus().isDecided()) {
            resolvers.remove(id);
            resolution.onResolved(doc);
        }
    }

    public void unregister(@NonNull String id) {
        resolvers.remove(id);
    }

    private void resolve(@NonNull ReviewDocument doc) {
        Resolution r = resolvers.remove(doc.getId());
        if (r != null) {
            try {
                r.onResolved(doc);
            } catch (RuntimeException x) {
                LOGGER.log(Level.WARNING, "resolver for review " + doc.getId() + " threw", x);
            }
        }
    }

    @CheckForNull
    public ReviewDocument get(@NonNull String id) {
        return docs.get(id);
    }

    /** Remove a document outright (metadata + all content versions). */
    public void remove(@NonNull String id) {
        resolvers.remove(id);
        if (docs.remove(id) != null) {
            deleteContentDir(id);
            save();
        }
    }

    @NonNull
    private ReviewDocument require(@NonNull String id) {
        ReviewDocument doc = docs.get(id);
        if (doc == null) {
            throw new IllegalStateException("No such review: " + id);
        }
        return doc;
    }

    /**
     * Add a root comment. Callers must pre-check permissions (the REST/UI layer's responsibility).
     *
     * @throws IllegalStateException if the document is missing or not commentable
     */
    @NonNull
    public ReviewComment addComment(@NonNull String id, int line, @NonNull String body, @NonNull String byUserId) {
        return addComment(id, line, body, byUserId, null, null);
    }

    /**
     * Add a comment, optionally as a threaded reply and/or with a display-only author label. Callers must
     * pre-check permissions (the REST/UI layer's responsibility). The {@code byUserId} audit author is
     * always the real, server-set identity; {@code authorLabel} only changes what the viewer displays.
     *
     * <p>Threads are kept one level deep: a reply whose {@code parentId} points at another reply is
     * re-parented to that reply's root, so every stored reply references a root comment and the viewer can
     * render a simple parent&rarr;replies tree.
     *
     * @param parentId    the comment being replied to, or {@code null} for a root comment
     * @param authorLabel display-only label (e.g. "AI response"), or {@code null} to show the real author
     * @throws IllegalStateException    if the document is missing or not commentable
     * @throws IllegalArgumentException if {@code parentId} does not reference an existing comment
     */
    @NonNull
    public ReviewComment addComment(
            @NonNull String id,
            int line,
            @NonNull String body,
            @NonNull String byUserId,
            @CheckForNull String parentId,
            @CheckForNull String authorLabel) {
        return addComment(id, line, body, byUserId, parentId, authorLabel, null);
    }

    /**
     * Add a comment that also records the verbatim {@code quote} the reviewer highlighted (see the viewer's
     * highlight-select flow). Anchoring still uses {@code line}; {@code quote} is display-only context, so a
     * value is kept even when it is a sub-phrase of the line or spans several lines. Callers must pre-check
     * permissions (the REST/UI layer's responsibility).
     *
     * @param quote the highlighted snippet to show back verbatim, or {@code null} for the "+"/general path
     * @throws IllegalStateException    if the document is missing or not commentable
     * @throws IllegalArgumentException if {@code parentId} does not reference an existing comment
     */
    @NonNull
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReviewComment addComment(
            @NonNull String id,
            int line,
            @NonNull String body,
            @NonNull String byUserId,
            @CheckForNull String parentId,
            @CheckForNull String authorLabel,
            @CheckForNull String quote) {
        ReviewDocument doc = require(id);
        ReviewComment c;
        synchronized (doc) {
            if (!doc.isCommentable()) {
                throw new IllegalStateException("Review " + id + " is not commentable");
            }
            String effectiveParent = null;
            if (parentId != null) {
                ReviewComment parent = doc.findComment(parentId);
                if (parent == null) {
                    throw new IllegalArgumentException("No such parent comment: " + parentId);
                }
                // Flatten reply-to-a-reply onto the same root (keeps threads one level deep).
                effectiveParent = parent.getParentId() != null ? parent.getParentId() : parentId;
            }
            c = new ReviewComment(
                    UUID.randomUUID().toString(),
                    line,
                    body,
                    byUserId,
                    System.currentTimeMillis(),
                    effectiveParent,
                    authorLabel,
                    quote);
            doc.addComment(c);
        }
        save();
        LOGGER.log(Level.FINE, "review {0} commented by {1} (line={2}, parent={3})", new Object[] {
            id, byUserId, line, parentId
        });
        return c;
    }

    /**
     * Toggle a comment's resolved flag.
     *
     * @throws IllegalStateException if the document or comment is missing
     */
    public void setCommentResolved(@NonNull String id, @NonNull String commentId, boolean resolved) {
        ReviewDocument doc = require(id);
        synchronized (doc) {
            ReviewComment c = doc.findComment(commentId);
            if (c == null) {
                throw new IllegalStateException("No such comment: " + commentId);
            }
            c.setResolved(resolved);
        }
        save();
    }

    /**
     * Save an edit to the durable review copy as a new content version with the default "edited" note.
     *
     * @return the new version index
     * @throws IllegalStateException if the document is missing, not editable, or in a final decided state
     *     (any decision other than {@code CHANGES_REQUESTED}; see {@link ReviewStatus#allowsEdit()})
     */
    public int saveEdit(@NonNull String id, @NonNull String newContent, @NonNull String byUserId) {
        return saveEdit(id, newContent, byUserId, null);
    }

    /**
     * Save an edit to the durable review copy as a new content version, recording an optional display-only
     * {@code note} (e.g. an AI course-correction summary) in the version history. Callers must pre-check
     * permissions and that the document is editable.
     *
     * @param note a short edit summary shown in the version dropdown, or {@code null} for the default label
     * @return the new version index
     * @throws IllegalStateException if the document is missing, not editable, or in a final decided state
     *     (any decision other than {@code CHANGES_REQUESTED}; see {@link ReviewStatus#allowsEdit()})
     */
    public int saveEdit(
            @NonNull String id, @NonNull String newContent, @NonNull String byUserId, @CheckForNull String note) {
        ReviewDocument doc = require(id);
        int version;
        synchronized (doc) {
            if (!doc.isEditable()) {
                throw new IllegalStateException("Review " + id + " is not editable");
            }
            // OPEN and CHANGES_REQUESTED both permit edits: the latter is the "please course-correct"
            // state that the regenerate loop targets. Every other terminal decision is final/read-only.
            if (!doc.getStatus().allowsEdit()) {
                throw new IllegalStateException("Review " + id + " is already " + doc.getStatus());
            }
            version = doc.getCurrentVersion() + 1;
            writeContent(id, version, newContent);
            doc.addVersion(byUserId, System.currentTimeMillis(), note);
        }
        save();
        LOGGER.log(Level.FINE, "review {0} edited by {1} (v{2})", new Object[] {id, byUserId, version});
        return version;
    }

    /**
     * Record a reviewer decision and, for a blocking view, resume the paused pipeline. Callers must
     * pre-check permissions.
     *
     * @throws IllegalStateException if the document is missing or already decided
     */
    public void decide(
            @NonNull String id, @NonNull ReviewStatus decision, @NonNull String byUserId, @NonNull String source) {
        if (!decision.isDecided() || decision == ReviewStatus.EXPIRED) {
            throw new IllegalArgumentException("Invalid decision: " + decision);
        }
        ReviewDocument doc = require(id);
        synchronized (doc) {
            if (doc.getStatus().isDecided()) {
                throw new IllegalStateException("Review " + id + " is already " + doc.getStatus());
            }
            doc.markDecided(decision, byUserId, System.currentTimeMillis());
        }
        save();
        LOGGER.log(Level.INFO, "review {0} {1} by {2} via {3}", new Object[] {id, decision, byUserId, source});
        if (doc.isBlocking()) {
            resolve(doc);
        }
    }

    /**
     * Mark an open blocking review as {@code ABORTED} without firing its resolver. Used when the owning
     * step is stopped (build aborted): the framework already delivers the abort to the pipeline, so we
     * only need to move the document to a terminal, compactable state.
     */
    public void abandonForShutdown(@NonNull String id) {
        ReviewDocument doc = docs.get(id);
        if (doc == null) {
            return;
        }
        resolvers.remove(id);
        synchronized (doc) {
            if (doc.getStatus().isDecided()) {
                return;
            }
            doc.markAborted();
        }
        save();
        LOGGER.log(Level.FINE, "review {0} abandoned (build stopped)", id);
    }

    /** Expire a single overdue blocking review (used by the SLA ticker). */
    void expire(@NonNull String id) {
        ReviewDocument doc = docs.get(id);
        if (doc == null) {
            return;
        }
        synchronized (doc) {
            if (doc.getStatus().isDecided()) {
                return;
            }
            doc.markExpired();
        }
        save();
        LOGGER.log(Level.INFO, "review {0} expired (SLA elapsed)", id);
        if (doc.isBlocking()) {
            resolve(doc);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Content persistence (per-version files kept out of the metadata XML)
    // ----------------------------------------------------------------------------------------

    /** @return the content of a specific version as UTF-8 text, or {@code null} if unavailable. */
    @CheckForNull
    public String readContent(@NonNull String id, int version) {
        File f = contentFile(id, version);
        if (!f.isFile()) {
            return null;
        }
        try {
            return Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "failed to read review content " + id + " v" + version);
            return null;
        }
    }

    /** @return the current version's content as UTF-8 text, or {@code null} if unavailable. */
    @CheckForNull
    public String readCurrentContent(@NonNull String id) {
        ReviewDocument doc = docs.get(id);
        return doc == null ? null : readContent(id, doc.getCurrentVersion());
    }

    private void writeContent(@NonNull String id, int version, @NonNull String content) {
        File f = contentFile(id, version);
        try {
            Files.createDirectories(f.getParentFile().toPath());
            Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "failed to persist review content " + id + " v" + version);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------

    /** @return every OPEN review the current user can read (admin {@code ?all=true} list). */
    @NonNull
    public List<ReviewDocument> listOpenReadable() {
        List<ReviewDocument> out = new ArrayList<>();
        for (ReviewDocument d : docs.values()) {
            if (d.getStatus() == ReviewStatus.OPEN && canView(d)) {
                out.add(d);
            }
        }
        return out;
    }

    // ---- Notification-centre queries (user-scope aware) ----
    // These drive the bell dropdown/badge, the per-project sidebar count and the build-history badge.
    // They mirror QuestionStore's notification predicate: the "show each user only their own build's
    // notifications" switch limits them to the current user's own builds (plus ownerless SCM/timer/
    // upstream/system reviews). The base visibility stays Item.READ ({@link #canView}) so a review is
    // still surfaced to any reader when the switch is off (unchanged behaviour); the separate
    // lock-to-build-starter switch only restricts *contributing* (see {@link #canContributeEffective}),
    // matching the "others can view" semantics.

    /** @return OPEN, notify-enabled reviews to surface for the current user across all jobs. */
    @NonNull
    public List<ReviewDocument> listNotifications() {
        return collectNotifications(null);
    }

    /** @return OPEN, notify-enabled reviews to surface for the current user, scoped to one job. */
    @NonNull
    public List<ReviewDocument> listNotificationsForJob(@NonNull String jobFullName) {
        return collectNotifications(jobFullName);
    }

    public int countNotificationsForJob(@NonNull String jobFullName) {
        return listNotificationsForJob(jobFullName).size();
    }

    /**
     * @return the number of OPEN, notify-enabled reviews to surface for the current user on a single
     *     build, honouring the user-scope switch. Drives the run-page sidebar count badge (the
     *     per-build equivalent of {@link #countNotificationsForJob(String)}).
     */
    public int countNotificationsForBuild(@NonNull String jobFullName, int buildNumber) {
        boolean userScoped = InteractiveInputGlobalConfig.userScopedNotificationsEnabled();
        String uid = currentUserId();
        int count = 0;
        for (ReviewDocument d : docs.values()) {
            if (isBuildNotification(d, jobFullName, buildNumber, uid, userScoped)) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return {@code true} if the build has an OPEN, notify-enabled review the current user should be
     *     notified of (drives the build-history badge, honouring the user-scope switch).
     */
    public boolean hasNotificationForBuild(@NonNull String jobFullName, int buildNumber) {
        boolean userScoped = InteractiveInputGlobalConfig.userScopedNotificationsEnabled();
        String uid = currentUserId();
        for (ReviewDocument d : docs.values()) {
            if (isBuildNotification(d, jobFullName, buildNumber, uid, userScoped)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBuildNotification(
            @NonNull ReviewDocument d,
            @NonNull String jobFullName,
            int buildNumber,
            @NonNull String uid,
            boolean userScoped) {
        return d.getStatus() == ReviewStatus.OPEN
                && d.isNotify()
                && !d.isBuildDeleted()
                && jobFullName.equals(d.getJobFullName())
                && d.getBuildNumber() == buildNumber
                && canView(d)
                && (!userScoped || isOwnedByOrShared(d, uid));
    }

    @NonNull
    private List<ReviewDocument> collectNotifications(@CheckForNull String jobFullName) {
        boolean userScoped = InteractiveInputGlobalConfig.userScopedNotificationsEnabled();
        String uid = currentUserId();
        List<ReviewDocument> out = new ArrayList<>();
        for (ReviewDocument d : docs.values()) {
            if (d.getStatus() != ReviewStatus.OPEN || !d.isNotify() || d.isBuildDeleted()) {
                continue;
            }
            if (jobFullName != null && !jobFullName.equals(d.getJobFullName())) {
                continue;
            }
            if (!canView(d)) {
                continue;
            }
            if (userScoped && !isOwnedByOrShared(d, uid)) {
                continue;
            }
            out.add(d);
        }
        return out;
    }

    /** @return {@code true} if the build has no human starter (shared) or was started by {@code uid}. */
    private boolean isOwnedByOrShared(@NonNull ReviewDocument d, @NonNull String uid) {
        String owner = d.getCreatedBy();
        if (!CauseResolver.isRealUser(owner)) {
            return true;
        }
        return userIdEquals(owner, uid);
    }

    /** @return every review (any status) for {@code jobFullName} the current user can read. */
    @NonNull
    public List<ReviewDocument> listForJob(@NonNull String jobFullName) {
        List<ReviewDocument> out = new ArrayList<>();
        for (ReviewDocument d : docs.values()) {
            if (jobFullName.equals(d.getJobFullName()) && canView(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** @return every review (any status) for a specific build the current user can read. */
    @NonNull
    public List<ReviewDocument> listForBuild(@NonNull String jobFullName, int buildNumber) {
        List<ReviewDocument> out = new ArrayList<>();
        for (ReviewDocument d : docs.values()) {
            if (jobFullName.equals(d.getJobFullName()) && d.getBuildNumber() == buildNumber && canView(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * Existence probe (no permission filter) used by the run-action factory. Anyone reaching a build
     * page already holds {@code Item.READ}, so this leaks nothing the permission-checked page would not.
     *
     * @return {@code true} if any review (any status) is recorded for this build.
     */
    public boolean hasAnyForBuild(@NonNull String jobFullName, int buildNumber) {
        for (ReviewDocument d : docs.values()) {
            if (jobFullName.equals(d.getJobFullName()) && d.getBuildNumber() == buildNumber) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------------------------------
    // Permissions (mirrors QuestionStore#canView / #canAnswer)
    // ----------------------------------------------------------------------------------------

    /** @return {@code true} if the current authentication can read the source job (Item.READ). */
    public boolean canView(@NonNull ReviewDocument doc) {
        Job<?, ?> job = findJob(doc);
        return job != null && job.hasPermission(Item.READ);
    }

    /**
     * @return {@code true} if the current authentication may contribute (comment / edit / decide):
     *     {@code Item.BUILD} on the job, or membership in the document's {@code submitterFilter} (with
     *     administrators always allowed). Mirrors {@link QuestionStore#canAnswer}.
     */
    public boolean canContribute(@NonNull ReviewDocument doc) {
        Job<?, ?> job = findJob(doc);
        if (job == null) {
            return false;
        }
        String submitter = doc.getSubmitterFilter();
        if (submitter == null || submitter.trim().isEmpty()) {
            return job.hasPermission(Item.BUILD);
        }
        Jenkins j = Jenkins.get();
        if (!j.isUseSecurity() || j.hasPermission(Jenkins.ADMINISTER)) {
            return true;
        }
        return isSubmitter(submitter, Jenkins.getAuthentication2());
    }

    /**
     * @return {@code true} if the current authentication may contribute (comment / edit / decide)
     *     <em>after</em> applying the lock-to-build-starter switch. This is
     *     {@link #canContribute(ReviewDocument)} unless the switch is on, in which case only the build
     *     starter (or a Jenkins administrator) may contribute; reviews with no human starter are never
     *     locked. This is the check the REST mutation endpoints and the page's {@code canContribute}
     *     flag use — it can only restrict, never widen, access. Mirrors
     *     {@link QuestionStore#canAnswerEffective}, so "only the build starter may answer (others can
     *     view)" governs reviews exactly as it governs questions.
     */
    public boolean canContributeEffective(@NonNull ReviewDocument doc) {
        if (!canContribute(doc)) {
            return false;
        }
        if (!InteractiveInputGlobalConfig.lockToBuildStarterEnabled()) {
            return true;
        }
        Jenkins j = Jenkins.get();
        if (!j.isUseSecurity() || j.hasPermission(Jenkins.ADMINISTER)) {
            return true; // admins can always contribute (safety valve)
        }
        String owner = doc.getCreatedBy();
        if (!CauseResolver.isRealUser(owner)) {
            return true; // no human starter to lock to
        }
        return userIdEquals(owner, currentUserId());
    }

    /** Compare two user ids using the security realm's id strategy (case handling per realm). */
    private boolean userIdEquals(@NonNull String a, @NonNull String b) {
        return Jenkins.get().getSecurityRealm().getUserIdStrategy().equals(a, b);
    }

    @CheckForNull
    public Job<?, ?> findJob(@NonNull ReviewDocument doc) {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        return j.getItemByFullName(doc.getJobFullName(), Job.class);
    }

    private boolean isSubmitter(@NonNull String submitter, @NonNull Authentication a) {
        Set<String> submitters = new HashSet<>();
        Collections.addAll(submitters, submitter.split(","));
        SecurityRealm realm = Jenkins.get().getSecurityRealm();
        if (isMemberOf(a.getName(), submitters, realm.getUserIdStrategy())) {
            return true;
        }
        for (GrantedAuthority ga : a.getAuthorities()) {
            if (isMemberOf(ga.getAuthority(), submitters, realm.getGroupIdStrategy())) {
                return true;
            }
        }
        return false;
    }

    private boolean isMemberOf(String userId, Set<String> submitters, IdStrategy idStrategy) {
        for (String submitter : submitters) {
            if (idStrategy.equals(userId, submitter.trim())) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------------------------------
    // SLA + retention (driven by SlaTicker)
    // ----------------------------------------------------------------------------------------

    /** Expire every overdue blocking review. Called by the SLA ticker. */
    public void expireOverdue(long now) {
        for (ReviewDocument d : docs.values()) {
            if (d.getStatus() == ReviewStatus.OPEN && d.isBlocking() && d.isExpired(now)) {
                expire(d.getId());
            }
        }
    }

    /** Remove decided reviews older than {@code retentionMs}. Called by the SLA ticker. */
    public void compact(long now, long retentionMs) {
        if (retentionMs <= 0) {
            return;
        }
        for (ReviewDocument d : new ArrayList<>(docs.values())) {
            if (d.getStatus().isDecided()) {
                long ref = d.getDecidedTs() > 0 ? d.getDecidedTs() : d.getCreatedTs();
                if (now - ref > retentionMs) {
                    remove(d.getId());
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Build lifecycle (driven by BuildLifecycleCleanup)
    // ----------------------------------------------------------------------------------------

    /**
     * Mark every review of a now-deleted build as build-deleted. The reviews are <em>kept</em> as durable
     * audit records (their comment/decision history outlives the build), but they drop out of the
     * notification centre and the sidebar/badge counts and the per-job page renders them as "build
     * deleted". Invoked by the {@code RunListener} when a build is deleted.
     *
     * @return the number of reviews newly marked (0 if none matched or all were already marked).
     */
    public int markBuildDeletedForBuild(@NonNull String jobFullName, int buildNumber) {
        int marked = 0;
        for (ReviewDocument d : docs.values()) {
            if (!jobFullName.equals(d.getJobFullName()) || d.getBuildNumber() != buildNumber || d.isBuildDeleted()) {
                continue;
            }
            synchronized (d) {
                if (!d.isBuildDeleted()) {
                    d.markBuildDeleted();
                    marked++;
                }
            }
        }
        if (marked > 0) {
            save();
            LOGGER.log(Level.FINE, "marked {0} review(s) build-deleted for {1} #{2}", new Object[] {
                marked, jobFullName, buildNumber
            });
        }
        return marked;
    }

    /**
     * Startup self-heal: mark reviews whose owning build no longer exists (deleted before this cleanup
     * shipped, or while the controller was down). Only acts when the job still resolves but the build is
     * gone, so a temporarily-unresolvable job (folder still loading, security, etc.) never loses its
     * reviews. Invoked once from {@code BuildLifecycleCleanup}'s {@code onLoaded}.
     *
     * @return the number of reviews newly marked.
     */
    public int reconcileDeletedBuilds() {
        int marked = 0;
        for (ReviewDocument d : docs.values()) {
            if (d.isBuildDeleted()) {
                continue;
            }
            Job<?, ?> job = findJob(d);
            if (job == null || job.getBuildByNumber(d.getBuildNumber()) != null) {
                continue;
            }
            synchronized (d) {
                if (!d.isBuildDeleted()) {
                    d.markBuildDeleted();
                    marked++;
                }
            }
        }
        if (marked > 0) {
            save();
            LOGGER.log(Level.INFO, "reconciled {0} review(s) whose owning build was deleted", marked);
        }
        return marked;
    }

    // ----------------------------------------------------------------------------------------
    // Persistence
    // ----------------------------------------------------------------------------------------

    @NonNull
    private File baseDir() {
        return new File(Jenkins.get().getRootDir(), "interactive-input");
    }

    @NonNull
    private XmlFile getConfigFile() {
        return new XmlFile(Jenkins.XSTREAM2, new File(baseDir(), "views.xml"));
    }

    @NonNull
    private File contentDir(@NonNull String id) {
        if (!isSafeId(id)) {
            throw new IllegalArgumentException("Unsafe review id");
        }
        return new File(new File(baseDir(), "views"), id);
    }

    @NonNull
    private File contentFile(@NonNull String id, int version) {
        return new File(contentDir(id), "v" + Math.max(1, version) + ".txt");
    }

    /** Guard against path traversal: server-generated ids are UUIDs, so only allow that alphabet. */
    private static boolean isSafeId(@CheckForNull String id) {
        return id != null && !id.isEmpty() && id.length() <= 64 && id.matches("[A-Za-z0-9_-]+");
    }

    private void deleteContentDir(@NonNull String id) {
        if (!isSafeId(id)) {
            return;
        }
        try {
            Util.deleteRecursive(contentDir(id));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "failed to delete review content dir for " + id);
        }
    }

    private void fireSubmitted(@NonNull ReviewDocument doc) {
        for (ViewStoreListener l : ViewStoreListener.all()) {
            try {
                l.onSubmitted(doc);
            } catch (RuntimeException x) {
                LOGGER.log(Level.WARNING, "ViewStoreListener " + l.getClass().getName() + " threw", x);
            }
        }
    }

    private synchronized void save() {
        if (Jenkins.getInstanceOrNull() == null) {
            return;
        }
        try {
            XmlFile f = getConfigFile();
            f.mkdirs();
            f.write(new ArrayList<>(docs.values()));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "failed to persist interactive-input reviews", e);
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void load() {
        if (Jenkins.getInstanceOrNull() == null) {
            return;
        }
        XmlFile f = getConfigFile();
        if (!f.exists()) {
            return;
        }
        try {
            Object data = f.read();
            docs.clear();
            if (data instanceof List) {
                for (Object o : (List<Object>) data) {
                    if (o instanceof ReviewDocument) {
                        ReviewDocument d = (ReviewDocument) o;
                        docs.put(d.getId(), d);
                    }
                }
            }
            LOGGER.log(Level.FINE, "loaded {0} interactive-input reviews", docs.size());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "failed to load interactive-input reviews", e);
        }
    }

    /** @return the user id of the current authentication, or {@code "SYSTEM"} if unauthenticated. */
    @NonNull
    public static String currentUserId() {
        return QuestionStore.currentUserId();
    }
}
