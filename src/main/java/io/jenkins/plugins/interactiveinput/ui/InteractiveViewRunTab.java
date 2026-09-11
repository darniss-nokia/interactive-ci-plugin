// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Run;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Tab;
import jenkins.model.TransientActionFactory;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.GET;

/**
 * Native "Interactive View" card for the experimental run overview — the review counterpart of
 * {@link InteractiveOutputRunTab}. Rendered by core's {@code jenkins/run/OverviewTab/index.jelly}, which
 * iterates {@code Run#getRunTabs()} and includes each {@link Tab}'s {@code widget.jelly} on the overview
 * grid. It shows a compact, server-rendered "mini" of the build's {@code interactive-view/} page (the
 * list of reviews, each with its status and comment count), so reviewers see the reviews at a glance
 * without opening the dedicated page.
 *
 * <p>This tab is only <em>visible</em> when the viewer has the experimental build page enabled AND has at
 * least one readable review on this build ({@link #getIconFileName()} returns {@code null} otherwise, and
 * {@code getRunTabs()} filters out null-icon tabs), so the classic layout is untouched and the persisted
 * {@link InteractiveViewRunAction} keeps serving its own sidebar link / overflow entry there.
 *
 * <p>Clicking the tab redirects to the canonical {@code interactive-view/} page (which owns the full
 * review editor and enforces its own permissions); the tab uses a distinct URL to avoid colliding with
 * that action's route.
 */
public class InteractiveViewRunTab extends Tab {

    private static final Logger LOGGER = Logger.getLogger(InteractiveViewRunTab.class.getName());

    /** Distinct URL segment (redirects to {@link InteractiveViewRunAction#URL_NAME}). */
    public static final String URL_NAME = "interactive-view-overview";

    public InteractiveViewRunTab(@NonNull Run<?, ?> run) {
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

    /**
     * @return the reviews for this build the current viewer may read (any status). Queries the
     *     {@link ViewStore} directly rather than {@code run.getAction(...)}: the view action is a
     *     transient action, so it is absent from the persisted list, and {@code getAllActions()} would
     *     re-run every {@link TransientActionFactory} (recursing into this tab's own factory).
     */
    @NonNull
    public List<ReviewDocument> getReviews() {
        try {
            return ViewStore.get().listForBuild(getJobFullName(), getBuildNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not list reviews for " + getRun());
            return Collections.emptyList();
        }
    }

    /**
     * @return this build's readable reviews grouped by report name, preserving order via a
     *     {@link LinkedHashMap} so the card can render a section per report/folder — mirroring
     *     {@link InteractiveViewJobAction#getReviewGroups()} but scoped to this build.
     */
    @NonNull
    public Map<String, List<ReviewDocument>> getReviewGroups() {
        Map<String, List<ReviewDocument>> groups = new LinkedHashMap<>();
        for (ReviewDocument r : getReviews()) {
            groups.computeIfAbsent(r.getReportName(), k -> new ArrayList<>()).add(r);
        }
        return groups;
    }

    /**
     * @return the review groups prepared for the compact card. Wraps {@link #getReviewGroups()} and
     *     precomputes, per group, whether its report-name heading should render — done here (not in the
     *     Jelly view) so the rule is unit-testable and {@code widget.jelly} needs no list-size / element
     *     logic. The heading is suppressed only when it would merely duplicate a lone file whose title is
     *     just the report name (the row already shows that title) — the common single-file publish, where
     *     {@link io.jenkins.plugins.interactiveinput.step.InteractiveViewStepExecution} defaults an unset
     *     {@code title} to the {@code reportName}. Multi-file (glob/dir) groups and custom titles keep it.
     */
    @NonNull
    public List<OverviewGroup> getOverviewGroups() {
        List<OverviewGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<ReviewDocument>> e : getReviewGroups().entrySet()) {
            out.add(new OverviewGroup(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * A report group prepared for {@code widget.jelly}: the report name, its reviews, and whether the
     * report-name heading adds information — so the template needs no list-size or element logic.
     */
    public static final class OverviewGroup {

        private final String reportName;
        private final List<ReviewDocument> reviews;
        private final boolean showHeading;

        OverviewGroup(@NonNull String reportName, @NonNull List<ReviewDocument> reviews) {
            this.reportName = reportName;
            this.reviews = reviews;
            // Groups are built from a non-empty review list, so get(0) is safe. The heading only adds
            // information for a real multi-file group or when a lone file carries a title of its own.
            this.showHeading = reviews.size() > 1 || !reviews.get(0).getTitle().equals(reportName);
        }

        @NonNull
        public String getReportName() {
            return reportName;
        }

        @NonNull
        public List<ReviewDocument> getReviews() {
            return reviews;
        }

        /** @return {@code false} only for a lone file whose title is just the report name (redundant heading). */
        public boolean isShowHeading() {
            return showHeading;
        }
    }

    /** @return the theme-aware review symbol (for {@code <l:icon>} in the card). */
    @NonNull
    public String getIconClassName() {
        return InteractiveViewRunAction.ICON;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        // Cheap checks first (classic layout / Appearance toggle) so the store lookup is skipped when the
        // card cannot show anyway.
        return ExperimentalLayout.newBuildPage()
                        && InteractiveInputAppearanceConfig.viewBuildCardEnabled()
                        && !getReviews().isEmpty()
                ? InteractiveViewRunAction.ICON
                : null;
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "Interactive View";
    }

    @Override
    @NonNull
    public String getUrlName() {
        return URL_NAME;
    }

    /**
     * Redirects the tab's own page to the canonical per-build review page. GET-only (a tab click);
     * {@link Item#READ} is checked here so the method is self-contained — the destination page
     * enforces the same permission.
     */
    // lgtm[jenkins/csrf] -- read-only redirect, no side effects
    @GET
    public void doIndex(@NonNull StaplerRequest2 req, @NonNull StaplerResponse2 rsp) throws IOException {
        getRun().checkPermission(Item.READ);
        rsp.sendRedirect2(req.getContextPath() + "/" + getRun().getUrl() + InteractiveViewRunAction.URL_NAME + "/");
    }

    /**
     * Attaches the tab to any build that has review documents (same gate as {@link InteractiveViewRunAction}).
     * Attachment is layout-independent (cheap, deterministic); actual visibility is gated by
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
            if (!InteractiveViewRunAction.enabled()) {
                return Collections.emptySet();
            }
            try {
                if (ViewStore.get().hasAnyForBuild(target.getParent().getFullName(), target.getNumber())) {
                    return Collections.singleton(new InteractiveViewRunTab(target));
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "could not build view run tab for " + target);
            }
            return Collections.emptySet();
        }
    }
}
