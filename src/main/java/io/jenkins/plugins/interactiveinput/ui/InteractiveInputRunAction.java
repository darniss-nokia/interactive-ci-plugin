// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.BuildBadgeAction;
import hudson.model.Run;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputRunPageAlertJobProperty;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.TransientActionFactory;

/**
 * Per-build notification + audit surface (§per-project surfaces). Attached to a {@link Run} by
 * {@link Factory} only when that build has interactive-input questions recorded in the store.
 *
 * <p>As a {@link BuildBadgeAction} its {@code badge.jelly} renders an "awaiting input" marker in the
 * build-history list while the build is waiting (core's {@code Run#getBadgeActions()} includes
 * transient actions — verified against {@code Actionable#getActions(Class)}). Its {@code index.jelly}
 * is the per-build audit view (reached from the build's left-sidebar link and from the anchored
 * console link), showing what was asked and what was chosen.
 *
 * <p>Audit data is read live from the {@link QuestionStore}; the durable record is the build console
 * line written by the step. After retention compaction the store no longer holds settled questions,
 * so the audit page shows an empty state and defers to the console line (documented trade-off).
 */
public class InteractiveInputRunAction implements BuildBadgeAction {

    private static final Logger LOGGER = Logger.getLogger(InteractiveInputRunAction.class.getName());

    /** Stable URL segment under the run (e.g. {@code /job/foo/3/interactive-input/}). */
    public static final String URL_NAME = "interactive-input";

    private final Run<?, ?> run;

    public InteractiveInputRunAction(@NonNull Run<?, ?> run) {
        this.run = run;
    }

    static boolean enabled() {
        return InteractiveInputAppearanceConfig.perProjectCentreEnabled();
    }

    /** @return the theme-aware symbol class for the configured notification icon. */
    @NonNull
    public String getIconClassName() {
        return InteractiveInputAppearanceConfig.iconClassNameOrDefault();
    }

    @NonNull
    public Run<?, ?> getRun() {
        return run;
    }

    @NonNull
    public String getJobFullName() {
        return run.getParent().getFullName();
    }

    public int getBuildNumber() {
        return run.getNumber();
    }

    /**
     * @return {@code true} while this build has a WAITING question the current viewer should be
     *     notified of (drives the history badge, honouring the user-scope and lock switches).
     */
    public boolean isWaiting() {
        try {
            return QuestionStore.get().hasNotificationForBuild(getJobFullName(), getBuildNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not compute waiting state for " + run);
            return false;
        }
    }

    public int getPollingIntervalSeconds() {
        return InteractiveInputGlobalConfig.pollingIntervalSecondsOrDefault();
    }

    public boolean isRichModalEnabled() {
        return InteractiveInputGlobalConfig.featuresOrDefault().isRichModal();
    }

    /**
     * @return whether this build's job opted in to the run-page attention indicator (B5) via the
     *     per-pipeline "alert user on run page" property in Configure. Gates the summary row only; the
     *     auto-open dialog is independent of this and governed by the System setting.
     */
    public boolean isRunAlertEnabled() {
        return InteractiveInputRunPageAlertJobProperty.isEnabledOn(run.getParent());
    }

    /**
     * @return whether the classic {@code summary.jelly} attention row should render. Suppressed under
     *     the experimental build page, where the prompt is shown natively by {@link InteractiveInputRunTab}
     *     (avoiding a duplicate inside core's "Legacy" card).
     */
    public boolean isClassicSummaryVisible() {
        return isRunAlertEnabled() && !ExperimentalLayout.newBuildPage();
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return enabled() ? getIconClassName() : null;
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "Interactive Input";
    }

    @Override
    @NonNull
    public String getUrlName() {
        return URL_NAME;
    }

    /** Attaches {@link InteractiveInputRunAction} to builds that have interactive-input questions. */
    @Extension
    public static class Factory extends TransientActionFactory<Run> {

        @Override
        public Class<Run> type() {
            return Run.class;
        }

        @Override
        @NonNull
        public Collection<? extends Action> createFor(@NonNull Run target) {
            if (!enabled()) {
                return Collections.emptySet();
            }
            try {
                if (QuestionStore.get().hasAnyForBuild(target.getParent().getFullName(), target.getNumber())) {
                    return Collections.singleton(new InteractiveInputRunAction(target));
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "could not build run action for " + target);
            }
            return Collections.emptySet();
        }
    }
}
