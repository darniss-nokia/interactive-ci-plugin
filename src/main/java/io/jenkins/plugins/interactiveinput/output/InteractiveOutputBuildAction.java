// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.output;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.ui.ExperimentalLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.RunAction2;

/**
 * Per-build "Interactive Output" surface published by the {@code interactiveOutput} step. Holds one or
 * more {@link MetricReport}s and is stored directly in the build's action list, so it is persisted in
 * {@code build.xml} via XStream (no separate store needed — the data is naturally durable and
 * build-scoped).
 *
 * <p>{@code summary.jelly} contributes KPI cards + a table to the build's main page; {@code index.jelly}
 * is the dedicated details page reached from the left-sidebar link. Multiple {@code interactiveOutput}
 * calls in one build append to the single action instance (see the step), so the build shows one
 * "Interactive Output" link listing every report.
 */
public class InteractiveOutputBuildAction implements RunAction2 {

    /** Stable URL segment under the run (e.g. {@code /job/foo/3/interactive-output/}). */
    public static final String URL_NAME = "interactive-output";

    /** Theme-aware symbol for the output surfaces. */
    public static final String ICON = "symbol-stats-chart-outline plugin-ionicons-api";

    @NonNull
    private final List<MetricReport> reports = new ArrayList<>();

    private transient Run<?, ?> run;

    public InteractiveOutputBuildAction() {
        // Reports are appended via addReport as the step runs.
    }

    private static boolean enabled() {
        return InteractiveInputAppearanceConfig.perProjectCentreEnabled()
                && InteractiveInputGlobalConfig.featuresOrDefault().isInteractiveOutput();
    }

    public synchronized void addReport(@NonNull MetricReport report) {
        reports.add(report);
    }

    @NonNull
    public synchronized List<MetricReport> getReports() {
        return Collections.unmodifiableList(new ArrayList<>(reports));
    }

    public synchronized boolean isEmpty() {
        return reports.isEmpty();
    }

    @CheckForNull
    public Run<?, ?> getRun() {
        return run;
    }

    /**
     * @return the owning run. Required by core's {@code l:run-subpage} ({@code it.object}).
     */
    @CheckForNull
    public Run<?, ?> getObject() {
        return run;
    }

    /** @return the theme-aware symbol class for the output surfaces (for {@code <l:icon src=..>}). */
    @NonNull
    public String getIconClassName() {
        return ICON;
    }

    /** @return whether the build-page summary/detail surfaces should render (feature on and non-empty). */
    public boolean isVisible() {
        return enabled() && !isEmpty();
    }

    /**
     * @return whether the classic {@code summary.jelly} row should render. Suppressed under the
     *     experimental build page, where the KPI card is instead shown natively by
     *     {@link InteractiveOutputRunTab} (avoiding a duplicate inside core's "Legacy" card).
     */
    public boolean isClassicSummaryVisible() {
        return isVisible()
                && !ExperimentalLayout.newBuildPage()
                && InteractiveInputAppearanceConfig.outputBuildCardEnabled();
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return enabled() && !isEmpty() ? ICON : null;
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
}
