// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Result;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ReviewStatus;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * End-to-end tests for the {@code interactiveView} step: it must ship a Snippet-Generator form,
 * snapshot the named workspace file into the {@link ViewStore} and continue (non-blocking default), and
 * — with {@code wait:true} — pause the pipeline until a decision is recorded, then resume returning the
 * decision (approve/reject/acknowledge/request-changes never abort the run) together with the review's
 * inline comments so a generator can regenerate on a {@code CHANGES_REQUESTED} verdict.
 */
@WithJenkins
class InteractiveViewStepTest {

    @Test
    void stepShipsConfigFormForTheSnippetGenerator(JenkinsRule j) {
        StepDescriptor d = (StepDescriptor) j.jenkins.getDescriptor(InteractiveViewStep.class);
        assertNotNull(d, "interactiveView step descriptor must be registered");
        assertEquals("interactiveView", d.getFunctionName());
        assertNotNull(d.getConfigPage(), "interactiveView must ship a config.jelly so the Snippet Generator works");
    }

    @Test
    void nonBlockingSnapshotsTheFileAndContinues(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "view-nonblocking",
                "node {\n"
                        + "  writeFile file: 'report.md', text: '# Release notes\\nLooks good.'\n"
                        + "  def id = interactiveView(file: 'report.md', reportName: 'Release notes')\n"
                        + "  echo \"VIEW=${id}\"\n"
                        + "}");

        // The step is synchronous in non-blocking mode, so the build completes on its own.
        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        List<ReviewDocument> docs = ViewStore.get().listOpenReadable();
        assertEquals(1, docs.size(), "exactly one review must have been published");
        ReviewDocument doc = docs.get(0);
        assertEquals(ReviewStatus.OPEN, doc.getStatus());
        assertEquals(ReviewDocument.FORMAT_MARKDOWN, doc.getFormat(), "a .md file must be detected as markdown");
        assertEquals("Release notes", doc.getReportName());
        assertEquals(
                "# Release notes\nLooks good.",
                ViewStore.get().readCurrentContent(doc.getId()),
                "the durable snapshot must match the workspace file content");
        j.assertLogContains("VIEW=" + doc.getId(), b);
        j.assertLogContains("published for review", b);
    }

    @Test
    void blockingWaitPausesThenResumesWithTheDecision(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "view-blocking",
                "node {\n"
                        + "  writeFile file: 'diff.txt', text: 'the change'\n"
                        + "  def d = interactiveView(file: 'diff.txt', reportName: 'Change', wait: true)\n"
                        + "  echo \"DECISION=${d.status};BY=${d.decidedBy}\"\n"
                        + "}");

        ReviewDocument doc = awaitOneReview(j);
        // The store decision path is exactly what the REST /decision endpoint and the editor call.
        ViewStore.get().decide(doc.getId(), ReviewStatus.APPROVED, "alice", "test");

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("DECISION=APPROVED;BY=alice", b);
        j.assertLogContains("APPROVED by alice", b);
        assertEquals(ReviewStatus.APPROVED, ViewStore.get().get(doc.getId()).getStatus());
    }

    @Test
    void requestChangesResumesReturningInlineCommentsForRegeneration(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "view-regenerate",
                "node {\n"
                        + "  writeFile file: 'plan.md', text: '# Plan\\n\\nStep one.'\n"
                        + "  def d = interactiveView(file: 'plan.md', reportName: 'Plan', wait: true, commentable: true)\n"
                        + "  echo \"DECISION=${d.status};COMMENTS=${d.comments.size()}\"\n"
                        + "  echo \"C0=line:${d.comments[0].line}|body:${d.comments[0].body}\"\n"
                        + "}");

        ReviewDocument doc = awaitOneReview(j);
        // A reviewer leaves an inline comment on line 3 ("Step one."), then clicks "Request changes".
        ViewStore.get().addComment(doc.getId(), 3, "expand this step", "carol");
        ViewStore.get().decide(doc.getId(), ReviewStatus.CHANGES_REQUESTED, "carol", "test");

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        // The step resolves (does not abort) and hands the comments back so a generator can course-correct.
        j.assertLogContains("DECISION=CHANGES_REQUESTED;COMMENTS=1", b);
        j.assertLogContains("C0=line:3|body:expand this step", b);
        assertEquals(
                ReviewStatus.CHANGES_REQUESTED, ViewStore.get().get(doc.getId()).getStatus());
    }

    @Test
    void globPublishesOneReviewPerFileSharingGroupAndReport(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "view-glob",
                "node {\n"
                        + "  writeFile file: 'reports/a.md', text: '# A'\n"
                        + "  writeFile file: 'reports/sub/b.md', text: '# B'\n"
                        + "  writeFile file: 'reports/ignore.txt', text: 'nope'\n"
                        + "  def r = interactiveView(includes: 'reports/**/*.md', reportName: 'Docs')\n"
                        + "  echo \"COUNT=${r.count};IDS=${r.ids.size()}\"\n"
                        + "}");
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        // A glob returns a map (groupId + ids + count), not a bare id.
        j.assertLogContains("COUNT=2;IDS=2", b);

        List<ReviewDocument> docs = ViewStore.get().listOpenReadable();
        assertEquals(2, docs.size(), "only the two .md files are published (the .txt is not matched)");
        String group = docs.get(0).getGroupId();
        assertNotNull(group);
        assertEquals(group, docs.get(1).getGroupId(), "files from one glob share a groupId");
        for (ReviewDocument d : docs) {
            assertEquals("Docs", d.getReportName(), "grouped files share the report name");
            assertEquals(ReviewDocument.MODE_REVIEW, d.getMode(), "default mode is review");
        }
        // The per-file title is its workspace-relative path, so the listing can show the folder layout.
        assertTrue(docs.stream().anyMatch(d -> "reports/a.md".equals(d.getTitle())), "a.md title is its rel path");
        assertTrue(docs.stream().anyMatch(d -> "reports/sub/b.md".equals(d.getTitle())), "b.md title is its rel path");
    }

    @Test
    void singleFileIsItsOwnSingletonGroupInReviewMode(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "view-single",
                "node {\n"
                        + "  writeFile file: 'r.md', text: '# R'\n"
                        + "  def id = interactiveView(file: 'r.md', reportName: 'R')\n"
                        + "  echo \"ID=${id}\"\n"
                        + "}");
        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        List<ReviewDocument> docs = ViewStore.get().listOpenReadable();
        assertEquals(1, docs.size());
        ReviewDocument d = docs.get(0);
        j.assertLogContains("ID=" + d.getId(), b); // back-compat: a lone file returns a bare String id
        assertEquals(d.getId(), d.getGroupId(), "a single file is its own singleton group (groupId falls back to id)");
        assertEquals(ReviewDocument.MODE_REVIEW, d.getMode());
    }

    @Test
    void infoModeMarksTheReviewInformational(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "view-info",
                "node {\n"
                        + "  writeFile file: 'note.md', text: '# Note'\n"
                        + "  interactiveView(file: 'note.md', reportName: 'Note', mode: 'info')\n"
                        + "}");
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        List<ReviewDocument> docs = ViewStore.get().listOpenReadable();
        assertEquals(1, docs.size());
        assertEquals(ReviewDocument.MODE_INFO, docs.get(0).getMode(), "mode:'info' is stored as an informational view");
    }

    @Test
    void blockingWaitRejectsAGlobMatchingMultipleFiles(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "view-wait-multi",
                "node {\n"
                        + "  writeFile file: 'a.md', text: '# A'\n"
                        + "  writeFile file: 'b.md', text: '# B'\n"
                        + "  interactiveView(includes: '*.md', wait: true)\n"
                        + "}");
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains("wait:true supports a single file", b);
    }

    @Test
    void tooManyMatchedFilesIsRejectedByTheCap(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "view-toomany",
                "node {\n"
                        + "  for (int i = 0; i < 51; i++) { writeFile file: \"gen/f${i}.txt\", text: 'x' }\n"
                        + "  interactiveView(includes: 'gen/*.txt', reportName: 'Gen')\n"
                        + "}");
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains("too many files matched", b);
    }

    // ---- helpers ----

    private static WorkflowRun start(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p.scheduleBuild2(0).waitForStart();
    }

    private static ReviewDocument awaitOneReview(JenkinsRule j) throws InterruptedException {
        ViewStore store = ViewStore.get();
        for (int i = 0; i < 100; i++) {
            List<ReviewDocument> docs = store.listOpenReadable();
            if (docs.size() == 1) {
                return docs.get(0);
            }
            Thread.sleep(100L);
        }
        fail("no OPEN review appeared in the store");
        throw new AssertionError("unreachable");
    }
}
