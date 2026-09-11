// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.User;
import hudson.security.SecurityRealm;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.model.Answer;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.model.QuestionStatus;
import io.jenkins.plugins.interactiveinput.util.CauseResolver;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.IdStrategy;
import jenkins.model.Jenkins;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * In-memory registry of human-in-the-loop {@link Question}s with XStream persistence to
 * {@code $JENKINS_HOME/interactive-input/questions.xml} (§8.1, §8.2).
 *
 * <p>The store is the single authority for question lifecycle and permissions. Paused pipeline steps
 * register a transient {@link Resolution} keyed by question id; when a question reaches a terminal
 * state (answered, aborted, expired) the store invokes that resolution so the pipeline resumes. The
 * question metadata survives a Jenkins restart via XStream; the resolution is re-registered when the
 * step's {@code onResume} runs.
 *
 * <p>Concurrency: the questions map is a {@link ConcurrentHashMap}; all transitions on a single
 * {@link Question} are serialised via {@code synchronized (question)} (§8.9).
 */
@Extension
public class QuestionStore {

    private static final Logger LOGGER = Logger.getLogger(QuestionStore.class.getName());

    /** Source tags for the audit trail (§7.7). */
    public static final String SOURCE_UI = "UI";

    public static final String SOURCE_REST = "REST";
    public static final String SOURCE_BRIDGE = "BRIDGE";
    public static final String SOURCE_SYSTEM = "SYSTEM";

    /** Callback invoked once when a question reaches a terminal state. */
    @FunctionalInterface
    public interface Resolution {
        void onResolved(@NonNull Question question);
    }

    private final Map<String, Question> questions = new ConcurrentHashMap<>();

    /** Transient (in-memory only) resolvers for currently-attached paused steps. */
    private final transient Map<String, Resolution> resolvers = new ConcurrentHashMap<>();

    public QuestionStore() {
        load();
    }

    @NonNull
    public static QuestionStore get() {
        return ExtensionList.lookupSingleton(QuestionStore.class);
    }

    // ----------------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------------

    /**
     * Register a new WAITING question and persist it.
     *
     * @return the same question for convenience
     */
    @NonNull
    public Question submit(@NonNull Question question) {
        questions.put(question.getId(), question);
        save();
        LOGGER.log(Level.FINE, "submitted question {0} for {1} #{2}", new Object[] {
            question.getId(), question.getJobFullName(), question.getBuildNumber()
        });
        fire(QuestionStatus.WAITING, question);
        return question;
    }

    /**
     * Attach a paused step's resolver. If the question already reached a terminal state (e.g. it was
     * answered while the step was being resumed after a restart), the resolver is invoked immediately.
     */
    public void register(@NonNull String questionId, @NonNull Resolution resolution) {
        resolvers.put(questionId, resolution);
        Question q = questions.get(questionId);
        if (q != null && q.getStatus().isTerminal()) {
            resolvers.remove(questionId);
            resolution.onResolved(q);
        }
    }

    public void unregister(@NonNull String questionId) {
        resolvers.remove(questionId);
    }

    /**
     * Record a human answer and resume the pipeline. Callers must pre-check permissions (a 403 is the
     * REST/UI layer's responsibility); this method assumes the caller is authorised.
     *
     * @throws IllegalStateException if the question is missing or already settled
     */
    @NonNull
    public Answer answer(
            @NonNull String questionId,
            @CheckForNull String choiceId,
            @CheckForNull String freeText,
            @NonNull String byUserId,
            @NonNull String source) {
        Question q = require(questionId);
        Answer a;
        synchronized (q) {
            if (q.getStatus().isTerminal()) {
                throw new IllegalStateException("Question " + questionId + " is already " + q.getStatus());
            }
            a = new Answer(questionId, choiceId, freeText, byUserId, System.currentTimeMillis());
            q.markAnswered(a);
        }
        save();
        LOGGER.log(Level.INFO, "question {0} answered by {1} via {2} (choice={3}, freeText={4})", new Object[] {
            questionId, byUserId, source, choiceId, freeText != null
        });
        fire(QuestionStatus.ANSWERED, q);
        resolve(q);
        return a;
    }

    /**
     * Record an {@code input}-style parameter answer and resume the pipeline (B24). Callers must
     * pre-check permissions and pre-validate/convert the values (the REST/UI layer's responsibility);
     * this method assumes the caller is authorised and the values are already the resolved
     * {@code ParameterValue.getValue()} objects. Only parameter names are logged — never values, so a
     * password parameter is not leaked.
     *
     * @throws IllegalStateException if the question is missing or already settled
     */
    @NonNull
    public Answer answerParameters(
            @NonNull String questionId,
            @NonNull Map<String, Object> parameterValues,
            @NonNull String byUserId,
            @NonNull String source) {
        Question q = require(questionId);
        Answer a;
        synchronized (q) {
            if (q.getStatus().isTerminal()) {
                throw new IllegalStateException("Question " + questionId + " is already " + q.getStatus());
            }
            a = new Answer(questionId, null, null, parameterValues, byUserId, System.currentTimeMillis());
            q.markAnswered(a);
        }
        save();
        LOGGER.log(Level.INFO, "question {0} answered by {1} via {2} ({3} parameter(s): {4})", new Object[] {
            questionId, byUserId, source, parameterValues.size(), parameterValues.keySet()
        });
        fire(QuestionStatus.ANSWERED, q);
        resolve(q);
        return a;
    }

    /**
     * Abort a question (cancel the input). Delivers an abort to the pipeline.
     *
     * @throws IllegalStateException if the question is missing or already settled
     */
    public void abort(@NonNull String questionId, @NonNull String byUserId, @NonNull String source) {
        Question q = require(questionId);
        synchronized (q) {
            if (q.getStatus().isTerminal()) {
                throw new IllegalStateException("Question " + questionId + " is already " + q.getStatus());
            }
            q.markAborted(new Answer(questionId, null, null, byUserId, System.currentTimeMillis()));
        }
        save();
        LOGGER.log(Level.INFO, "question {0} aborted by {1} via {2}", new Object[] {questionId, byUserId, source});
        fire(QuestionStatus.ABORTED, q);
        resolve(q);
    }

    /**
     * Transition a WAITING question to ABORTED without invoking its resolver. Used when the framework
     * stops the step itself (e.g. the build is aborted) and will deliver the cause to the pipeline
     * directly. No-op if the question is missing or already terminal.
     */
    public void abortForShutdown(@NonNull String questionId, @NonNull String byUserId) {
        Question q = questions.get(questionId);
        if (q == null) {
            return;
        }
        synchronized (q) {
            if (q.getStatus().isTerminal()) {
                return;
            }
            q.markAborted(new Answer(questionId, null, null, byUserId, System.currentTimeMillis()));
        }
        save();
        LOGGER.log(Level.FINE, "question {0} aborted (step stopped by framework)", questionId);
        fire(QuestionStatus.ABORTED, q);
    }

    /** Expire a single overdue question (used by the SLA ticker). */
    void expire(@NonNull String questionId) {
        Question q = questions.get(questionId);
        if (q == null) {
            return;
        }
        synchronized (q) {
            if (q.getStatus().isTerminal()) {
                return;
            }
            q.markExpired();
        }
        save();
        LOGGER.log(Level.INFO, "question {0} expired (SLA elapsed)", questionId);
        fire(QuestionStatus.EXPIRED, q);
        resolve(q);
    }

    private void resolve(@NonNull Question q) {
        Resolution r = resolvers.remove(q.getId());
        if (r != null) {
            try {
                r.onResolved(q);
            } catch (RuntimeException x) {
                LOGGER.log(Level.WARNING, "resolver for question " + q.getId() + " threw", x);
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------

    /**
     * Listing order for every query below: oldest first — the order the pipeline asked the questions —
     * tie-broken by id so equal timestamps still yield a stable sequence.
     *
     * <p>The backing map is a {@link ConcurrentHashMap}, whose iteration order is arbitrary. Without this
     * the notification centre, the per-build audit list and the multi-question "series" pager all showed a
     * build's questions in an unrelated order, so the pager's slide 1 was not the first question asked.
     */
    private static final Comparator<Question> BY_CREATION =
            Comparator.comparingLong(Question::getCreatedTs).thenComparing(Question::getId);

    /** Sort {@code out} into {@link #BY_CREATION} order and return it (the caller owns the list). */
    @NonNull
    private static List<Question> sorted(@NonNull List<Question> out) {
        out.sort(BY_CREATION);
        return out;
    }

    @CheckForNull
    public Question get(@NonNull String questionId) {
        return questions.get(questionId);
    }

    /**
     * Remove a question outright (used by the input-step bridge to drop a mirror when the underlying
     * native input has settled or the bridge has been disabled).
     */
    public void remove(@NonNull String questionId) {
        resolvers.remove(questionId);
        if (questions.remove(questionId) != null) {
            save();
        }
    }

    @NonNull
    private Question require(@NonNull String questionId) {
        Question q = questions.get(questionId);
        if (q == null) {
            throw new IllegalStateException("No such question: " + questionId);
        }
        return q;
    }

    /** @return all WAITING questions the current user is allowed to answer (bell + default REST list). */
    @NonNull
    public List<Question> listAnswerable() {
        List<Question> out = new ArrayList<>();
        for (Question q : questions.values()) {
            if (q.getStatus() == QuestionStatus.WAITING && canAnswer(q)) {
                out.add(q);
            }
        }
        return sorted(out);
    }

    /** @return all WAITING questions the current user can at least read (Item.READ on the source job). */
    @NonNull
    public List<Question> listReadable() {
        List<Question> out = new ArrayList<>();
        for (Question q : questions.values()) {
            if (q.getStatus() == QuestionStatus.WAITING && canView(q)) {
                out.add(q);
            }
        }
        return sorted(out);
    }

    /** @return every WAITING question (admin only; callers must enforce {@code Overall/Administer}). */
    @NonNull
    public List<Question> listAll() {
        List<Question> out = new ArrayList<>();
        for (Question q : questions.values()) {
            if (q.getStatus() == QuestionStatus.WAITING) {
                out.add(q);
            }
        }
        return sorted(out);
    }

    // ---- Per-project (job/build) scoped queries (§per-project notification centre) ----

    /** @return WAITING questions for {@code jobFullName} the current user may answer. */
    @NonNull
    public List<Question> listAnswerableForJob(@NonNull String jobFullName) {
        List<Question> out = new ArrayList<>();
        for (Question q : questions.values()) {
            if (q.getStatus() == QuestionStatus.WAITING && jobFullName.equals(q.getJobFullName()) && canAnswer(q)) {
                out.add(q);
            }
        }
        return sorted(out);
    }

    /** @return WAITING questions for {@code jobFullName} the current user can at least read. */
    @NonNull
    public List<Question> listReadableForJob(@NonNull String jobFullName) {
        List<Question> out = new ArrayList<>();
        for (Question q : questions.values()) {
            if (q.getStatus() == QuestionStatus.WAITING && jobFullName.equals(q.getJobFullName()) && canView(q)) {
                out.add(q);
            }
        }
        return sorted(out);
    }

    /** @return the number of WAITING questions for {@code jobFullName} the current user may answer. */
    public int countAnswerableForJob(@NonNull String jobFullName) {
        return listAnswerableForJob(jobFullName).size();
    }

    /**
     * @return every question (any status) recorded for a specific build that the current user can
     *     read. Used by the per-build audit view; includes settled questions until compaction.
     */
    @NonNull
    public List<Question> listForBuild(@NonNull String jobFullName, int buildNumber) {
        List<Question> out = new ArrayList<>();
        for (Question q : questions.values()) {
            if (jobFullName.equals(q.getJobFullName()) && q.getBuildNumber() == buildNumber && canView(q)) {
                out.add(q);
            }
        }
        return sorted(out);
    }

    /** @return {@code true} if {@code jobFullName} #{@code buildNumber} has any WAITING question. */
    public boolean hasWaitingForBuild(@NonNull String jobFullName, int buildNumber) {
        for (Question q : questions.values()) {
            if (q.getStatus() == QuestionStatus.WAITING
                    && jobFullName.equals(q.getJobFullName())
                    && q.getBuildNumber() == buildNumber) {
                return true;
            }
        }
        return false;
    }

    /**
     * Existence probe (no permission filter) used by the run-action factory to decide whether to
     * attach the per-build surfaces. Anyone reaching a build page already holds {@code Item.READ}, so
     * this leaks nothing beyond what the audit view (which is permission-checked) would show.
     *
     * @return {@code true} if any question (any status) is recorded for this build.
     */
    public boolean hasAnyForBuild(@NonNull String jobFullName, int buildNumber) {
        for (Question q : questions.values()) {
            if (jobFullName.equals(q.getJobFullName()) && q.getBuildNumber() == buildNumber) {
                return true;
            }
        }
        return false;
    }

    // ---- Notification-centre queries (user-scope + lock-to-starter aware) ----
    // These drive the bell, per-project box, sidebar count and build-list badge. They layer the two
    // Appearance switches on top of the base permission checks:
    //   * lock off  -> only questions the user may answer (unchanged behaviour);
    //   * lock on   -> also readable questions the user may NOT answer (surfaced, then rendered locked);
    //   * user-scope -> only the current user's own builds (plus ownerless SCM/timer/upstream/system).

    /** @return WAITING questions to surface for the current user across all jobs. */
    @NonNull
    public List<Question> listNotifications() {
        return collectNotifications(null);
    }

    /** @return WAITING questions to surface for the current user, scoped to one job. */
    @NonNull
    public List<Question> listNotificationsForJob(@NonNull String jobFullName) {
        return collectNotifications(jobFullName);
    }

    public int countNotifications() {
        return listNotifications().size();
    }

    public int countNotificationsForJob(@NonNull String jobFullName) {
        return listNotificationsForJob(jobFullName).size();
    }

    /**
     * @return the number of WAITING questions to surface for the current user on a single build,
     *     honouring the user-scope and lock-to-starter switches. Drives the run-page sidebar count
     *     badge (the per-build equivalent of {@link #countNotificationsForJob(String)}).
     */
    public int countNotificationsForBuild(@NonNull String jobFullName, int buildNumber) {
        boolean userScoped = InteractiveInputGlobalConfig.userScopedNotificationsEnabled();
        boolean lock = InteractiveInputGlobalConfig.lockToBuildStarterEnabled();
        String uid = currentUserId();
        int count = 0;
        for (Question q : questions.values()) {
            if (q.getStatus() == QuestionStatus.WAITING
                    && jobFullName.equals(q.getJobFullName())
                    && q.getBuildNumber() == buildNumber
                    && isNotification(q, uid, userScoped, lock)) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return {@code true} if the build has a WAITING question the current user should be notified of
     *     (drives the build-history badge, honouring user-scope and lock).
     */
    public boolean hasNotificationForBuild(@NonNull String jobFullName, int buildNumber) {
        boolean userScoped = InteractiveInputGlobalConfig.userScopedNotificationsEnabled();
        boolean lock = InteractiveInputGlobalConfig.lockToBuildStarterEnabled();
        String uid = currentUserId();
        for (Question q : questions.values()) {
            if (q.getStatus() == QuestionStatus.WAITING
                    && jobFullName.equals(q.getJobFullName())
                    && q.getBuildNumber() == buildNumber
                    && isNotification(q, uid, userScoped, lock)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private List<Question> collectNotifications(@CheckForNull String jobFullName) {
        boolean userScoped = InteractiveInputGlobalConfig.userScopedNotificationsEnabled();
        boolean lock = InteractiveInputGlobalConfig.lockToBuildStarterEnabled();
        String uid = currentUserId();
        List<Question> out = new ArrayList<>();
        for (Question q : questions.values()) {
            if (q.getStatus() != QuestionStatus.WAITING) {
                continue;
            }
            if (jobFullName != null && !jobFullName.equals(q.getJobFullName())) {
                continue;
            }
            if (isNotification(q, uid, userScoped, lock)) {
                out.add(q);
            }
        }
        return sorted(out);
    }

    private boolean isNotification(@NonNull Question q, @NonNull String uid, boolean userScoped, boolean lock) {
        if (lock ? !canView(q) : !canAnswer(q)) {
            return false;
        }
        return !userScoped || isOwnedByOrShared(q, uid);
    }

    /** @return {@code true} if the build has no human starter (shared) or was started by {@code uid}. */
    private boolean isOwnedByOrShared(@NonNull Question q, @NonNull String uid) {
        String owner = q.getStartedBy();
        if (!CauseResolver.isRealUser(owner)) {
            return true;
        }
        return userIdEquals(owner, uid);
    }

    // ----------------------------------------------------------------------------------------
    // Permissions — mirrors pipeline-input-step InputStepExecution (verified against 560):
    //   canSettle / proceed  → Item.BUILD (or submitter / Administer)
    //   preAbortCheck        → Item.CANCEL, or submitter membership when a filter is set
    //                          (native also treats Item.BUILD as sufficient when there is no
    //                          submitter; we do not — aborting the run is Cancel, not Build)
    // ----------------------------------------------------------------------------------------

    /** @return {@code true} if the current authentication may answer the question. */
    public boolean canAnswer(@NonNull Question q) {
        Job<?, ?> job = findJob(q);
        if (job == null) {
            return false;
        }
        String submitter = q.getSubmitterFilter();
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
     * @return {@code true} if the current authentication may abort the question (and thereby abort the
     *     run). Requires {@link Item#CANCEL} on the source job, or — when a {@code submitterFilter} is
     *     set — membership in that set. {@link Jenkins#ADMINISTER} and security-off always pass.
     *     {@link Item#BUILD} alone is not enough: aborting a pipeline is Cancel, not Build.
     */
    public boolean canAbort(@NonNull Question q) {
        Job<?, ?> job = findJob(q);
        if (job == null) {
            return false;
        }
        Jenkins j = Jenkins.get();
        if (!j.isUseSecurity() || j.hasPermission(Jenkins.ADMINISTER)) {
            return true;
        }
        if (job.hasPermission(Item.CANCEL)) {
            return true;
        }
        String submitter = q.getSubmitterFilter();
        if (submitter != null && !submitter.trim().isEmpty()) {
            return isSubmitter(submitter, Jenkins.getAuthentication2());
        }
        return false;
    }

    /**
     * @return {@code true} if the current authentication may answer <em>after</em> applying the
     *     lock-to-build-starter switch. This is {@link #canAnswer(Question)} unless the switch is on,
     *     in which case only the build starter (or a Jenkins administrator) may answer; builds with
     *     no human starter are never locked. This is the check the REST answer endpoint and the
     *     modal's {@code canAnswer} flag use — it can only ever restrict, never widen, access.
     */
    public boolean canAnswerEffective(@NonNull Question q) {
        return canAnswer(q) && passesLockToBuildStarter(q);
    }

    /**
     * @return {@code true} if the current authentication may abort <em>after</em> applying the
     *     lock-to-build-starter switch. Same restriction as {@link #canAnswerEffective(Question)}:
     *     the lock can only ever restrict, never widen, Cancel.
     */
    public boolean canAbortEffective(@NonNull Question q) {
        return canAbort(q) && passesLockToBuildStarter(q);
    }

    /**
     * @return {@code true} unless lock-to-build-starter is on and the caller is neither the human
     *     starter nor an administrator. Builds with no human starter are never locked.
     */
    private boolean passesLockToBuildStarter(@NonNull Question q) {
        if (!InteractiveInputGlobalConfig.lockToBuildStarterEnabled()) {
            return true;
        }
        Jenkins j = Jenkins.get();
        if (!j.isUseSecurity() || j.hasPermission(Jenkins.ADMINISTER)) {
            return true;
        }
        String owner = q.getStartedBy();
        if (!CauseResolver.isRealUser(owner)) {
            return true;
        }
        return userIdEquals(owner, currentUserId());
    }

    /** @return {@code true} if the current authentication can read the source job (Item.READ). */
    public boolean canView(@NonNull Question q) {
        Job<?, ?> job = findJob(q);
        return job != null && job.hasPermission(Item.READ);
    }

    /** Compare two user ids using the security realm's id strategy (case handling per realm). */
    private boolean userIdEquals(@NonNull String a, @NonNull String b) {
        return Jenkins.get().getSecurityRealm().getUserIdStrategy().equals(a, b);
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

    @CheckForNull
    public Job<?, ?> findJob(@NonNull Question q) {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        return j.getItemByFullName(q.getJobFullName(), Job.class);
    }

    /** @return the user id of the current authentication, or {@code "SYSTEM"} if unauthenticated. */
    @NonNull
    public static String currentUserId() {
        User u = User.current();
        return u != null ? u.getId() : SOURCE_SYSTEM;
    }

    // ----------------------------------------------------------------------------------------
    // SLA + retention (driven by SlaTicker)
    // ----------------------------------------------------------------------------------------

    /** Expire every overdue WAITING question. Called by the SLA ticker. */
    public void expireOverdue(long now) {
        for (Question q : questions.values()) {
            if (q.getStatus() == QuestionStatus.WAITING && q.isExpired(now)) {
                expire(q.getId());
            }
        }
    }

    /** Remove terminal questions older than {@code retentionMs}. Called by the SLA ticker. */
    public void compact(long now, long retentionMs) {
        if (retentionMs <= 0) {
            return;
        }
        boolean changed = false;
        for (Question q : new ArrayList<>(questions.values())) {
            if (q.getStatus().isTerminal()) {
                Answer settled = q.getAnswer();
                long settledRef = settled != null ? settled.getAnsweredTs() : q.getCreatedTs();
                if (now - settledRef > retentionMs) {
                    questions.remove(q.getId());
                    resolvers.remove(q.getId());
                    changed = true;
                }
            }
        }
        if (changed) {
            save();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Build lifecycle (driven by BuildLifecycleCleanup)
    // ----------------------------------------------------------------------------------------

    /**
     * Purge every question of a now-deleted build (any status). A question is meaningful only alongside
     * its build — its audit trail lives on the build page, which is gone — so on build deletion its
     * questions are removed outright, clearing them from the notification centre and the sidebar/badge
     * counts. Invoked by the {@code RunListener} when a build is deleted.
     *
     * @return the number of questions removed.
     */
    public int removeForBuild(@NonNull String jobFullName, int buildNumber) {
        int removed = 0;
        for (Question q : new ArrayList<>(questions.values())) {
            if (jobFullName.equals(q.getJobFullName()) && q.getBuildNumber() == buildNumber) {
                questions.remove(q.getId());
                resolvers.remove(q.getId());
                removed++;
            }
        }
        if (removed > 0) {
            save();
            LOGGER.log(Level.FINE, "removed {0} question(s) for deleted build {1} #{2}", new Object[] {
                removed, jobFullName, buildNumber
            });
        }
        return removed;
    }

    /**
     * Startup self-heal: purge questions whose owning build no longer exists (deleted before this cleanup
     * shipped, or while the controller was down). Only acts when the job still resolves but the build is
     * gone, so a temporarily-unresolvable job never loses its questions. Invoked once from
     * {@code BuildLifecycleCleanup}'s {@code onLoaded}.
     *
     * @return the number of questions removed.
     */
    public int reconcileDeletedBuilds() {
        int removed = 0;
        for (Question q : new ArrayList<>(questions.values())) {
            Job<?, ?> job = findJob(q);
            if (job != null && job.getBuildByNumber(q.getBuildNumber()) == null) {
                questions.remove(q.getId());
                resolvers.remove(q.getId());
                removed++;
            }
        }
        if (removed > 0) {
            save();
            LOGGER.log(Level.INFO, "reconciled {0} question(s) whose owning build was deleted", removed);
        }
        return removed;
    }

    // ----------------------------------------------------------------------------------------
    // Persistence
    // ----------------------------------------------------------------------------------------

    @NonNull
    private XmlFile getConfigFile() {
        File dir = new File(Jenkins.get().getRootDir(), "interactive-input");
        return new XmlFile(Jenkins.XSTREAM2, new File(dir, "questions.xml"));
    }

    private synchronized void save() {
        if (Jenkins.getInstanceOrNull() == null) {
            return;
        }
        try {
            XmlFile f = getConfigFile();
            f.mkdirs();
            f.write(new ArrayList<>(questions.values()));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "failed to persist interactive-input questions", e);
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
            questions.clear();
            if (data instanceof List) {
                for (Object o : (List<Object>) data) {
                    if (o instanceof Question) {
                        Question q = (Question) o;
                        questions.put(q.getId(), q);
                    }
                }
            }
            LOGGER.log(Level.FINE, "loaded {0} interactive-input questions", questions.size());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "failed to load interactive-input questions", e);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Listener fan-out (audit + extensibility)
    // ----------------------------------------------------------------------------------------

    private void fire(@NonNull QuestionStatus transition, @NonNull Question q) {
        for (QuestionStoreListener l : QuestionStoreListener.all()) {
            try {
                switch (transition) {
                    case WAITING:
                        l.onSubmitted(q);
                        break;
                    case ANSWERED:
                        l.onAnswered(q);
                        break;
                    case ABORTED:
                        l.onAborted(q);
                        break;
                    case EXPIRED:
                        l.onExpired(q);
                        break;
                    default:
                        break;
                }
            } catch (RuntimeException x) {
                LOGGER.log(
                        Level.WARNING, "QuestionStoreListener " + l.getClass().getName() + " threw", x);
            }
        }
    }

    // Visible for tests: build a Choice list without importing model in test packages awkwardly.
    @NonNull
    public static Choice choice(@NonNull String id, @NonNull String label, @CheckForNull String why) {
        Choice c = new Choice(id, label);
        if (why != null) {
            c.setWhy(why);
        }
        return c;
    }
}
