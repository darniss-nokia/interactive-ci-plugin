// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Edit-lifecycle invariants for the durable review copy. The regenerate loop (Ask #2) course-corrects a
 * review after a reviewer clicks "Request changes", so {@link ReviewStatus#allowsEdit()} keeps editing
 * open while {@link ReviewStatus#CHANGES_REQUESTED} — but every other terminal decision is read-only.
 */
@WithJenkins
class ReviewEditLifecycleTest {

    @Test
    void allowsEditPredicateMatchesTheStateMachine() {
        assertTrue(ReviewStatus.OPEN.allowsEdit());
        assertTrue(ReviewStatus.CHANGES_REQUESTED.allowsEdit(), "the regenerate loop edits in place");
        assertFalse(ReviewStatus.APPROVED.allowsEdit());
        assertFalse(ReviewStatus.REJECTED.allowsEdit());
        assertFalse(ReviewStatus.ACKNOWLEDGED.allowsEdit());
        assertFalse(ReviewStatus.EXPIRED.allowsEdit());
        assertFalse(ReviewStatus.ABORTED.allowsEdit());
    }

    @Test
    void editIsAllowedWhileOpenAndAfterRequestChangesButNotAfterApproval(JenkinsRule j) {
        ViewStore store = ViewStore.get();
        store.submit(review("ed"), "v1 content");

        // OPEN: a normal edit versions the copy.
        assertEquals(2, store.saveEdit("ed", "v2 content", "alice", "manual tweak"));

        // Reviewer requests changes -> CHANGES_REQUESTED (a decision, but editing stays open).
        store.decide("ed", ReviewStatus.CHANGES_REQUESTED, "carol", "test");
        assertEquals(ReviewStatus.CHANGES_REQUESTED, store.get("ed").getStatus());

        // The regenerate-agent course-corrects the SAME review and records its summary note.
        assertEquals(3, store.saveEdit("ed", "v3 regenerated", "ai-bot", "Regenerated per review: added Notes column"));
        List<ContentVersion> versions = store.get("ed").getVersions();
        assertEquals(
                "Regenerated per review: added Notes column",
                versions.get(versions.size() - 1).getNote(),
                "the AI course-correction note is recorded in version history");

        // A finally-decided review stays read-only.
        store.submit(review("ap"), "orig");
        store.decide("ap", ReviewStatus.APPROVED, "carol", "test");
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> store.saveEdit("ap", "nope", "ai-bot", "x"));
        assertTrue(ex.getMessage().contains("already APPROVED"), ex.getMessage());
    }

    private static ReviewDocument review(String id) {
        return new ReviewDocument(
                id,
                "job",
                1,
                "Report",
                "Title",
                "file",
                ReviewDocument.FORMAT_TEXT,
                "text",
                "alice",
                System.currentTimeMillis(),
                true, // commentable
                true, // editable
                false, // notify
                false, // blocking
                null, // submitterFilter
                0L, // slaMs
                null, // groupId
                null); // mode
    }
}
