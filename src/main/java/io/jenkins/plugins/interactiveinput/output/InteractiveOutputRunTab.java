// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.output;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Run;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.ui.ExperimentalLayout;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Tab;
import jenkins.model.TransientActionFactory;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * Native "Interactive Output" card for the experimental run overview. Rendered by core's
 * {@code jenkins/run/OverviewTab/index.jelly}, which iterates {@code Run#getRunTabs()} and includes
 * each {@link Tab}'s {@code widget.jelly} on the overview grid — the native alternative to the
 * "Legacy" card that collects old-style {@code summary.jelly} rows.
 *
 * <p>The card reuses the KPI markup and {@code bell.css} styles of
 * {@link InteractiveOutputBuildAction}'s {@code summary.jelly}. This tab is only <em>visible</em> when
 * the viewer has the experimental build page enabled ({@link #getIconFileName()} returns {@code null}
 * otherwise, and {@code getRunTabs()} filters out null-icon tabs), so the classic layout is untouched
 * and the persisted action keeps serving its own sidebar link / overflow entry there.
 *
 * <p>Clicking the tab redirects to the canonical {@code interactive-output/} page (which owns the full
 * report table + trend and enforces its own permissions); the tab uses a distinct URL to avoid
 * colliding with that action's route.
 */
public class InteractiveOutputRunTab extends Tab {

    private static final Logger LOGGER = Logger.getLogger(InteractiveOutputRunTab.class.getName());

    /** Distinct URL segment (redirects to {@link InteractiveOutputBuildAction#URL_NAME}). */
    public static final String URL_NAME = "interactive-output-overview";

    public InteractiveOutputRunTab(@NonNull Run<?, ?> run) {
        super(run);
    }

    @NonNull
    public Run<?, ?> getRun() {
        return (Run<?, ?>) getObject();
    }

    @CheckForNull
    private InteractiveOutputBuildAction action() {
        return findOn(getRun());
    }

    /**
     * Finds the persisted {@link InteractiveOutputBuildAction} on a run by scanning the persisted action
     * list only. Using {@code getActions()} (not {@code getAction(Class)}/{@code getAllActions()}) is
     * essential: the latter re-run every {@link TransientActionFactory}, so calling them from inside
     * this tab's factory would recurse into itself (StackOverflowError). The output action is always
     * persisted by the step, so the persisted list is sufficient.
     */
    @CheckForNull
    private static InteractiveOutputBuildAction findOn(@NonNull Run<?, ?> run) {
        for (Action a : run.getActions()) {
            if (a instanceof InteractiveOutputBuildAction out) {
                return out;
            }
        }
        return null;
    }

    /** @return the reports of the underlying build action (empty if it went away). */
    @NonNull
    public List<MetricReport> getReports() {
        InteractiveOutputBuildAction a = action();
        return a == null ? Collections.emptyList() : a.getReports();
    }

    /** @return the theme-aware output symbol (for {@code <l:icon>} in the card). */
    @NonNull
    public String getIconClassName() {
        return InteractiveOutputBuildAction.ICON;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return ExperimentalLayout.newBuildPage() && InteractiveInputAppearanceConfig.outputBuildCardEnabled()
                ? InteractiveOutputBuildAction.ICON
                : null;
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "Interactive Output";
    }

    @Override
    @NonNull
    public String getUrlName() {
        return URL_NAME;
    }

    /** Redirects the tab's own page to the canonical output action page. */
    public void doIndex(@NonNull StaplerRequest2 req, @NonNull StaplerResponse2 rsp) throws IOException {
        rsp.sendRedirect2(req.getContextPath() + "/" + getRun().getUrl() + InteractiveOutputBuildAction.URL_NAME + "/");
    }

    /**
     * Attaches the tab to any build carrying a visible {@link InteractiveOutputBuildAction}. Attachment
     * is layout-independent (cheap, deterministic); actual visibility is gated by
     * {@link #getIconFileName()} so nothing shows in the classic layout.
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
            try {
                InteractiveOutputBuildAction a = findOn(target);
                if (a != null && a.isVisible()) {
                    return Collections.singleton(new InteractiveOutputRunTab(target));
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "could not build output run tab for " + target);
            }
            return Collections.emptySet();
        }
    }
}
