// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.bridge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.model.Answer;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import io.jenkins.plugins.interactiveinput.util.CauseResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStep;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;

/**
 * Opt-in bridge that surfaces built-in {@code input} steps in the notification bell (§3.1 S5, §8.7).
 *
 * <p>When {@code unclassified.interactiveInput.features.inputStepBridge} is on, {@link #sync()}
 * (invoked by the SLA ticker) reconciles the {@link QuestionStore} with the live set of pending
 * {@link InputStepExecution}s: each pending native input is mirrored as a {@code bridged}
 * {@link Question}, and a transient resolver is (re)attached every tick so it survives a restart.
 * Answering a mirror through our modal or REST forwards to the native step
 * ({@link InputStepExecution#proceed}/{@link InputStepExecution#doAbort()}); if the user instead
 * answers via the built-in UI, the native input settles, disappears from its {@link InputAction},
 * and the next sync drops the now-orphaned mirror.
 *
 * <p>The mirror faithfully reproduces the native "proceed with no parameters" contract, including the
 * optional {@code submitterParameter} (populated with the answering user's id). Native inputs that
 * declare parameters are surfaced read-only with a deep link to the build's input form (answering
 * parameterised inputs in-modal is a v0.2 item, §12).
 *
 * <p>Builds that already use {@code input} see no behavioural change — they simply also appear in the
 * bell. The bridge is off by default and does nothing until an operator opts in.
 */
@Extension
public class InputStepBridge {

    private static final Logger LOGGER = Logger.getLogger(InputStepBridge.class.getName());

    /** Sentinel choice offered for parameterless native inputs; means "resume the input". */
    public static final String PROCEED_CHOICE_ID = "__proceed__";

    /**
     * Bound on how many recent builds per job to scan for pending inputs (v0.1). Only builds that
     * actually reached an {@code input} step carry an {@link InputAction}, so the expensive
     * {@link InputAction#getExecutions()} call is gated behind that cheap check; the cap simply keeps
     * the newest-first scan from walking very long build histories. Event-driven mirroring is a v0.2
     * item (§12).
     */
    private static final int MAX_BUILDS_PER_JOB = 25;

    @NonNull
    public static InputStepBridge get() {
        return ExtensionList.lookupSingleton(InputStepBridge.class);
    }

    /**
     * Reconcile every job's bridged mirrors with the live set of pending native input steps. Invoked
     * by the SLA ticker every 30s; equivalent to {@link #reconcile(String) reconcile(null)}. Safe to
     * call repeatedly; it is a no-op (beyond dropping stale mirrors) when the feature is off.
     */
    public void sync() {
        reconcile(null);
    }

    /**
     * Reconcile bridged mirrors with the live native input state.
     *
     * <p>When {@code jobScope} is {@code null} every job is scanned (the ticker path). When it names a
     * job, only that job is scanned and only that job's bridged mirrors are reconciled — this is the
     * read path: {@code GET /questions?job=<name>} calls it so a pipeline's surfaces self-heal within
     * one poll (≤15s) instead of waiting up to 30s for the next ticker tick. That matters when a
     * native input is answered through the built-in console/stage-view UI: our mirror stays WAITING
     * until the underlying {@link InputStepExecution} disappears from its {@link InputAction} and we
     * drop it here.
     *
     * @param jobScope full name of the single job to reconcile, or {@code null} for all jobs
     */
    public void reconcile(@CheckForNull String jobScope) {
        QuestionStore store = QuestionStore.get();
        if (!InteractiveInputGlobalConfig.featuresOrDefault().isInputStepBridge()) {
            dropBridged(store, jobScope);
            return;
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return;
        }
        Set<String> present = new HashSet<>();
        if (jobScope != null) {
            Job<?, ?> job = j.getItemByFullName(jobScope, Job.class);
            if (job != null) {
                scanJob(store, job, present);
            }
        } else {
            for (Job<?, ?> job : j.getAllItems(Job.class)) {
                scanJob(store, job, present);
            }
        }
        // Drop mirrors whose native input has settled (e.g. answered via the built-in UI). listAll()
        // returns a copy of the WAITING questions, so removing here is safe. Only in-scope mirrors are
        // considered, so a scoped reconcile never disturbs other jobs' notifications.
        for (Question q : store.listAll()) {
            if (q.isBridged() && inScope(q, jobScope) && !present.contains(q.getId())) {
                store.remove(q.getId());
            }
        }
    }

    /** Scan one job's building runs for pending native inputs; mirror new ones and (re)attach resolvers. */
    private void scanJob(@NonNull QuestionStore store, @NonNull Job<?, ?> job, @NonNull Set<String> present) {
        int checked = 0;
        for (Run<?, ?> run : job.getBuilds()) {
            if (checked++ >= MAX_BUILDS_PER_JOB) {
                break;
            }
            if (!run.isBuilding()) {
                continue;
            }
            InputAction ia = run.getAction(InputAction.class);
            if (ia == null) {
                continue;
            }
            try {
                for (InputStepExecution ise : ia.getExecutions()) {
                    String qid = bridgeId(run, ise.getId());
                    present.add(qid);
                    if (store.get(qid) == null) {
                        mirror(store, run, ise, qid);
                    }
                    // (Re)attach the resolver every tick so answers still forward after a restart (the
                    // mirror survives via XStream but its resolver is transient). Idempotent.
                    final String runId = run.getExternalizableId();
                    final String inputId = ise.getId();
                    store.register(qid, resolved -> forward(runId, inputId, resolved));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.log(Level.FINE, e, () -> "could not read InputAction for " + run);
            }
        }
    }

    private void dropBridged(@NonNull QuestionStore store, @CheckForNull String jobScope) {
        for (Question q : store.listAll()) {
            if (q.isBridged() && inScope(q, jobScope)) {
                store.remove(q.getId());
            }
        }
    }

    private static boolean inScope(@NonNull Question q, @CheckForNull String jobScope) {
        return jobScope == null || jobScope.equals(q.getJobFullName());
    }

    private void mirror(
            @NonNull QuestionStore store,
            @NonNull Run<?, ?> run,
            @NonNull InputStepExecution ise,
            @NonNull String qid) {
        InputStep input = ise.getInput();
        boolean hasParams =
                input.getParameters() != null && !input.getParameters().isEmpty();
        List<Choice> choices = new ArrayList<>();
        String contextMd = null;
        if (hasParams) {
            contextMd = "This input needs parameters. [Open the build](" + buildInputUrl(run) + ") to answer it fully.";
        } else {
            choices.add(QuestionStore.choice(PROCEED_CHOICE_ID, "Approve / Proceed", "Resume the paused input step"));
        }
        String prompt = input.getMessage() != null ? input.getMessage() : "Input requested";
        Question q = new Question(
                qid,
                prompt,
                choices,
                false,
                0L,
                contextMd,
                input.getSubmitter(),
                run.getParent().getFullName(),
                run.getNumber(),
                CauseResolver.startedBy(run),
                System.currentTimeMillis(),
                true);
        store.submit(q);
    }

    /** Forward a mirror's terminal outcome to the underlying native input step. */
    private void forward(@NonNull String runExternalizableId, @NonNull String inputId, @NonNull Question q) {
        try {
            Run<?, ?> run = Run.fromExternalizableId(runExternalizableId);
            if (run == null) {
                return;
            }
            InputAction ia = run.getAction(InputAction.class);
            if (ia == null) {
                return;
            }
            InputStepExecution ise = ia.getExecution(inputId);
            if (ise == null || ise.isSettled()) {
                return; // already settled natively, or via a prior forward; never double-settle
            }
            switch (q.getStatus()) {
                case ANSWERED:
                    Answer a = q.getAnswer();
                    if (a != null && a.isDeny()) {
                        ise.doAbort();
                    } else {
                        ise.proceed(submitterParams(ise.getInput(), a));
                    }
                    break;
                case ABORTED:
                case EXPIRED:
                    ise.doAbort();
                    break;
                default:
                    break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e, () -> "failed to forward bridged answer to native input " + inputId);
        }
    }

    /**
     * Reproduce the native {@code doProceedEmpty} contract: if the input declares a
     * {@code submitterParameter}, return a single-entry map of that name to the answering user's id;
     * otherwise {@code null}. {@link InputStepExecution#proceed(Map)} unwraps a single-entry map to
     * its value, so the pipeline sees exactly what the built-in UI would have produced.
     */
    private static Map<String, Object> submitterParams(@NonNull InputStep input, Answer answer) {
        String name = input.getSubmitterParameter();
        if (name == null || name.isEmpty() || answer == null) {
            return null;
        }
        return Collections.singletonMap(name, answer.getAnsweredBy());
    }

    @NonNull
    private static String buildInputUrl(@NonNull Run<?, ?> run) {
        Jenkins j = Jenkins.getInstanceOrNull();
        String root = j != null ? j.getRootUrl() : null; // includes context path + trailing slash
        String base = (root != null && !root.isEmpty()) ? root : "/";
        return base + run.getUrl() + "input/";
    }

    @NonNull
    private static String bridgeId(@NonNull Run<?, ?> run, @NonNull String inputId) {
        return "bridge:" + run.getExternalizableId() + ":" + inputId;
    }
}
