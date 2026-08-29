// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.output;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.TransientActionFactory;
import net.sf.json.JSONArray;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

/**
 * Per-job "Interactive Output" trend surface. Iterates the job's builds, reads each build's persisted
 * {@link InteractiveOutputBuildAction}, and assembles a compact JSON model (build labels + one numeric
 * series per metric {@code key}) that {@code output.js} renders as a theme-aware ECharts line chart.
 *
 * <p>Attached to every {@link Job} by {@link Factory} when the per-project surfaces feature is on; the
 * sidebar link only appears when at least one build actually published output (so it is never permanent
 * clutter). All heavy lifting (reading builds) is bounded to the most recent {@link #MAX_BUILDS}.
 */
public class InteractiveOutputJobAction implements Action {

    private static final Logger LOGGER = Logger.getLogger(InteractiveOutputJobAction.class.getName());

    /** Stable URL segment under the job (e.g. {@code /job/foo/interactive-output/}). */
    public static final String URL_NAME = "interactive-output";

    /** Bound on how many recent builds the trend scans, to protect the controller on long histories. */
    static final int MAX_BUILDS = 50;

    private final Job<?, ?> job;

    public InteractiveOutputJobAction(@NonNull Job<?, ?> job) {
        this.job = job;
    }

    static boolean enabled() {
        return InteractiveInputAppearanceConfig.perProjectCentreEnabled()
                && InteractiveInputGlobalConfig.featuresOrDefault().isInteractiveOutput();
    }

    @NonNull
    public Job<?, ?> getJob() {
        return job;
    }

    @NonNull
    public String getJobFullName() {
        return job.getFullName();
    }

    /**
     * @return the most recent build (newest first) that published a non-empty output action, or
     *     {@code null} if none has. Used to show "latest values" cards on the job page.
     */
    @CheckForNull
    public Run<?, ?> getLatestRun() {
        int scanned = 0;
        for (Run<?, ?> r : job.getBuilds()) {
            if (scanned++ >= MAX_BUILDS) {
                break;
            }
            InteractiveOutputBuildAction a = r.getAction(InteractiveOutputBuildAction.class);
            if (a != null && !a.isEmpty()) {
                return r;
            }
        }
        return null;
    }

    /** @return the latest build's reports (newest first), or an empty list if none published output. */
    @NonNull
    public List<MetricReport> getLatestReports() {
        Run<?, ?> r = getLatestRun();
        if (r == null) {
            return Collections.emptyList();
        }
        InteractiveOutputBuildAction a = r.getAction(InteractiveOutputBuildAction.class);
        return a != null ? a.getReports() : Collections.emptyList();
    }

    /** @return {@code true} if any scanned build published output at all (drives the sidebar link). */
    public boolean isHasAnyOutput() {
        return getLatestRun() != null;
    }

    /** @return {@code true} if at least one numeric series exists to plot. */
    public boolean isHasTrend() {
        return !collect().builds.isEmpty();
    }

    /** @return {@code true} if there is at least one renderable per-report chart. */
    public boolean isHasCharts() {
        return !getCharts().isEmpty();
    }

    /**
     * @return one chart model per report (in first-appearance order) for the per-job page. A report's
     *     {@code line}/{@code bar} chart is its numeric series across the recent builds; a {@code pie}
     *     chart is the latest build's numeric metrics as slices; a {@code timeseries} chart is the latest
     *     build's date-labelled metrics as points sorted by date. Reports with no plottable data are
     *     omitted. Each {@link Chart} carries the JSON {@code output.js} renders.
     */
    @NonNull
    public List<Chart> getCharts() {
        List<Run<?, ?>> recent = new ArrayList<>();
        int scanned = 0;
        for (Run<?, ?> r : job.getBuilds()) {
            if (scanned++ >= MAX_BUILDS) {
                break;
            }
            recent.add(r);
        }
        Collections.reverse(recent); // chronological (oldest first) so "latest wins" is the newest build

        Map<String, ReportAgg> byReport = new LinkedHashMap<>();
        for (Run<?, ?> r : recent) {
            InteractiveOutputBuildAction a = r.getAction(InteractiveOutputBuildAction.class);
            if (a == null || a.isEmpty()) {
                continue;
            }
            for (MetricReport rep : a.getReports()) {
                ReportAgg agg = byReport.computeIfAbsent(rep.getName(), k -> new ReportAgg());
                agg.chartType = rep.getChartType(); // latest build's choice wins
                Map<String, Double> vals = new LinkedHashMap<>();
                JSONArray sliceCandidates = new JSONArray();
                for (Metric m : rep.getMetrics()) {
                    if (m.isNumeric()) {
                        String key = m.seriesKey();
                        agg.seriesNames.put(key, m.getLabel()); // latest label wins
                        vals.put(key, m.numericValue()); // later value in a build wins
                        JSONObject slice = new JSONObject();
                        slice.put("name", m.getLabel());
                        slice.put("value", m.numericValue());
                        sliceCandidates.add(slice);
                    }
                }
                if (!vals.isEmpty()) {
                    agg.buildLabels.add("#" + r.getNumber());
                    agg.buildUrls.add(r.getUrl() + InteractiveOutputBuildAction.URL_NAME + "/");
                    agg.perBuildValues.add(vals);
                    agg.latestSlices = sliceCandidates; // newest build with numeric data wins
                    agg.latestUrl = r.getUrl() + InteractiveOutputBuildAction.URL_NAME + "/";
                }
            }
        }

        List<Chart> charts = new ArrayList<>();
        for (Map.Entry<String, ReportAgg> e : byReport.entrySet()) {
            String name = e.getKey();
            ReportAgg agg = e.getValue();
            String type = agg.chartType;
            if (MetricReport.CHART_PIE.equals(type)) {
                if (agg.latestSlices == null || agg.latestSlices.isEmpty()) {
                    continue;
                }
                JSONObject model = new JSONObject();
                model.put("report", name);
                model.put("type", MetricReport.CHART_PIE);
                model.put("url", agg.latestUrl == null ? "" : agg.latestUrl);
                model.put("slices", agg.latestSlices);
                charts.add(new Chart(name, MetricReport.CHART_PIE, model.toString()));
            } else if (MetricReport.CHART_TIMESERIES.equals(type)) {
                JSONArray points = timeseriesPoints(agg.latestSlices);
                if (points.isEmpty()) {
                    continue; // no metric label parsed as a date — nothing to plot
                }
                JSONObject model = new JSONObject();
                model.put("report", name);
                model.put("type", MetricReport.CHART_TIMESERIES);
                model.put("url", agg.latestUrl == null ? "" : agg.latestUrl);
                model.put("points", points);
                charts.add(new Chart(name, MetricReport.CHART_TIMESERIES, model.toString()));
            } else {
                if (agg.buildLabels.isEmpty() || agg.seriesNames.isEmpty()) {
                    continue;
                }
                String resolved =
                        MetricReport.CHART_BAR.equals(type) ? MetricReport.CHART_BAR : MetricReport.CHART_LINE;
                JSONObject model = new JSONObject();
                model.put("report", name);
                model.put("type", resolved);
                model.put("builds", JSONArray.fromObject(agg.buildLabels));
                model.put("urls", JSONArray.fromObject(agg.buildUrls));
                JSONArray series = new JSONArray();
                for (Map.Entry<String, String> s : agg.seriesNames.entrySet()) {
                    JSONObject so = new JSONObject();
                    so.put("name", s.getValue());
                    JSONArray data = new JSONArray();
                    for (Map<String, Double> vals : agg.perBuildValues) {
                        Double d = vals.get(s.getKey());
                        data.add(d == null ? JSONNull.getInstance() : d);
                    }
                    so.put("data", data);
                    series.add(so);
                }
                model.put("series", series);
                charts.add(new Chart(name, resolved, model.toString()));
            }
        }
        return charts;
    }

    /**
     * Matches date/timestamp labels used by {@code timeseries} reports: {@code yyyy}, {@code yyyy-MM},
     * {@code yyyy-MM-dd} and {@code yyyy-MM-dd[ T]HH:mm[:ss]}. Missing parts default to the start of the
     * period (month 1, day 1, 00:00:00), so points sort correctly regardless of granularity.
     */
    private static final java.util.regex.Pattern TS_LABEL = java.util.regex.Pattern.compile(
            "^(\\d{4})(?:-(\\d{2})(?:-(\\d{2})(?:[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?)?)?)?$");

    /**
     * Builds the sorted {@code points} array for a {@code timeseries} chart from the latest build's numeric
     * slices ({@code {name,value}}). Each slice whose {@code name} parses as a date becomes a point
     * {@code {t:<epochMs>,label:<name>,value:<num>}}; non-date labels are skipped. Sorted ascending by date.
     */
    @NonNull
    private static JSONArray timeseriesPoints(@CheckForNull JSONArray latestSlices) {
        JSONArray out = new JSONArray();
        if (latestSlices == null || latestSlices.isEmpty()) {
            return out;
        }
        List<JSONObject> pts = new ArrayList<>();
        for (int i = 0; i < latestSlices.size(); i++) {
            JSONObject slice = latestSlices.getJSONObject(i);
            String label = slice.optString("name", "");
            Long ts = parseLabelEpochMs(label);
            if (ts == null) {
                continue;
            }
            JSONObject p = new JSONObject();
            p.put("t", ts);
            p.put("label", label);
            p.put("value", slice.get("value"));
            pts.add(p);
        }
        pts.sort(java.util.Comparator.comparingLong(p -> p.getLong("t")));
        out.addAll(pts);
        return out;
    }

    /**
     * @return the UTC epoch-millis for a date/timestamp label (see {@link #TS_LABEL}), or {@code null} if the
     *     label is not a recognised date. UTC keeps sorting/bucketing independent of the controller's zone.
     */
    @CheckForNull
    private static Long parseLabelEpochMs(@CheckForNull String label) {
        if (label == null) {
            return null;
        }
        java.util.regex.Matcher m = TS_LABEL.matcher(label.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            int year = Integer.parseInt(m.group(1));
            int month = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
            int day = m.group(3) != null ? Integer.parseInt(m.group(3)) : 1;
            int hour = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
            int minute = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
            int second = m.group(6) != null ? Integer.parseInt(m.group(6)) : 0;
            // LocalDateTime.of validates ranges (e.g. rejects month 13 or Feb 30), throwing DateTimeException.
            return java.time.LocalDateTime.of(year, month, day, hour, minute, second)
                    .toInstant(java.time.ZoneOffset.UTC)
                    .toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * @return the trend model as a JSON string: {@code {"builds":[...],"urls":[...],
     *     "series":[{"name":..,"data":[num|null,..]}]}}. Consumed by {@code output.js}; empty series are
     *     omitted. Values are aligned to {@code builds} (null where a build did not report that key).
     */
    @NonNull
    public String getTrendJson() {
        Trend t = collect();
        JSONObject root = new JSONObject();
        root.put("builds", JSONArray.fromObject(t.builds));
        root.put("urls", JSONArray.fromObject(t.urls));
        JSONArray series = new JSONArray();
        for (Map.Entry<String, List<Double>> e : t.series.entrySet()) {
            JSONObject s = new JSONObject();
            s.put("name", t.seriesNames.getOrDefault(e.getKey(), e.getKey()));
            JSONArray data = new JSONArray();
            for (Double d : e.getValue()) {
                if (d == null) {
                    data.add(JSONNull.getInstance());
                } else {
                    data.add(d);
                }
            }
            s.put("data", data);
            series.add(s);
        }
        root.put("series", series);
        return root.toString();
    }

    /**
     * Two-pass scan of the recent builds (oldest→newest): discover numeric series in chronological order
     * of first appearance, then align each series' values to the included build axis.
     */
    private Trend collect() {
        List<Run<?, ?>> recent = new ArrayList<>();
        int scanned = 0;
        for (Run<?, ?> r : job.getBuilds()) {
            if (scanned++ >= MAX_BUILDS) {
                break;
            }
            recent.add(r);
        }
        Collections.reverse(recent); // chronological (oldest first)

        Trend t = new Trend();
        // Pass 1: per build, gather this build's numeric values by series key.
        List<Map<String, Double>> perBuild = new ArrayList<>();
        List<Run<?, ?>> included = new ArrayList<>();
        for (Run<?, ?> r : recent) {
            InteractiveOutputBuildAction a = r.getAction(InteractiveOutputBuildAction.class);
            if (a == null || a.isEmpty()) {
                continue;
            }
            Map<String, Double> vals = new LinkedHashMap<>();
            for (MetricReport rep : a.getReports()) {
                for (Metric m : rep.getMetrics()) {
                    if (m.isNumeric()) {
                        String key = m.seriesKey();
                        t.seriesNames.put(key, m.getLabel()); // latest label wins
                        vals.put(key, m.numericValue()); // later value in a build wins
                    }
                }
            }
            if (!vals.isEmpty()) {
                included.add(r);
                perBuild.add(vals);
            }
        }
        if (included.isEmpty()) {
            return t;
        }
        // Initialise one aligned list per discovered series.
        for (String key : t.seriesNames.keySet()) {
            t.series.put(key, new ArrayList<>());
        }
        // Pass 2: align values to the included-build axis (null where missing).
        for (int i = 0; i < included.size(); i++) {
            Run<?, ?> r = included.get(i);
            Map<String, Double> vals = perBuild.get(i);
            t.builds.add("#" + r.getNumber());
            t.urls.add(r.getUrl() + InteractiveOutputBuildAction.URL_NAME + "/");
            for (Map.Entry<String, List<Double>> s : t.series.entrySet()) {
                s.getValue().add(vals.get(s.getKey()));
            }
        }
        return t;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return enabled() && isHasAnyOutput() ? InteractiveOutputBuildAction.ICON : null;
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

    /** Mutable accumulator for {@link #collect()}. */
    private static final class Trend {
        final List<String> builds = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        final Map<String, String> seriesNames = new LinkedHashMap<>();
        final Map<String, List<Double>> series = new LinkedHashMap<>();
    }

    /** Per-report accumulator for {@link #getCharts()}. */
    private static final class ReportAgg {
        final Map<String, String> seriesNames = new LinkedHashMap<>();
        final List<String> buildLabels = new ArrayList<>();
        final List<String> buildUrls = new ArrayList<>();
        final List<Map<String, Double>> perBuildValues = new ArrayList<>();
        String chartType = MetricReport.CHART_LINE;
        JSONArray latestSlices;
        String latestUrl;
    }

    /** A single per-report chart model exposed to {@code index.jelly} / {@code output.js}. */
    public static final class Chart {
        private final String reportName;
        private final String type;
        private final String json;

        Chart(@NonNull String reportName, @NonNull String type, @NonNull String json) {
            this.reportName = reportName;
            this.type = type;
            this.json = json;
        }

        @NonNull
        public String getReportName() {
            return reportName;
        }

        @NonNull
        public String getType() {
            return type;
        }

        /** @return the JSON model string ({@code output.js} reads it from the {@code data-io-model} attr). */
        @NonNull
        public String getJson() {
            return json;
        }
    }

    /** Attaches {@link InteractiveOutputJobAction} to every job when the per-project centre is enabled. */
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
            try {
                return Collections.singleton(new InteractiveOutputJobAction(target));
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "could not create output job action for " + target.getFullName());
                return Collections.emptySet();
            }
        }
    }
}
