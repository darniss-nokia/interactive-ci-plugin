// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.step;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import java.io.Serializable;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.verb.POST;

/**
 * The {@code interactiveView} pipeline step: snapshot a generated file into the plugin's durable store
 * and publish a Confluence-style review page where humans can comment (generally or inline, per source
 * line), edit a durable review copy (with version history) and record an
 * approve / reject / acknowledge / request-changes decision.
 *
 * <pre>{@code
 * // non-blocking: publish for review and carry on (returns the review id)
 * interactiveView(file: 'reports/summary.md', reportName: 'Build Summary', commentable: true)
 *
 * // blocking review gate: pause until a human decides, then branch on the outcome
 * def r = interactiveView(file: 'reports/plan.md', wait: true, slaMinutes: 30)
 * if (r.status == 'APPROVED') { echo "approved by ${r.decidedBy}" }
 *
 * // regenerate loop: feed the inline comments back to a generator on "Request changes"
 * if (r.status == 'CHANGES_REQUESTED') {
 *     r.comments.each { c -> echo "line ${c.line}: ${c.body}" }  // pass to your AI agent
 * }
 *
 * // folders / dynamically generated files: publish every match as one grouped report
 * interactiveView(includes: 'reports/**' + '/*.md', reportName: 'Generated reports')
 * interactiveView(dir: 'out/site', mode: 'info', notify: false)  // read-only viewer, no approval
 * }</pre>
 *
 * <p>The source is read via the build's {@link FilePath} workspace and copied into the store, so the
 * review still works after the workspace/build is cleaned. Provide exactly one of: a single
 * {@code file}; an Ant-style {@code includes} glob (optionally with {@code excludes}); or a {@code dir}
 * (sugar for {@code dir/**}). A glob/dir publishes one review per matched file, grouped under a shared
 * {@code reportName}. Markdown is rendered to safe HTML; every other format (HTML included, and any
 * programming language) is shown as escaped, syntax-highlighted <em>source</em> — never executed.
 *
 * <p>{@code mode} is {@code 'review'} (default — a decision is required) or {@code 'info'} (a read-only
 * viewer, still commentable, with no approve/reject buttons). {@code wait:true} requires a single file
 * in {@code 'review'} mode.
 *
 * <p>Return value: a single-file, non-blocking call returns the review id ({@code String}); a
 * glob/dir call returns a {@code Map} {@code [groupId, ids, count]}; {@code wait:true} returns a
 * {@code Map} {@code [id, status, decidedBy, version, content, comments]} on decision (where
 * {@code comments} is a list of {@code [id, line, body, author, createdTs, resolved]}; {@code line} is
 * the 1-based source line for inline comments or {@code -1} for general notes), or throws a
 * {@code TimeoutException} on SLA expiry (like {@code askInteractive}).
 */
public class InteractiveViewStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    @CheckForNull
    private String file;

    @CheckForNull
    private String includes;

    @CheckForNull
    private String excludes;

    @CheckForNull
    private String dir;

    @CheckForNull
    private String mode;

    @CheckForNull
    private String title;

    @CheckForNull
    private String reportName;

    private boolean commentable = true;

    private boolean editable = false;

    private boolean notify = true;

    private boolean wait = false;

    @CheckForNull
    private String format;

    private int slaMinutes = -1; // -1 => fall back to the JCasC default SLA (only used when wait:true)

    @CheckForNull
    private String submitterFilter;

    @DataBoundConstructor
    public InteractiveViewStep() {
        // All inputs are optional @DataBoundSetter properties. The source (a single 'file' XOR an
        // 'includes'/'dir' glob) and any invalid combination are validated at execution time so the
        // pipeline gets a clear AbortException rather than a construction-time failure.
    }

    @CheckForNull
    public String getFile() {
        return file;
    }

    @DataBoundSetter
    public void setFile(@CheckForNull String file) {
        this.file = file;
    }

    @CheckForNull
    public String getIncludes() {
        return includes;
    }

    @DataBoundSetter
    public void setIncludes(@CheckForNull String includes) {
        this.includes = includes;
    }

    @CheckForNull
    public String getExcludes() {
        return excludes;
    }

    @DataBoundSetter
    public void setExcludes(@CheckForNull String excludes) {
        this.excludes = excludes;
    }

    @CheckForNull
    public String getDir() {
        return dir;
    }

    @DataBoundSetter
    public void setDir(@CheckForNull String dir) {
        this.dir = dir;
    }

    /** @return the review mode as configured ({@code review}/{@code info}); {@code null} means default. */
    @CheckForNull
    public String getMode() {
        return mode;
    }

    @DataBoundSetter
    public void setMode(@CheckForNull String mode) {
        this.mode = mode;
    }

    @CheckForNull
    public String getTitle() {
        return title;
    }

    @DataBoundSetter
    public void setTitle(@CheckForNull String title) {
        this.title = title;
    }

    @CheckForNull
    public String getReportName() {
        return reportName;
    }

    @DataBoundSetter
    public void setReportName(@CheckForNull String reportName) {
        this.reportName = reportName;
    }

    public boolean isCommentable() {
        return commentable;
    }

    @DataBoundSetter
    public void setCommentable(boolean commentable) {
        this.commentable = commentable;
    }

    public boolean isEditable() {
        return editable;
    }

    @DataBoundSetter
    public void setEditable(boolean editable) {
        this.editable = editable;
    }

    public boolean isNotify() {
        return notify;
    }

    @DataBoundSetter
    public void setNotify(boolean notify) {
        this.notify = notify;
    }

    public boolean isWait() {
        return wait;
    }

    @DataBoundSetter
    public void setWait(boolean wait) {
        this.wait = wait;
    }

    @CheckForNull
    public String getFormat() {
        return format;
    }

    @DataBoundSetter
    public void setFormat(@CheckForNull String format) {
        this.format = format;
    }

    public int getSlaMinutes() {
        return slaMinutes;
    }

    @DataBoundSetter
    public void setSlaMinutes(int slaMinutes) {
        this.slaMinutes = slaMinutes;
    }

    @CheckForNull
    public String getSubmitterFilter() {
        return submitterFilter;
    }

    @DataBoundSetter
    public void setSubmitterFilter(@CheckForNull String submitterFilter) {
        this.submitterFilter = submitterFilter;
    }

    /** @return the effective SLA in milliseconds for a blocking review, resolving the JCasC default. */
    public long resolveSlaMs() {
        int minutes = slaMinutes >= 0 ? slaMinutes : InteractiveInputGlobalConfig.defaultSlaMinutesOrDefault();
        return minutes > 0 ? (long) minutes * 60_000L : 0L;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new InteractiveViewStepExecution(context, this);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "interactiveView";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Publish a file for interactive review";
        }

        /**
         * Populates the Mode dropdown in the Snippet Generator form. {@code @POST} for the Security
         * Scan CSRF check; {@link Jenkins#READ} is the Snippet Generator's own gate.
         */
        @POST
        @NonNull
        public ListBoxModel doFillModeItems() {
            Jenkins.get().checkPermission(Jenkins.READ);
            ListBoxModel m = new ListBoxModel();
            m.add("Review (approve / reject / acknowledge / request changes)", ReviewDocument.MODE_REVIEW);
            m.add("Info (read-only viewer, no decision)", ReviewDocument.MODE_INFO);
            return m;
        }

        /**
         * Requires a {@link FilePath} because the step reads the named file from the build's workspace;
         * calling it outside a {@code node}/workspace context fails with the standard "missing context"
         * message.
         */
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, FlowNode.class, FilePath.class);
        }
    }
}
