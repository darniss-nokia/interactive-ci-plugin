// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.ui.InteractiveViewJobAction;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.util.List;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Deleting a build must clear its interactive-input notifications and decrease the count, and the per-job
 * interactive-view page must mark the build as deleted (evidence-driven fix for the "stale notification
 * after build deletion" bug). Verifies {@link BuildLifecycleCleanup}'s two hooks:
 *
 * <ul>
 *   <li>on deletion — questions are purged (their audit lived on the now-gone build) and reviews are
 *       marked build-deleted (kept as durable records but no longer notified);</li>
 *   <li>on startup — {@code reconcileDeletedBuilds()} self-heals entries whose build was deleted before
 *       the cleanup existed, while leaving entries whose build still exists untouched.</li>
 * </ul>
 *
 * <p>Builds are created for real ({@link JenkinsRule#buildAndAssertSuccess}) so {@code getBuildByNumber}
 * resolves them; deleting the build then drives the real {@code RunListener}.
 */
@WithJenkins
class BuildDeletionCleanupTest {

    @Test
    void deletingABuildPurgesItsQuestionsAndClearsTheCount(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("q-del");
        FreeStyleBuild b = j.buildAndAssertSuccess(p); // build #1 exists

        QuestionStore store = QuestionStore.get();
        store.submit(question("qd", p.getFullName(), b.getNumber()));
        assertEquals(1, store.countNotifications(), "the WAITING question notifies while its build exists");
        assertTrue(store.hasNotificationForBuild(p.getFullName(), b.getNumber()));

        b.delete(); // fires RunListener.onDeleted

        assertNull(store.get("qd"), "the question is purged when its build is deleted");
        assertEquals(0, store.countNotifications(), "the global notification count drops to zero");
        assertEquals(0, store.countNotificationsForJob(p.getFullName()), "the per-job count drops to zero");
        assertFalse(store.hasNotificationForBuild(p.getFullName(), 1), "the build-history badge clears");
    }

    @Test
    void deletingABuildMarksItsReviewsBuildDeletedButKeepsThem(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("v-del");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);

        ViewStore store = ViewStore.get();
        store.submit(review("vd", p.getFullName(), b.getNumber()), "content");
        assertEquals(1, store.listNotifications().size(), "the OPEN review notifies while its build exists");
        assertTrue(store.hasNotificationForBuild(p.getFullName(), b.getNumber()));

        b.delete();

        ReviewDocument doc = store.get("vd");
        assertNotNull(doc, "the review is KEPT as a durable record after its build is deleted");
        assertTrue(doc.isBuildDeleted(), "but it is flagged build-deleted");
        assertEquals(0, store.listNotifications().size(), "and no longer appears in the notification centre");
        assertEquals(0, store.countNotificationsForJob(p.getFullName()), "the per-job count drops to zero");
        assertFalse(store.hasNotificationForBuild(p.getFullName(), 1), "the build-history badge clears");

        // The per-job page keeps listing it but reports the build as deleted.
        InteractiveViewJobAction action = new InteractiveViewJobAction(p);
        assertTrue(action.isBuildDeleted(doc), "the per-job page reports the build as deleted");
        assertFalse(action.getReviews().isEmpty(), "the review still appears on the per-job page");
    }

    @Test
    void perJobPageMarksDeletedBuildsAndDropsTheDeadLink(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("view-page-del");
        FreeStyleBuild b = j.buildAndAssertSuccess(p); // #1
        ViewStore.get().submit(review("vp", p.getFullName(), b.getNumber()), "content");

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(false); // server-rendered assertion only
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

            // While the build exists: the row links to the per-build review editor; nothing is "deleted".
            HtmlPage live = wc.goTo(p.getUrl() + "interactive-view/");
            String liveHtml = live.getWebResponse().getContentAsString();
            assertFalse(liveHtml.contains("build deleted"), "a live build is not marked deleted");
            assertNotNull(
                    live.querySelector("a[href$='/interactive-view/?doc=vp']"),
                    "a live review links to its per-build editor");

            // Delete the build: the review is kept, the page marks it, and the now-dead link is dropped.
            b.delete();
            HtmlPage deleted = wc.goTo(p.getUrl() + "interactive-view/");
            String delHtml = deleted.getWebResponse().getContentAsString();
            assertTrue(delHtml.contains("build deleted"), "the page must mention the build is deleted");
            assertNotNull(deleted.querySelector(".iv-flag-deleted"), "the build-deleted marker renders");
            assertNull(
                    deleted.querySelector("a[href$='/interactive-view/?doc=vp']"),
                    "the dead per-build editor link is dropped once the build is gone");
        }
    }

    @Test
    void startupReconcileClearsOrphansFromBuildsDeletedEarlier(JenkinsRule j) throws Exception {
        // Simulate pre-existing orphans (deleted before this cleanup shipped): the job exists but the
        // referenced build never does. reconcileDeletedBuilds() is exactly what onLoaded runs at startup.
        FreeStyleProject p = j.createFreeStyleProject("recon");
        QuestionStore qs = QuestionStore.get();
        ViewStore vs = ViewStore.get();
        qs.submit(question("qo", p.getFullName(), 7)); // build #7 was never created
        vs.submit(review("vo", p.getFullName(), 7), "content");

        assertEquals(1, qs.reconcileDeletedBuilds(), "the orphaned question is reconciled");
        assertEquals(1, vs.reconcileDeletedBuilds(), "the orphaned review is reconciled");
        assertNull(qs.get("qo"), "the orphaned question is purged");
        assertTrue(vs.get("vo").isBuildDeleted(), "the orphaned review is marked build-deleted (kept)");
    }

    @Test
    void reconcileLeavesEntriesWhoseBuildStillExists(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("keep");
        FreeStyleBuild b = j.buildAndAssertSuccess(p); // build #1 exists

        QuestionStore qs = QuestionStore.get();
        ViewStore vs = ViewStore.get();
        qs.submit(question("qk", p.getFullName(), b.getNumber()));
        vs.submit(review("vk", p.getFullName(), b.getNumber()), "content");

        assertEquals(0, qs.reconcileDeletedBuilds(), "a live build's question is untouched");
        assertEquals(0, vs.reconcileDeletedBuilds(), "a live build's review is untouched");
        assertNotNull(qs.get("qk"), "the question survives");
        assertFalse(vs.get("vk").isBuildDeleted(), "the review is not marked deleted");
    }

    // ---- helpers ----

    private static Question question(String id, String job, int build) {
        return new Question(
                id,
                "prompt",
                List.of(new Choice("a", "A")),
                false,
                0L,
                null,
                null,
                job,
                build,
                null,
                System.currentTimeMillis(),
                false);
    }

    private static ReviewDocument review(String id, String job, int build) {
        return new ReviewDocument(
                id,
                job,
                build,
                "Report",
                "Title",
                "file",
                ReviewDocument.FORMAT_TEXT,
                "text",
                "alice",
                System.currentTimeMillis(),
                true,
                false,
                true,
                false,
                null,
                0L,
                null,
                null);
    }
}
