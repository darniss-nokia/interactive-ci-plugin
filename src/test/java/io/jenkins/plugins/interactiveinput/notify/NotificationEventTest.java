// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationEventTest {

    @Test
    void truncatesAndStripsNewlines() {
        String longTitle = "x".repeat(250) + "\nsecret";
        assertEquals(NotificationEvent.TITLE_MAX, NotificationEvent.truncate(longTitle).length());
        assertFalse(NotificationEvent.truncate("a\nb\rc").contains("\n"));
        assertEquals("", NotificationEvent.truncate(null));
    }

    @Test
    void questionSnapshotOmitsContext() {
        Question q = new Question(
                "qid",
                "Approve deploy?",
                List.of(new Choice("y", "Yes")),
                false,
                0L,
                "SECRET_TOKEN=abc",
                null,
                "job",
                3,
                "alice",
                System.currentTimeMillis(),
                false);
        NotificationEvent e = NotificationEvent.question(q, "http://j/job/job/3/interactive-input/?open=qid");
        assertEquals(NotificationEvent.Kind.QUESTION, e.getKind());
        assertEquals("qid", e.getId());
        assertEquals("job", e.getJobFullName());
        assertEquals(3, e.getBuildNumber());
        assertEquals("alice", e.getStartedBy());
        assertEquals("Approve deploy?", e.getTitle());
        assertTrue(e.getUrl().contains("interactive-input/?open=qid"));
        assertFalse(e.getTitle().contains("SECRET"));
        assertTrue(e.summaryLine().contains("alice"));
    }

    @Test
    void reviewSnapshotUsesTitleNotFileBody() {
        ReviewDocument doc = new ReviewDocument(
                "did",
                "job",
                1,
                "Release",
                "Release 2.4.0",
                "notes.md",
                ReviewDocument.FORMAT_MARKDOWN,
                "markdown",
                "bob",
                System.currentTimeMillis(),
                true,
                false,
                true,
                false,
                null,
                0L,
                null,
                ReviewDocument.MODE_REVIEW);
        NotificationEvent e = NotificationEvent.review(doc, "http://j/job/job/1/interactive-view/?doc=did");
        assertEquals(NotificationEvent.Kind.REVIEW, e.getKind());
        assertEquals("Release 2.4.0", e.getTitle());
        assertTrue(e.getUrl().contains("interactive-view/?doc=did"));
        assertTrue(SlackChannel.json(e).contains("interactive-view/?doc=did"));
        assertTrue(TeamsChannel.json(e).contains("Open in Jenkins"));
        assertTrue(EmailChannel.subject(e).contains("Interactive view"));
        assertTrue(EmailChannel.body(e).contains("http://j/"));
    }
}
