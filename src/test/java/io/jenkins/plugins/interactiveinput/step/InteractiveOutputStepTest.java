// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.interactiveinput.output.InteractiveOutputBuildAction;
import io.jenkins.plugins.interactiveinput.output.InteractiveOutputJobAction;
import io.jenkins.plugins.interactiveinput.output.Metric;
import io.jenkins.plugins.interactiveinput.output.MetricReport;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for the {@code interactiveOutput} step: it ships a Snippet-Generator form with a describable
 * {@link Metric}, persists a {@link MetricReport} in the build's {@link InteractiveOutputBuildAction},
 * parses numeric values robustly, and feeds a per-job trend across builds.
 */
@WithJenkins
class InteractiveOutputStepTest {

    @Test
    void stepAndMetricShipConfigForms(JenkinsRule j) throws Exception {
        StepDescriptor d = (StepDescriptor) j.jenkins.getDescriptor(InteractiveOutputStep.class);
        assertNotNull(d, "interactiveOutput step descriptor must be registered");
        assertEquals("interactiveOutput", d.getFunctionName());
        assertNotNull(d.getConfigPage(), "interactiveOutput must ship a config.jelly for the Snippet Generator");
        assertNotNull(j.jenkins.getDescriptor(Metric.class), "Metric must be a Describable with a Descriptor");
        InteractiveOutputStep.DescriptorImpl out = (InteractiveOutputStep.DescriptorImpl) d;
        assertEquals(4, out.doFillChartTypeItems().size(), "chart-type dropdown lists the four chart kinds");
        assertNotNull(
                InteractiveOutputStep.DescriptorImpl.class
                        .getMethod("doFillChartTypeItems")
                        .getAnnotation(org.kohsuke.stapler.verb.POST.class),
                "chart-type fill must be @POST (Jenkins Security Scan CSRF)");
    }

    @Test
    void publishesMetricsIntoTheBuildAction(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "out-build",
                "interactiveOutput(reportName: 'Cost report', metrics: [\n"
                        + "  [label: 'Total cost', value: '12.50', unit: 'USD', key: 'cost'],\n"
                        + "  [label: 'Status', value: 'green']\n"
                        + "])");
        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        InteractiveOutputBuildAction action = b.getAction(InteractiveOutputBuildAction.class);
        assertNotNull(action, "the build must carry an InteractiveOutputBuildAction");
        List<MetricReport> reports = action.getReports();
        assertEquals(1, reports.size());
        MetricReport rep = reports.get(0);
        assertEquals("Cost report", rep.getName());
        assertEquals(2, rep.getMetrics().size());
        Metric cost = rep.getMetrics().get(0);
        assertEquals("Total cost", cost.getLabel());
        assertEquals("12.50 USD", cost.getDisplayValue());
        assertTrue(cost.isNumeric());
        assertFalse(rep.getMetrics().get(1).isNumeric(), "a non-numeric value must not feed the trend");
    }

    @Test
    void metricParsesNumbersWithCurrencyAndSeparators(JenkinsRule j) {
        assertEquals(1234.5, new Metric("c", "$1,234.5").numericValue(), 0.0001);
        assertEquals(-5.0, new Metric("c", "-5").numericValue(), 0.0001);
        assertEquals(3.14, new Metric("c", "3.14").numericValue(), 0.0001);
        assertFalse(new Metric("c", "N/A").isNumeric());
        assertFalse(new Metric("c", "").isNumeric());
    }

    @Test
    void trendAggregatesNumericSeriesAcrossBuilds(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "out-trend");
        p.setDefinition(new CpsFlowDefinition(
                "interactiveOutput(reportName: 'Cost', metrics: [[label: 'Cost', value: env.BUILD_NUMBER, key: 'cost']])",
                true));
        j.assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        j.assertBuildStatusSuccess(p.scheduleBuild2(0).get());

        InteractiveOutputJobAction job = new InteractiveOutputJobAction(p);
        assertTrue(job.isHasAnyOutput());
        assertTrue(job.isHasTrend());

        JSONObject model = JSONObject.fromObject(job.getTrendJson());
        JSONArray builds = model.getJSONArray("builds");
        assertEquals(2, builds.size(), "both builds must appear on the axis (oldest first)");
        assertEquals("#1", builds.getString(0));
        assertEquals("#2", builds.getString(1));
        JSONArray series = model.getJSONArray("series");
        assertEquals(1, series.size(), "one numeric series (keyed 'cost')");
        JSONObject cost = series.getJSONObject(0);
        assertEquals("Cost", cost.getString("name"));
        JSONArray data = cost.getJSONArray("data");
        assertEquals(1.0, data.getDouble(0), 0.0001);
        assertEquals(2.0, data.getDouble(1), 0.0001);
    }

    @Test
    void chartTypeDefaultsToLineForLegacyReportsAndNormalisesUnknowns(JenkinsRule j) {
        // Back-compat: a report persisted before chartType existed (3-arg ctor) has null → line.
        assertEquals(MetricReport.CHART_LINE, new MetricReport("R", List.of(), 0L).getChartType());
        // Unknown/blank types normalise to line; valid ones (any case) are kept.
        assertEquals(MetricReport.CHART_LINE, new MetricReport("R", List.of(), 0L, "wat").getChartType());
        assertEquals(MetricReport.CHART_BAR, new MetricReport("R", List.of(), 0L, "bar").getChartType());
        assertEquals(MetricReport.CHART_PIE, new MetricReport("R", List.of(), 0L, "PIE").getChartType());
        assertEquals(MetricReport.CHART_TIMESERIES, new MetricReport("R", List.of(), 0L, "TimeSeries").getChartType());
    }

    @Test
    void getChartsEmitsOneModelPerReportWithTheChosenType(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "out-charts");
        p.setDefinition(new CpsFlowDefinition(
                "interactiveOutput(reportName: 'Trend', chartType: 'bar', metrics: "
                        + "[[label: 'Cost', value: env.BUILD_NUMBER, key: 'cost']])\n"
                        + "interactiveOutput(reportName: 'Mix', chartType: 'pie', metrics: "
                        + "[[label: 'A', value: '3'], [label: 'B', value: '7']])",
                true));
        j.assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        j.assertBuildStatusSuccess(p.scheduleBuild2(0).get());

        InteractiveOutputJobAction job = new InteractiveOutputJobAction(p);
        assertTrue(job.isHasCharts());
        List<InteractiveOutputJobAction.Chart> charts = job.getCharts();
        assertEquals(2, charts.size(), "one chart model per report");

        // bar → the cross-build trend (builds axis + one numeric series).
        InteractiveOutputJobAction.Chart trend = chart(charts, "Trend");
        assertEquals(MetricReport.CHART_BAR, trend.getType());
        JSONObject tm = JSONObject.fromObject(trend.getJson());
        assertEquals("bar", tm.getString("type"));
        assertEquals(2, tm.getJSONArray("builds").size(), "both builds appear on the axis");
        assertEquals(1, tm.getJSONArray("series").size(), "one numeric series (keyed 'cost')");

        // pie → the latest build's numeric metrics as slices.
        InteractiveOutputJobAction.Chart mix = chart(charts, "Mix");
        assertEquals(MetricReport.CHART_PIE, mix.getType());
        JSONObject mm = JSONObject.fromObject(mix.getJson());
        assertEquals("pie", mm.getString("type"));
        JSONArray slices = mm.getJSONArray("slices");
        assertEquals(2, slices.size(), "two numeric metrics → two slices");
        assertEquals("A", slices.getJSONObject(0).getString("name"));
        assertEquals(3.0, slices.getJSONObject(0).getDouble("value"), 0.0001);
    }

    @Test
    void getChartsEmitsTimeseriesPointsSortedByDateSkippingNonDates(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "out-ts");
        // Metric LABELS are dates (deliberately out of order); a non-date label must be skipped.
        p.setDefinition(new CpsFlowDefinition(
                "interactiveOutput(reportName: 'Daily cost', chartType: 'timeseries', metrics: [\n"
                        + "  [label: '2026-07-03', value: '30'],\n"
                        + "  [label: '2026-07-01', value: '10'],\n"
                        + "  [label: '2026-07-02', value: '20'],\n"
                        + "  [label: 'Total', value: '60']\n"
                        + "])",
                true));
        j.assertBuildStatusSuccess(p.scheduleBuild2(0).get());

        InteractiveOutputJobAction job = new InteractiveOutputJobAction(p);
        InteractiveOutputJobAction.Chart ts = chart(job.getCharts(), "Daily cost");
        assertEquals(MetricReport.CHART_TIMESERIES, ts.getType());

        JSONObject m = JSONObject.fromObject(ts.getJson());
        assertEquals("timeseries", m.getString("type"));
        JSONArray points = m.getJSONArray("points");
        assertEquals(3, points.size(), "the non-date 'Total' label is skipped");

        // Sorted ascending by date regardless of input order, each carrying label + value + epoch-ms 't'.
        assertEquals("2026-07-01", points.getJSONObject(0).getString("label"));
        assertEquals("2026-07-02", points.getJSONObject(1).getString("label"));
        assertEquals("2026-07-03", points.getJSONObject(2).getString("label"));
        assertEquals(10.0, points.getJSONObject(0).getDouble("value"), 0.0001);
        assertTrue(
                points.getJSONObject(0).getLong("t") < points.getJSONObject(1).getLong("t"),
                "points carry an epoch-ms 't' used for chronological ordering");
    }

    private static InteractiveOutputJobAction.Chart chart(List<InteractiveOutputJobAction.Chart> charts, String name) {
        return charts.stream()
                .filter(c -> name.equals(c.getReportName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no chart for report " + name));
    }

    private static WorkflowRun start(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p.scheduleBuild2(0).waitForStart();
    }
}
