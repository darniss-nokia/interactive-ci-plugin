// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.step;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.interactiveinput.output.Metric;
import io.jenkins.plugins.interactiveinput.output.MetricReport;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.verb.POST;

/**
 * The {@code interactiveOutput} pipeline step: publish per-build statistics (cost, carbon footprint,
 * resource usage, &#8230;) as KPI cards + a table on the build page, and feed a per-job trend chart.
 *
 * <pre>{@code
 * interactiveOutput(reportName: 'Cost report', metrics: [
 *   [label: 'Total cost',   value: '12.40', unit: 'USD', key: 'cost'],
 *   [label: 'CPU minutes',  value: '318',    unit: 'min', key: 'cpu'],
 *   [label: 'Carbon',       value: '0.42',   unit: 'kgCO2e', key: 'carbon']
 * ])
 * }</pre>
 *
 * <p>Metrics whose value is numeric and that carry a {@code key} also feed the per-job trend chart
 * (the {@code key} identifies the series across builds). The data is stored in the build's
 * {@link io.jenkins.plugins.interactiveinput.output.InteractiveOutputBuildAction} (persisted in
 * {@code build.xml}). The step is synchronous and returns the report name.
 */
public class InteractiveOutputStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    @NonNull
    private final String reportName;

    @NonNull
    private List<Metric> metrics = new ArrayList<>();

    private boolean notify = false;

    @NonNull
    private String chartType = MetricReport.CHART_LINE;

    @DataBoundConstructor
    public InteractiveOutputStep(@NonNull String reportName) {
        if (reportName == null || reportName.trim().isEmpty()) {
            throw new IllegalArgumentException("reportName must not be blank");
        }
        this.reportName = reportName;
    }

    @NonNull
    public String getReportName() {
        return reportName;
    }

    @NonNull
    public List<Metric> getMetrics() {
        return Collections.unmodifiableList(metrics);
    }

    @DataBoundSetter
    public void setMetrics(@CheckForNull List<Metric> metrics) {
        this.metrics = metrics == null ? new ArrayList<>() : new ArrayList<>(metrics);
    }

    public boolean isNotify() {
        return notify;
    }

    @DataBoundSetter
    public void setNotify(boolean notify) {
        this.notify = notify;
    }

    /** @return the per-job chart type: {@code line} (default), {@code bar}, {@code pie} or {@code timeseries}. */
    @NonNull
    public String getChartType() {
        return chartType;
    }

    @DataBoundSetter
    public void setChartType(@CheckForNull String chartType) {
        this.chartType = chartType == null || chartType.trim().isEmpty() ? MetricReport.CHART_LINE : chartType.trim();
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new InteractiveOutputStepExecution(context, this);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "interactiveOutput";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Publish interactive output statistics";
        }

        /**
         * Populates the Chart type dropdown in the Snippet Generator form. {@code @POST} for the
         * Security Scan CSRF check; {@link Jenkins#READ} is the Snippet Generator's own gate.
         */
        @POST
        @NonNull
        public ListBoxModel doFillChartTypeItems() {
            Jenkins.get().checkPermission(Jenkins.READ);
            ListBoxModel m = new ListBoxModel();
            m.add("Line (trend across builds)", MetricReport.CHART_LINE);
            m.add("Bar (trend across builds)", MetricReport.CHART_BAR);
            m.add("Pie (latest build's metrics)", MetricReport.CHART_PIE);
            m.add("Time series (latest build; metric labels are dates)", MetricReport.CHART_TIMESERIES);
            return m;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }
    }
}
