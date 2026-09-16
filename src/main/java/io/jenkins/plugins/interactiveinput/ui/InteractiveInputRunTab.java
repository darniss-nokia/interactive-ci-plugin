// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Run;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputRunPageAlertJobProperty;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Tab;
import jenkins.model.TransientActionFactory;

/**
 * Native "Interactive Input" attention card for the experimental run overview — the experimental-layout
 * counterpart of {@link InteractiveInputRunAction}'s {@code summary.jelly} (B5 attention row), which is
 * suppressed when the new build page is on. Rendered by core's {@code jenkins/run/OverviewTab/index.jelly}
 * via {@code Run#getRunTabs()} + {@code widget.jelly}, so the "input needed" prompt is a first-class
 * {@code <l:card>} instead of a row inside core's "Legacy" card.
 *
 * <p>{@link #getIconFileName()} returns a non-null icon (making the tab/card visible) only when the
 * viewer has the experimental build page enabled AND this build is currently waiting for the viewer's
 * input. So it appears only when relevant, never in the classic layout, and matches the per-viewer
 * scoping of the classic row. Live reveal/hide is handled by the global bell + auto-open dialog; this
 * server-rendered card is the in-page attention surface.
 *
 * <p>The tab uses a distinct URL so it never collides with {@link InteractiveInputRunAction}'s route.
 * {@code index.jelly} renders the audit view inside {@code l:run-subpage} so a tab click stays on the
 * experimental build chrome instead of redirecting to the classic sidepanel action.
 */
public class InteractiveInputRunTab extends Tab {

    private static final Logger LOGGER = Logger.getLogger(InteractiveInputRunTab.class.getName());

    /** Distinct URL segment (does not collide with {@link InteractiveInputRunAction#URL_NAME}). */
    public static final String URL_NAME = "interactive-input-overview";

    public InteractiveInputRunTab(@NonNull Run<?, ?> run) {
        super(run);
    }

    @NonNull
    public Run<?, ?> getRun() {
        return (Run<?, ?>) getObject();
    }

    @NonNull
    public String getJobFullName() {
        return getRun().getParent().getFullName();
    }

    public int getBuildNumber() {
        return getRun().getNumber();
    }

    /** @return the theme-aware notification symbol configured in Appearance. */
    @NonNull
    public String getIconClassName() {
        return InteractiveInputAppearanceConfig.iconClassNameOrDefault();
    }

    /** @return {@code true} while this build has a WAITING question the current viewer should answer. */
    public boolean isWaiting() {
        try {
            return QuestionStore.get().hasNotificationForBuild(getJobFullName(), getBuildNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not compute waiting state for " + getRun());
            return false;
        }
    }

    public int getPollingIntervalSeconds() {
        return InteractiveInputGlobalConfig.pollingIntervalSecondsOrDefault();
    }

    public boolean isRichModalEnabled() {
        return InteractiveInputGlobalConfig.featuresOrDefault().isRichModal();
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        // Short-circuits before the store lookup in the classic layout (newBuildPage() == false).
        return ExperimentalLayout.newBuildPage() && isWaiting() ? getIconClassName() : null;
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

    /**
     * @return a view of this build's audit action so {@code index.jelly} can reuse
     *     {@code InteractiveInputRunAction/content.jelly} inside {@code l:run-subpage}.
     */
    @NonNull
    public InteractiveInputRunAction getInputAction() {
        return new InteractiveInputRunAction(getRun());
    }

    /**
     * Attaches the tab to builds of jobs that opted into the run-page attention indicator (same gate as
     * the classic row). Visibility is then decided by {@link #getIconFileName()} (experimental layout +
     * waiting), so the classic layout and non-waiting builds are unaffected.
     */
    @Extension
    public static class Factory extends TransientActionFactory<Run> {

        @Override
        public Class<Run> type() {
            return Run.class;
        }

        @Override
        @NonNull
        public Collection<? extends Action> createFor(@NonNull Run target) {
            if (!InteractiveInputAppearanceConfig.perProjectCentreEnabled()) {
                return Collections.emptySet();
            }
            try {
                if (InteractiveInputRunPageAlertJobProperty.isEnabledOn(target.getParent())) {
                    return Collections.singleton(new InteractiveInputRunTab(target));
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "could not build input run tab for " + target);
            }
            return Collections.emptySet();
        }
    }
}
