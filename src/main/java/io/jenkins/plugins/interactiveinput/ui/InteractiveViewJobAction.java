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
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.TransientActionFactory;

/**
 * Per-job "Interactive View" surface. Attached to every {@link Job} by {@link Factory} when the
 * per-project surfaces are enabled; the sidebar link shows whenever the job has any review the viewer
 * can read (so decided reviews — and their comment history — stay reachable after the build completes),
 * while the live count badge still reflects only open notifications the viewer should act on. Its
 * {@code index.jelly} lists the job's reviews across builds, each linking to that build's review editor.
 */
public class InteractiveViewJobAction implements Action {

    private static final Logger LOGGER = Logger.getLogger(InteractiveViewJobAction.class.getName());

    /** Stable URL segment under the job (e.g. {@code /job/foo/interactive-view/}). */
    public static final String URL_NAME = "interactive-view";

    private final Job<?, ?> job;

    public InteractiveViewJobAction(@NonNull Job<?, ?> job) {
        this.job = job;
    }

    static boolean enabled() {
        return InteractiveInputAppearanceConfig.perProjectCentreEnabled()
                && InteractiveInputGlobalConfig.featuresOrDefault().isInteractiveView();
    }

    @NonNull
    public Job<?, ?> getJob() {
        return job;
    }

    @NonNull
    public String getJobFullName() {
        return job.getFullName();
    }

    /** @return every review for this job the current viewer may read (any status), newest builds first. */
    @NonNull
    public List<ReviewDocument> getReviews() {
        try {
            List<ReviewDocument> reviews = ViewStore.get().listForJob(job.getFullName());
            reviews.sort((a, b) -> Long.compare(b.getCreatedTs(), a.getCreatedTs()));
            return reviews;
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not list reviews for " + job.getFullName());
            return Collections.emptyList();
        }
    }

    /**
     * @return this job's readable reviews grouped by report name (a stable, newest-first ordering),
     *     so the page can render a section per report/folder across builds. A {@link LinkedHashMap}
     *     preserves the newest-first insertion order for both the group headings and their rows.
     */
    @NonNull
    public Map<String, List<ReviewDocument>> getReviewGroups() {
        Map<String, List<ReviewDocument>> groups = new LinkedHashMap<>();
        for (ReviewDocument r : getReviews()) {
            String key = r.getReportName();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        return groups;
    }

    /**
     * @return the open, notify-enabled reviews this job should surface to the current viewer as a live
     *     count badge, honouring the user-scope switch (0 on any store error). Note this is the
     *     notification count, not the sidebar-link visibility gate — see {@link #isHasAnyReviews()}.
     */
    public int getPendingCount() {
        try {
            return ViewStore.get().countNotificationsForJob(job.getFullName());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not count open reviews for " + job.getFullName());
            return 0;
        }
    }

    /**
     * @return {@code true} if the job has any review the current viewer may read (any status). Gates
     *     the sidebar link so completed/decided reviews (and their comment history) stay reachable
     *     after the build finishes, mirroring the per-build audit link's "stays visible" behaviour.
     */
    public boolean isHasAnyReviews() {
        try {
            return !ViewStore.get().listForJob(job.getFullName()).isEmpty();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not check reviews for " + job.getFullName());
            return false;
        }
    }

    /**
     * @return {@code true} if the review's owning build has been deleted. The per-job page keeps listing
     *     such reviews (their comment/decision history is durable) but marks them "build deleted" and
     *     drops the link to the — now absent — per-build review editor. Called per row from
     *     {@code index.jelly}. The flag is set by {@code BuildLifecycleCleanup} on deletion (and by its
     *     startup reconcile for builds deleted earlier), so no live build lookup is needed here.
     */
    public boolean isBuildDeleted(@NonNull ReviewDocument r) {
        return r.isBuildDeleted();
    }

    public int getPollingIntervalSeconds() {
        return InteractiveInputGlobalConfig.pollingIntervalSecondsOrDefault();
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return enabled() && isHasAnyReviews() ? InteractiveViewRunAction.ICON : null;
    }

    /**
     * @return "Interactive View", suffixed with the pending count {@code (N)} when there is at least one
     *     open notification. This surfaces the count in the experimental "more actions" overflow menu
     *     (which is server-rendered from the display name). In the classic sidebar {@code bell.js} resets
     *     the label to plain "Interactive View" and shows the count as a live pill instead, so there is no
     *     double count.
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

    /** Attaches {@link InteractiveViewJobAction} to every job when per-project surfaces are enabled. */
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
            return Collections.singleton(new InteractiveViewJobAction(target));
        }
    }
}
