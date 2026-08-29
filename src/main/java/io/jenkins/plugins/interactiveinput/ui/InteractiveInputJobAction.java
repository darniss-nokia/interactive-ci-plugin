// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.TransientActionFactory;

/**
 * Per-project notification centre (§per-project surfaces). Attached to every {@link Job} by
 * {@link Factory} when the {@code perProjectCentre} feature is on.
 *
 * <p>Renders two surfaces from the action's resources:
 * <ul>
 *   <li>{@code jobMain.jelly} — an inline box on the job/pipeline page listing that job's pending
 *       questions (rendered by {@code WorkflowJob/main.jelly} and {@code AbstractProject/main.jelly}
 *       which iterate {@code allActions} and include each action's {@code jobMain.jelly}). It
 *       self-hides when nothing is pending.</li>
 *   <li>{@code index.jelly} — the action's own page (reached via the left-sidebar link that appears
 *       only when there are pending questions, driven by {@link #getIconFileName()}). While showing,
 *       its pending count is kept live client-side by the always-present {@code data-ii-tasklink}
 *       controller in {@code jobMain.jelly} (see {@code bell.js}) — rendered as a native
 *       {@code jenkins-badge} pill next to the label rather than "{@code (N)}" text — so it no longer
 *       goes stale until a page reload.</li>
 * </ul>
 *
 * <p>Both surfaces mount the shared JS widget, which polls the scoped REST endpoint
 * ({@code /interactive-input/api/v1/questions?job=<fullName>}) and opens the shared modal. All
 * permission and existence checks live in the store/REST layer; this action only exposes metadata.
 */
public class InteractiveInputJobAction implements Action {

    private static final Logger LOGGER = Logger.getLogger(InteractiveInputJobAction.class.getName());

    /** Stable URL segment under the job (e.g. {@code /job/foo/interactive-input/}). */
    public static final String URL_NAME = "interactive-input";

    private final Job<?, ?> job;

    public InteractiveInputJobAction(@NonNull Job<?, ?> job) {
        this.job = job;
    }

    static boolean enabled() {
        return InteractiveInputAppearanceConfig.perProjectCentreEnabled();
    }

    /** @return whether the large inline job-page box is enabled (independent of the sidebar/badge). */
    public boolean isJobPageBoxEnabled() {
        return InteractiveInputAppearanceConfig.jobPageBoxEnabled();
    }

    /**
     * @return whether the inline job-page box should render. Suppressed under the experimental job page,
     *     where core wraps {@code jobMain.jelly} in its "Legacy" card and there is no plugin-usable
     *     native-card API ({@code WidgetFactory} is {@code @Restricted(NoExternalUse)}). The action stays
     *     reachable there via the native "more actions" overflow menu, the global bell, and the dedicated
     *     {@code interactive-input/} page. The classic layout is unchanged.
     */
    public boolean isJobBoxVisibleClassic() {
        return isJobPageBoxEnabled() && !ExperimentalLayout.newJobPage();
    }

    /** @return the theme-aware symbol class for the configured notification icon. */
    @NonNull
    public String getIconClassName() {
        return InteractiveInputAppearanceConfig.iconClassNameOrDefault();
    }

    @NonNull
    public Job<?, ?> getJob() {
        return job;
    }

    @NonNull
    public String getJobFullName() {
        return job.getFullName();
    }

    /** @return WAITING questions this job should surface to the current viewer (0 on any store error). */
    public int getPendingCount() {
        try {
            return QuestionStore.get().countNotificationsForJob(job.getFullName());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not count pending questions for " + job.getFullName());
            return 0;
        }
    }

    public int getPollingIntervalSeconds() {
        return InteractiveInputGlobalConfig.pollingIntervalSecondsOrDefault();
    }

    public boolean isRichModalEnabled() {
        return InteractiveInputGlobalConfig.featuresOrDefault().isRichModal();
    }

    /**
     * @return the sidebar icon path, or {@code null} to hide the link. The link appears only when the
     *     feature is on and this job has pending questions (so it stays a per-project indicator, not
     *     permanent clutter on every job). While it is showing, the always-present
     *     {@code data-ii-tasklink} controller (see {@code jobMain.jelly} / {@code bell.js}) keeps its
     *     "{@code (N)}" count live and hides the row when the count reaches zero, so the number no
     *     longer goes stale until a full page reload.
     */
    @Override
    @CheckForNull
    public String getIconFileName() {
        return enabled() && getPendingCount() > 0 ? getIconClassName() : null;
    }

    /**
     * @return "Interactive Input", suffixed with the pending count {@code (N)} when there is at least one
     *     pending question. This surfaces the count in the experimental "more actions" overflow menu, which
     *     is server-rendered from the display name (core exposes no styled-badge slot there). In the classic
     *     sidebar {@code bell.js} ({@code mountTaskLink}) resets the label to plain "Interactive Input" and
     *     shows the count as a live {@code jenkins-badge} pill instead, so there is no double count. Mirrors
     *     {@link InteractiveViewJobAction#getDisplayName()}.
     */
    @Override
    @NonNull
    public String getDisplayName() {
        int n = getPendingCount();
        return n > 0 ? "Interactive Input (" + n + ")" : "Interactive Input";
    }

    @Override
    @NonNull
    public String getUrlName() {
        return URL_NAME;
    }

    /** Attaches {@link InteractiveInputJobAction} to every job when the per-project centre is enabled. */
    @Extension
    public static class Factory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        @NonNull
        public Collection<? extends Action> createFor(@NonNull Job target) {
            if (!enabled()) {
                return Collections.emptySet();
            }
            return Collections.singleton(new InteractiveInputJobAction(target));
        }
    }
}
