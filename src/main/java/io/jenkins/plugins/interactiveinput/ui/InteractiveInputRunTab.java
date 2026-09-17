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
import jenkins.management.Badge;
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
 * <p>The tab's {@link #getUrlName()} matches {@link InteractiveInputRunAction} so the experimental
 * tab bar stays on the same URL as the sidepanel action. This tab is widget-only: the action's
 * {@code index.jelly} ({@code l:run-subpage}) serves the page. {@link #getBadge()} supplies the
 * pending count on the tab bar.
 */
public class InteractiveInputRunTab extends Tab {

    private static final Logger LOGGER = Logger.getLogger(InteractiveInputRunTab.class.getName());

    /** Same URL segment as {@link InteractiveInputRunAction} so the tab bar stays on the action page. */
    public static final String URL_NAME = InteractiveInputRunAction.URL_NAME;

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

    /**
     * @return WAITING questions on this build the current viewer should act on (0 on any store error).
     */
    public int getPendingCount() {
        try {
            return QuestionStore.get().countNotificationsForBuild(getJobFullName(), getBuildNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not count pending questions for " + getRun());
            return 0;
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
     * @return a numeric DANGER badge while this build is waiting for the current viewer; {@code null}
     *     at zero. Core shows this on the experimental tab bar.
     */
    @Override
    @CheckForNull
    public Badge getBadge() {
        return PendingCountBadge.of(getPendingCount());
    }

    /**
     * Attaches the tab to builds of jobs that opted into the run-page attention indicator (same gate as
     * the classic row). Visibility is then decided by {@link #getIconFileName()} (experimental layout +
     * waiting), so the classic layout and non-waiting builds are unaffected. {@code ordinal = -1} so
     * {@link InteractiveInputRunAction.Factory} (default ordinal) wins Stapler's first-{@code urlName}
     * match and serves the page.
     */
    @Extension(ordinal = -1)
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
