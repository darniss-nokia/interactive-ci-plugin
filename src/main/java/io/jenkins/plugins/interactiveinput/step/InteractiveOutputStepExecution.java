// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.step;

import hudson.console.HyperlinkNote;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.interactiveinput.output.InteractiveOutputBuildAction;
import io.jenkins.plugins.interactiveinput.output.Metric;
import io.jenkins.plugins.interactiveinput.output.MetricReport;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;

/**
 * Synchronous execution for {@link InteractiveOutputStep}: builds a {@link MetricReport} from the
 * step's metrics, attaches it to the build's {@link InteractiveOutputBuildAction} (creating the action
 * on first use), persists the build and — when {@code notify} is set — logs an anchored console link to
 * the output page. Runs off the CPS thread ({@link SynchronousNonBlockingStepExecution}) and never
 * blocks the pipeline.
 */
public class InteractiveOutputStepExecution extends SynchronousNonBlockingStepExecution<String> {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(InteractiveOutputStepExecution.class.getName());

    private static final String LOG_PREFIX = "[interactive-input] ";

    /**
     * Serialises action creation across parallel branches of the same (or different) builds so two
     * {@code interactiveOutput} calls never create two competing action instances on one build. The
     * critical section is tiny; a single global monitor is simpler and safer than locking the run.
     */
    private static final Object ACTION_LOCK = new Object();

    private final InteractiveOutputStep step;

    InteractiveOutputStepExecution(StepContext context, InteractiveOutputStep step) {
        super(context);
        this.step = step;
    }

    @Override
    protected String run() throws Exception {
        StepContext ctx = getContext();
        Run<?, ?> run = ctx.get(Run.class);
        TaskListener listener = ctx.get(TaskListener.class);

        List<Metric> metrics = new ArrayList<>(step.getMetrics());
        MetricReport report =
                new MetricReport(step.getReportName(), metrics, System.currentTimeMillis(), step.getChartType());

        synchronized (ACTION_LOCK) {
            InteractiveOutputBuildAction action = run.getAction(InteractiveOutputBuildAction.class);
            if (action == null) {
                action = new InteractiveOutputBuildAction();
                run.addAction(action);
            }
            action.addReport(report);
        }
        run.save();

        if (listener != null) {
            String summary = LOG_PREFIX + "Published output report '" + step.getReportName() + "' (" + metrics.size()
                    + (metrics.size() == 1 ? " metric)" : " metrics)");
            if (step.isNotify()) {
                // Plain deep-link to the build's output page (Item.READ-gated server-side). Unlike the
                // review link this opens no dialog — the output page is informational, not actionable.
                String target = "/" + run.getUrl() + InteractiveOutputBuildAction.URL_NAME + "/";
                summary += " " + HyperlinkNote.encodeTo(target, "View interactive output");
            }
            listener.getLogger().println(summary);
        }

        LOGGER.log(
                Level.FINE,
                () -> "interactiveOutput published '" + step.getReportName() + "' with " + metrics.size()
                        + " metric(s) on " + run.getFullDisplayName());
        return step.getReportName();
    }
}
