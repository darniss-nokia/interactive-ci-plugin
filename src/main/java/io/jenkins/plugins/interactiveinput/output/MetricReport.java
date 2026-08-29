// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.output;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named group of {@link Metric}s published by one {@code interactiveOutput} call (e.g. "Cost report"
 * or "Carbon footprint"). A build may hold several reports; they are stored in the build's
 * {@link InteractiveOutputBuildAction} and therefore persisted in {@code build.xml} via XStream.
 */
public class MetricReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Cross-build trend as a line chart (default). */
    public static final String CHART_LINE = "line";

    /** Cross-build trend as a grouped bar chart. */
    public static final String CHART_BAR = "bar";

    /** The latest build's numeric metrics as a pie chart. */
    public static final String CHART_PIE = "pie";

    /**
     * The latest build's metrics as a time series, when each metric's LABEL is a date/timestamp
     * (e.g. {@code 2026-07-01}). Points are emitted sorted by date and can be re-bucketed
     * (day/month/year) client-side. Labels that do not parse as a date are skipped.
     */
    public static final String CHART_TIMESERIES = "timeseries";

    @NonNull
    private final String name;

    @NonNull
    private final List<Metric> metrics;

    private final long createdTs;

    /**
     * Preferred chart type for this report's per-job visualisation ({@link #CHART_LINE}/{@link #CHART_BAR}
     * /{@link #CHART_PIE}). XStream-safe: {@code null} in reports persisted before this field existed, so
     * {@link #getChartType()} defaults them to {@code line}.
     */
    @CheckForNull
    private final String chartType;

    public MetricReport(@NonNull String name, @NonNull List<Metric> metrics, long createdTs) {
        this(name, metrics, createdTs, null);
    }

    public MetricReport(
            @NonNull String name, @NonNull List<Metric> metrics, long createdTs, @CheckForNull String chartType) {
        this.name = name;
        this.metrics = new ArrayList<>(metrics);
        this.createdTs = createdTs;
        this.chartType = normalizeChartType(chartType);
    }

    /** @return a known chart type ({@code line}/{@code bar}/{@code pie}/{@code timeseries}) if valid, else {@code null}. */
    @CheckForNull
    private static String normalizeChartType(@CheckForNull String type) {
        if (type == null) {
            return null;
        }
        String t = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (CHART_LINE.equals(t) || CHART_BAR.equals(t) || CHART_PIE.equals(t) || CHART_TIMESERIES.equals(t)) {
            return t;
        }
        return null;
    }

    @NonNull
    public String getName() {
        return name;
    }

    /** @return the report's chart type, defaulting legacy/blank/unknown values to {@link #CHART_LINE}. */
    @NonNull
    public String getChartType() {
        return chartType != null ? chartType : CHART_LINE;
    }

    @NonNull
    public List<Metric> getMetrics() {
        return Collections.unmodifiableList(metrics);
    }

    public long getCreatedTs() {
        return createdTs;
    }

    @NonNull
    public java.util.Date getCreatedDate() {
        return new java.util.Date(createdTs);
    }
}
