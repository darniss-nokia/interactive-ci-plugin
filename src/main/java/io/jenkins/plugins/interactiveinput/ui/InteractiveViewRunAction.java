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
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.TransientActionFactory;

/**
 * Per-build "Interactive View" surface. Attached to a {@link Run} by {@link Factory} only when that
 * build has review documents in the {@link ViewStore}.
 *
 * <p>Renders a left-sidebar link (present on the run page and its sub-pages, including the Console
 * Output) whose {@code index.jelly} is the Confluence-style review editor. As a
 * {@link BuildBadgeAction} its {@code badge.jelly} marks builds that still have an open review in the
 * build-history list. The editor page opens a specific review via {@code ?doc=<id>} (the console link
 * and notification bell deep-link there); with no {@code doc} param it lists the build's reviews.
 *
 * <p>All permission and existence checks live in the {@link ViewStore}/REST layer; this action only
 * exposes metadata for its Jelly views.
 */
public class InteractiveViewRunAction implements BuildBadgeAction {

    private static final Logger LOGGER = Logger.getLogger(InteractiveViewRunAction.class.getName());

    /** Stable URL segment under the run (e.g. {@code /job/foo/3/interactive-view/}). */
    public static final String URL_NAME = "interactive-view";

    /** Theme-aware symbol for the review surfaces (distinct from the question bell icon). */
    public static final String ICON = "symbol-document-text-outline plugin-ionicons-api";

    private final Run<?, ?> run;

    public InteractiveViewRunAction(@NonNull Run<?, ?> run) {
        this.run = run;
    }

    static boolean enabled() {
        return InteractiveInputAppearanceConfig.perProjectCentreEnabled()
                && InteractiveInputGlobalConfig.featuresOrDefault().isInteractiveView();
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

    /** @return the reviews for this build the current viewer may read (any status). */
    @NonNull
    public List<ReviewDocument> getReviews() {
        try {
            return ViewStore.get().listForBuild(getJobFullName(), getBuildNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not list reviews for " + run);
            return Collections.emptyList();
        }
    }

    /**
     * @return {@code true} if this build still has an open, notify-enabled review the current viewer
     *     should act on (drives the build-history badge), honouring the user-scope switch so it matches
     *     the bell and sidebar count.
     */
    public boolean isWaiting() {
        try {
            return ViewStore.get().hasNotificationForBuild(getJobFullName(), getBuildNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not compute review waiting state for " + run);
            return false;
        }
    }

    /**
     * @return the OPEN, notify-enabled reviews on this build the current viewer should act on (0 on any
     *     store error), honouring the user-scope switch. Surfaces as the {@code (N)} suffix on
     *     {@link #getDisplayName()} in the experimental "more actions" menu; the classic sidebar shows it
     *     as a live {@code bell.js} pill instead.
     */
    public int getPendingCount() {
        try {
            return ViewStore.get().countNotificationsForBuild(getJobFullName(), getBuildNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not count open reviews for " + run);
            return 0;
        }
    }

    public int getPollingIntervalSeconds() {
        return InteractiveInputGlobalConfig.pollingIntervalSecondsOrDefault();
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return enabled() ? ICON : null;
    }

    /**
     * @return "Interactive View", suffixed with the pending count {@code (N)} when this build has open
     *     notifications — so the experimental "more actions" overflow menu shows the count. The classic
     *     sidebar resets this to plain text and renders the count as a live {@code bell.js} pill instead.
     */
    @Override
    @NonNull
    public String getDisplayName() {
        int n = getPendingCount();
        return n > 0 ? "Interactive View (" + n + ")" : "Interactive View";
    }

    @Override
    @NonNull
    public String getUrlName() {
        return URL_NAME;
    }

    /** Attaches {@link InteractiveViewRunAction} to builds that have review documents. */
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
                if (ViewStore.get().hasAnyForBuild(target.getParent().getFullName(), target.getNumber())) {
                    return Collections.singleton(new InteractiveViewRunAction(target));
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "could not build view run action for " + target);
            }
            return Collections.emptySet();
        }
    }
}
