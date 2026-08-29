// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Pure-unit coverage of the data model: {@link Choice}, {@link Answer}, {@link Question}. */
class QuestionModelTest {

    // ---- Choice ----

    @Test
    void choiceRejectsBlankIdAndLabel() {
        assertThrows(IllegalArgumentException.class, () -> new Choice("", "label"));
        assertThrows(IllegalArgumentException.class, () -> new Choice("id", "  "));
    }

    @Test
    void choiceJsonOmitsWhyWhenAbsent() {
        Choice c = new Choice("staging", "Staging");
        JSONObject j = c.toJson();
        assertEquals("staging", j.getString("id"));
        assertEquals("Staging", j.getString("label"));
        assertFalse(j.containsKey("why"));
        c.setWhy("latest passing build");
        assertEquals("latest passing build", c.toJson().getString("why"));
    }

    // ---- Answer ----

    @Test
    void answerDenySentinelDetected() {
        Answer deny = new Answer("q1", Answer.DENY_CHOICE_ID, null, "alice", 100L);
        assertTrue(deny.isDeny());
        Answer normal = new Answer("q1", "staging", null, "alice", 100L);
        assertFalse(normal.isDeny());
    }

    @Test
    void answerStepReturnValueIsChoiceIdOrFreeTextMap() {
        Answer choice = new Answer("q1", "production", null, "alice", 100L);
        assertEquals("production", choice.toStepReturnValue());

        Answer free = new Answer("q1", null, "ship it", "bob", 100L);
        Object v = free.toStepReturnValue();
        assertTrue(v instanceof Map, "free text should return a map");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        assertEquals("ship it", m.get("text"));
        assertNull(m.get("choice"));
    }

    // ---- Question ----

    @Test
    void questionNoSlaNeverExpires() {
        Question q = waiting(0L);
        assertFalse(q.isExpired(Long.MAX_VALUE));
        assertEquals(-1L, q.remainingMs(System.currentTimeMillis()));
        assertEquals(0L, q.getExpiresAt());
    }

    @Test
    void questionWithSlaExpiresAfterDeadline() {
        long created = 1_000_000L;
        long slaMs = 60_000L;
        Question q = new Question(
                "q1",
                "prompt",
                List.of(new Choice("a", "A")),
                false,
                slaMs,
                null,
                null,
                "job",
                1,
                "tester",
                created,
                false);
        assertEquals(created + slaMs, q.getExpiresAt());
        assertFalse(q.isExpired(created + slaMs - 1));
        assertTrue(q.isExpired(created + slaMs));
        assertEquals(1L, q.remainingMs(created + slaMs - 1));
        assertEquals(0L, q.remainingMs(created + slaMs + 5));
    }

    @Test
    void questionTransitionsAreForwardOnly() {
        Question q = waiting(0L);
        assertEquals(QuestionStatus.WAITING, q.getStatus());
        assertFalse(q.getStatus().isTerminal());

        q.markAnswered(new Answer("q1", "a", null, "alice", 1L));
        assertEquals(QuestionStatus.ANSWERED, q.getStatus());
        assertTrue(q.getStatus().isTerminal());

        // A terminal question rejects further transitions.
        assertThrows(IllegalStateException.class, () -> q.markAborted(new Answer("q1", null, null, "x", 2L)));
        assertThrows(IllegalStateException.class, q::markExpired);
    }

    @Test
    void startedByExposedAndSerialised() {
        Question q = new Question(
                "q1", "prompt", List.of(new Choice("a", "A")), false, 0L, null, null, "job", 7, "alice", 1L, false);
        assertEquals("alice", q.getStartedBy());
        assertEquals("alice", q.toJson(false, 2L).getString("startedBy"));

        // Legacy records with no starting user serialise startedBy as an empty string (never null).
        Question legacy = new Question(
                "q2", "prompt", List.of(new Choice("a", "A")), false, 0L, null, null, "job", 7, null, 1L, false);
        assertNull(legacy.getStartedBy());
        assertEquals("", legacy.toJson(false, 2L).getString("startedBy"));
    }

    @Test
    void questionJsonIncludesAnswerOnlyWhenRequested() {
        Question q = waiting(0L);
        q.markAnswered(new Answer("q1", "a", null, "alice", 5L));
        long now = System.currentTimeMillis();
        assertFalse(q.toJson(false, now).containsKey("answer"));
        JSONObject withAnswer = q.toJson(true, now);
        assertTrue(withAnswer.containsKey("answer"));
        assertEquals("alice", withAnswer.getJSONObject("answer").getString("answeredBy"));
        assertFalse(withAnswer.getBoolean("bridged"));
    }

    private static Question waiting(long slaMs) {
        return new Question(
                "q1",
                "prompt",
                Collections.singletonList(new Choice("a", "A")),
                false,
                slaMs,
                null,
                null,
                "job",
                1,
                "tester",
                1L,
                false);
    }
}
