// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.step;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Util;
import hudson.console.HyperlinkNote;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.interactiveinput.ui.InteractiveViewRunAction;
import io.jenkins.plugins.interactiveinput.util.CauseResolver;
import io.jenkins.plugins.interactiveinput.view.FormatDetector;
import io.jenkins.plugins.interactiveinput.view.ReviewComment;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ReviewStatus;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;

/**
 * Durable, restart-safe execution for {@link InteractiveViewStep}.
 *
 * <p>Non-blocking (default): the file is snapshotted into the {@link ViewStore}, an anchored console
 * link to the review page is logged, and the step completes synchronously returning the review id.
 *
 * <p>{@code wait:true}: mirrors {@link AskInteractiveStepExecution} exactly — {@link #start()} returns
 * {@code false} to release the CPS thread and pause the pipeline; the store resumes it via
 * {@link StepContext#onSuccess}/{@link StepContext#onFailure} once a human records a decision (or the
 * SLA elapses). The decision is <em>returned</em> to the pipeline (approve/reject/acknowledge/
 * request-changes do not abort the run), so the pipeline can branch on the review outcome. The returned
 * map also carries the review's {@code comments} (inline, with their source line, plus general), so a
 * generator can course-correct on a {@code CHANGES_REQUESTED} verdict (the regenerate loop).
 */
public class InteractiveViewStepExecution extends AbstractStepExecutionImpl {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(InteractiveViewStepExecution.class.getName());

    private static final String LOG_PREFIX = "[interactive-input] ";

    /** Bound the snapshot so a huge file cannot exhaust controller memory or the pipeline program state. */
    static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;

    /** Cap the number of files a single glob/dir call may publish, to keep the store and page bounded. */
    static final int MAX_FILES = 50;

    /** Aggregate byte cap across all files matched by one glob/dir call (4× the single-file cap). */
    static final long MAX_TOTAL_SNAPSHOT_BYTES = 4L * MAX_SNAPSHOT_BYTES;

    private final InteractiveViewStep step;

    /** Assigned in {@link #start()}; persisted so a blocking review can re-attach after a restart. */
    private String viewId;

    InteractiveViewStepExecution(StepContext context, InteractiveViewStep step) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        StepContext ctx = getContext();
        Run<?, ?> run = ctx.get(Run.class);
        TaskListener listener = ctx.get(TaskListener.class);
        FilePath workspace = ctx.get(FilePath.class);
        if (run == null || workspace == null) {
            throw new AbortException("interactiveView requires a workspace; run it inside a node { } block");
        }

        boolean grouped = isGlobSource();
        String modeParam = step.getMode();
        boolean infoMode = ReviewDocument.MODE_INFO.equalsIgnoreCase(modeParam == null ? "" : modeParam.trim());
        if (step.isWait() && infoMode) {
            throw new AbortException("interactiveView: wait:true cannot be combined with mode:'info' "
                    + "(an informational view has no decision to wait for)");
        }

        List<String> targets = resolveTargets(workspace);

        // Blocking review: exactly one file, resumed on decision (mirrors askInteractive).
        if (step.isWait()) {
            if (targets.size() != 1) {
                throw new AbortException("interactiveView: wait:true supports a single file, but "
                        + targets.size() + " matched (" + describeSource() + "); use 'file' or a glob that "
                        + "resolves to one file for a blocking review");
            }
            String rel = targets.get(0);
            ReviewDocument doc = publishOne(run, rel, readSnapshot(workspace, rel), null, null, infoMode);
            this.viewId = doc.getId();
            logPublishedSingle(listener, run, doc, true);
            ViewStore.get().register(viewId, this::onResolved);
            markPaused(ctx);
            return false; // asynchronous: the pipeline pauses until a decision or SLA expiry
        }

        // Non-blocking: publish one review per matched file. A glob/dir shares a groupId + reportName.
        String groupId = grouped ? UUID.randomUUID().toString() : null;
        String groupReport = grouped ? resolveGroupReportName() : null;
        List<String> ids = new ArrayList<>();
        long aggregate = 0L;
        for (String rel : targets) {
            String content = readSnapshot(workspace, rel);
            aggregate += content.getBytes(StandardCharsets.UTF_8).length;
            if (aggregate > MAX_TOTAL_SNAPSHOT_BYTES) {
                throw new AbortException("interactiveView: matched files exceed the aggregate snapshot cap ("
                        + MAX_TOTAL_SNAPSHOT_BYTES + " bytes); narrow the includes/dir pattern");
            }
            ReviewDocument doc = publishOne(run, rel, content, groupId, groupReport, infoMode);
            ids.add(doc.getId());
        }
        this.viewId = ids.get(0);

        if (grouped) {
            logPublishedGroup(listener, run, ids.size(), groupReport);
            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("groupId", groupId);
            ret.put("ids", ids);
            ret.put("count", ids.size());
            ctx.onSuccess(ret);
        } else {
            logPublishedSingle(listener, run, ViewStore.get().get(ids.get(0)), false);
            ctx.onSuccess(ids.get(0)); // single file → back-compat String id
        }
        return true;
    }

    /** @return {@code true} if the step selects files via a glob/dir rather than a single {@code file}. */
    private boolean isGlobSource() {
        return trimToNull(step.getIncludes()) != null || trimToNull(step.getDir()) != null;
    }

    /**
     * Resolve the relative paths to publish. A single {@code file} returns just that path (validated by
     * {@link #readSnapshot}); a glob/dir enumerates the workspace via {@link FilePath#list(String,String)}
     * with deterministic ordering and the file-count cap. Throws a clear {@link AbortException} for an
     * ambiguous/empty source.
     */
    private List<String> resolveTargets(FilePath workspace) throws IOException, InterruptedException {
        String file = trimToNull(step.getFile());
        String includes = trimToNull(step.getIncludes());
        String dir = trimToNull(step.getDir());
        boolean glob = includes != null || dir != null;
        if (file != null && glob) {
            throw new AbortException("interactiveView: specify either 'file' or 'includes'/'dir', not both");
        }
        if (file == null && !glob) {
            throw new AbortException("interactiveView: one of 'file', 'includes' or 'dir' is required");
        }
        if (file != null) {
            return Collections.singletonList(file);
        }
        String effectiveIncludes = effectiveIncludes(includes, dir);
        String excludes = trimToNull(step.getExcludes());
        FilePath[] matches = workspace.list(effectiveIncludes, excludes == null ? "" : excludes);
        List<String> rels = new ArrayList<>();
        for (FilePath m : matches) {
            if (m.isDirectory()) {
                continue;
            }
            String rel = relativize(workspace, m);
            if (rel != null && !rel.isEmpty()) {
                rels.add(rel);
            }
        }
        if (rels.isEmpty()) {
            throw new AbortException("interactiveView: no files matched " + describeSource());
        }
        Collections.sort(rels); // stable, path-sorted order for a predictable listing
        if (rels.size() > MAX_FILES) {
            throw new AbortException("interactiveView: too many files matched (" + rels.size() + ", max " + MAX_FILES
                    + "); narrow the includes/dir pattern");
        }
        return rels;
    }

    /** Build + submit one review document for a single matched file; returns the stored document. */
    private ReviewDocument publishOne(
            Run<?, ?> run, String relPath, String content, String groupId, String groupReportName, boolean infoMode) {
        String reportName;
        String title;
        if (groupReportName != null) {
            // Grouped (glob/dir): every file shares the report; the file's path is its per-card title.
            reportName = groupReportName;
            title = relPath;
        } else {
            String base = baseName(relPath);
            String rawReportName = step.getReportName();
            reportName = rawReportName != null && !rawReportName.trim().isEmpty() ? rawReportName.trim() : base;
            String rawTitle = step.getTitle();
            title = rawTitle != null && !rawTitle.trim().isEmpty() ? rawTitle.trim() : reportName;
        }
        FormatDetector.Detected detected = FormatDetector.resolve(step.getFormat(), relPath);
        String id = UUID.randomUUID().toString();
        ReviewDocument doc = new ReviewDocument(
                id,
                run.getParent().getFullName(),
                run.getNumber(),
                reportName,
                title,
                relPath,
                detected.format,
                detected.language,
                CauseResolver.startedBy(run),
                System.currentTimeMillis(),
                step.isCommentable(),
                step.isEditable(),
                step.isNotify(),
                step.isWait(),
                step.getSubmitterFilter(),
                step.isWait() ? step.resolveSlaMs() : 0L,
                groupId,
                infoMode ? ReviewDocument.MODE_INFO : ReviewDocument.MODE_REVIEW);
        ViewStore.get().submit(doc, content);
        return doc;
    }

    /** @return the shared report name for a grouped publish (falls back to the dir, then a generic label). */
    private String resolveGroupReportName() {
        String rawReportName = step.getReportName();
        if (rawReportName != null && !rawReportName.trim().isEmpty()) {
            return rawReportName.trim();
        }
        String dir = trimToNull(step.getDir());
        if (dir != null) {
            return dir.replace('\\', '/').replaceAll("/+$", "");
        }
        return "Generated files";
    }

    /** Combine an optional {@code dir} with optional {@code includes} into a single Ant include pattern. */
    private static String effectiveIncludes(String includes, String dir) {
        if (dir == null) {
            return includes;
        }
        String d = dir.replace('\\', '/').replaceAll("/+$", "");
        if (includes == null) {
            return d + "/**";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : includes.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(d).append("/").append(p);
        }
        return sb.length() > 0 ? sb.toString() : d + "/**";
    }

    /** @return {@code child}'s path relative to {@code workspace}, or {@code null} if not under it. */
    private static String relativize(FilePath workspace, FilePath child) {
        String base = workspace.getRemote().replace('\\', '/');
        String c = child.getRemote().replace('\\', '/');
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return c.startsWith(base) ? c.substring(base.length()) : null;
    }

    /** @return a human-readable description of the configured source, for error messages. */
    private String describeSource() {
        String file = trimToNull(step.getFile());
        String includes = trimToNull(step.getIncludes());
        String dir = trimToNull(step.getDir());
        StringBuilder sb = new StringBuilder();
        if (file != null) {
            sb.append("file='").append(file).append("'");
        }
        if (dir != null) {
            sb.append(sb.length() > 0 ? ", " : "").append("dir='").append(dir).append("'");
        }
        if (includes != null) {
            sb.append(sb.length() > 0 ? ", " : "")
                    .append("includes='")
                    .append(includes)
                    .append("'");
        }
        return sb.length() > 0 ? sb.toString() : "(no source)";
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Read + snapshot the workspace file as UTF-8 text, bounded by {@link #MAX_SNAPSHOT_BYTES}. */
    private String readSnapshot(FilePath workspace, String relPath) throws IOException, InterruptedException {
        FilePath target = workspace.child(relPath);
        if (!target.exists() || target.isDirectory()) {
            throw new AbortException("interactiveView: file not found in workspace: " + relPath);
        }
        long len = target.length();
        if (len > MAX_SNAPSHOT_BYTES) {
            throw new AbortException("interactiveView: file too large to review (" + len + " bytes, max "
                    + MAX_SNAPSHOT_BYTES + "): " + relPath);
        }
        try (InputStream is = target.read()) {
            byte[] bytes = is.readNBytes(MAX_SNAPSHOT_BYTES + 1);
            if (bytes.length > MAX_SNAPSHOT_BYTES) {
                throw new AbortException(
                        "interactiveView: file too large to review (max " + MAX_SNAPSHOT_BYTES + " bytes): " + relPath);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Log a deep-link to a single review page (blocking or not). */
    private void logPublishedSingle(TaskListener listener, Run<?, ?> run, ReviewDocument doc, boolean waiting) {
        if (listener == null || doc == null) {
            return;
        }
        // Deep-link straight to the review page (not a dialog): the reviewer opens a full editor page.
        // The href is a real context-path-relative link resolved by HyperlinkNote and carries no
        // user-supplied text (the id is a server UUID), so there is no injection surface.
        String target = "/" + run.getUrl() + InteractiveViewRunAction.URL_NAME + "/?doc=" + Util.rawEncode(doc.getId());
        String link = HyperlinkNote.encodeTo(target, "Open interactive view");
        String verb = waiting ? " — waiting for a review decision. " : " published for review. ";
        listener.getLogger().println(LOG_PREFIX + "\"" + doc.getReportName() + "\"" + verb + link);
    }

    /** Log a summary + link to the build's review list for a grouped (multi-file) publish. */
    private void logPublishedGroup(TaskListener listener, Run<?, ?> run, int count, String reportName) {
        if (listener == null) {
            return;
        }
        String target = "/" + run.getUrl() + InteractiveViewRunAction.URL_NAME + "/";
        String link = HyperlinkNote.encodeTo(target, "Open interactive view");
        listener.getLogger()
                .println(LOG_PREFIX + count + " file(s) published for review under \"" + reportName + "\". " + link);
    }

    /** Invoked by the store exactly once when a blocking review reaches a decided/expired state. */
    private void onResolved(ReviewDocument doc) {
        StepContext ctx = getContext();
        endPause(ctx);
        logOutcome(ctx, doc);
        ReviewStatus status = doc.getStatus();
        switch (status) {
            case APPROVED:
            case REJECTED:
            case ACKNOWLEDGED:
            case CHANGES_REQUESTED:
                Map<String, Object> ret = new LinkedHashMap<>();
                ret.put("id", doc.getId());
                ret.put("status", status.name());
                ret.put("decidedBy", doc.getDecidedBy() == null ? "" : doc.getDecidedBy());
                ret.put("version", doc.getCurrentVersion());
                String c = ViewStore.get().readCurrentContent(doc.getId());
                ret.put("content", c == null ? "" : c);
                ret.put("comments", commentsFor(doc));
                ctx.onSuccess(ret);
                break;
            case EXPIRED:
                ctx.onFailure(new TimeoutException("interactiveView SLA elapsed with no decision after "
                        + (step.resolveSlaMs() / 60000L) + " min"));
                break;
            default:
                LOGGER.warning("onResolved called for non-terminal review " + doc.getId() + " (" + status + ")");
                break;
        }
    }

    /**
     * The review's comments as plain, CPS-serialisable maps so a pipeline can feed them back to a
     * generator (e.g. an AI agent) for course correction. Inline comments carry {@code line} (1-based
     * source line); general notes carry {@code line == -1}.
     */
    private static List<Map<String, Object>> commentsFor(ReviewDocument doc) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReviewComment c : doc.getComments()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("line", c.getLine());
            m.put("body", c.getBody());
            m.put("author", c.getAuthor());
            m.put("createdTs", c.getCreatedTs());
            m.put("resolved", c.isResolved());
            out.add(m);
        }
        return out;
    }

    private void markPaused(StepContext ctx) {
        try {
            FlowNode node = ctx.get(FlowNode.class);
            if (node != null) {
                node.addAction(new PauseAction("Interactive view"));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.FINE, e, () -> "could not mark flow node paused for review " + viewId);
        }
    }

    private void endPause(StepContext ctx) {
        try {
            FlowNode node = ctx.get(FlowNode.class);
            if (node != null && PauseAction.isPaused(node)) {
                PauseAction.endCurrentPause(node);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.FINE, e, () -> "could not end pause for review " + viewId);
        }
    }

    private void logOutcome(StepContext ctx, ReviewDocument doc) {
        try {
            TaskListener l = ctx.get(TaskListener.class);
            if (l != null) {
                String decidedBy = doc.getDecidedBy();
                String by = decidedBy == null || decidedBy.isEmpty() ? "unknown" : decidedBy;
                String line = doc.getStatus() == ReviewStatus.EXPIRED
                        ? "Review \"" + doc.getReportName() + "\" expired: SLA elapsed with no decision"
                        : "Review \"" + doc.getReportName() + "\" " + doc.getStatus() + " by " + by;
                l.getLogger().println(LOG_PREFIX + line);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.FINE, e, () -> "could not log outcome for review " + viewId);
        }
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        // The framework delivers the cause via super.stop(); mark the review abandoned for bell cleanup
        // without double-resolving the context (mirrors AskInteractiveStepExecution#stop).
        if (step.isWait() && viewId != null) {
            ViewStore store = ViewStore.get();
            store.unregister(viewId);
            store.abandonForShutdown(viewId);
        }
        super.stop(cause);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (step.isWait() && viewId != null) {
            // Re-attach after a Jenkins restart; if already decided while we were away, register()
            // resolves immediately.
            ViewStore.get().register(viewId, this::onResolved);
        }
    }

    public String getViewId() {
        return viewId;
    }

    private static String baseName(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String base = slash >= 0 ? p.substring(slash + 1) : p;
        return base.isEmpty() ? path : base;
    }
}
