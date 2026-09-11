// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.interactiveinput.model.Answer;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.model.QuestionStatus;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Permission model (§6.3, §8.3) and SLA/retention (§8.2, §8.3) coverage for {@link QuestionStore}.
 * Answer uses {@code Item.BUILD} (or submitter); abort uses {@code Item.CANCEL} (or submitter);
 * view uses {@code Item.READ}.
 */
@WithJenkins
class QuestionStorePermissionsSlaTest {

    private static final String JOB = "secured-job";

    @Test
    void buildPermissionRequiredToAnswer(JenkinsRule j) throws Exception {
        secure(j);
        QuestionStore store = QuestionStore.get();
        Question q = store.submit(question("q1", null, 0L, System.currentTimeMillis()));

        as("builder", () -> {
            assertTrue(store.canAnswer(q), "builder has Item.BUILD");
            assertTrue(store.canView(q), "builder has Item.READ");
        });
        as("reader", () -> {
            assertFalse(store.canAnswer(q), "reader lacks Item.BUILD");
            assertTrue(store.canView(q), "reader has Item.READ");
        });
    }

    @Test
    void cancelPermissionRequiredToAbort(JenkinsRule j) throws Exception {
        secure(j);
        QuestionStore store = QuestionStore.get();
        Question q = store.submit(question("qAbort", null, 0L, System.currentTimeMillis()));

        as("builder", () -> assertFalse(store.canAbort(q), "Item.BUILD alone must not abort the run"));
        as("reader", () -> assertFalse(store.canAbort(q), "Item.READ must not abort the run"));
        as("canceler", () -> assertTrue(store.canAbort(q), "Item.CANCEL may abort the run"));
    }

    @Test
    void submitterFilterMayAbortWithoutCancel(JenkinsRule j) throws Exception {
        secure(j);
        QuestionStore store = QuestionStore.get();
        Question q = store.submit(question("qAbortSub", "alice", 0L, System.currentTimeMillis()));

        as("alice", () -> assertTrue(store.canAbort(q), "listed submitter may abort (native input parity)"));
        as("builder", () -> assertFalse(store.canAbort(q), "BUILD without CANCEL and not submitter"));
        as("canceler", () -> assertTrue(store.canAbort(q), "CANCEL may abort even when a submitter filter is set"));
    }

    @Test
    void submitterFilterNarrowsAnswerers(JenkinsRule j) throws Exception {
        secure(j);
        QuestionStore store = QuestionStore.get();
        Question q = store.submit(question("q2", "alice", 0L, System.currentTimeMillis()));

        as("alice", () -> assertTrue(store.canAnswer(q), "alice matches the submitter filter"));
        as("builder", () -> assertFalse(store.canAnswer(q), "builder has Item.BUILD but is not the submitter"));
    }

    @Test
    void listAnswerableReflectsCurrentUser(JenkinsRule j) throws Exception {
        secure(j);
        QuestionStore store = QuestionStore.get();
        store.submit(question("q3", null, 0L, System.currentTimeMillis()));

        as("builder", () -> assertEquals(1, store.listAnswerable().size()));
        as("reader", () -> assertEquals(0, store.listAnswerable().size(), "reader cannot answer, so sees none"));
    }

    @Test
    void scopedQueriesFilterByJobAndBuildAndPermission(JenkinsRule j) throws Exception {
        secure(j);
        j.createFreeStyleProject("other-job");
        QuestionStore store = QuestionStore.get();
        store.submit(question("s1", null, 0L, System.currentTimeMillis())); // JOB #1
        store.submit(questionForBuild("s2", 2)); // JOB #2
        store.submit(new Question(
                "s3",
                "prompt",
                List.of(new Choice("a", "A")),
                false,
                0L,
                null,
                null,
                "other-job",
                1,
                "tester",
                System.currentTimeMillis(),
                false));

        as("builder", () -> {
            assertEquals(2, store.listAnswerableForJob(JOB).size(), "two questions on JOB");
            assertEquals(2, store.countAnswerableForJob(JOB));
            assertEquals(1, store.listAnswerableForJob("other-job").size(), "scoped to the other job");
            assertEquals(1, store.listForBuild(JOB, 1).size());
            assertEquals(1, store.listForBuild(JOB, 2).size());
            assertTrue(store.hasWaitingForBuild(JOB, 1));
            assertTrue(store.hasAnyForBuild(JOB, 2));
            assertFalse(store.hasWaitingForBuild(JOB, 3), "no question for build 3");
        });
        as("reader", () -> {
            assertEquals(0, store.countAnswerableForJob(JOB), "reader cannot answer");
            assertEquals(2, store.listReadableForJob(JOB).size(), "reader can view both on JOB");
        });
    }

    @Test
    void listsAreOrderedByCreationTime(JenkinsRule j) throws Exception {
        secure(j);
        QuestionStore store = QuestionStore.get();
        // The backing map is a ConcurrentHashMap, so without an explicit order the surfaces (notification
        // centre, per-build audit list, multi-question series pager) showed a build's questions in an
        // arbitrary order. The ids are deliberately created NEWEST-first so a listing that leaks the map's
        // iteration order cannot pass by coincidence.
        long t0 = System.currentTimeMillis() - 60_000L;
        store.submit(question("o2", null, 0L, t0 + 1_000L));
        store.submit(question("o1", null, 0L, t0 + 2_000L));
        store.submit(question("o3", null, 0L, t0));

        List<String> byCreation = List.of("o3", "o2", "o1");
        as("builder", () -> {
            assertEquals(byCreation, ids(store.listAnswerableForJob(JOB)));
            assertEquals(byCreation, ids(store.listForBuild(JOB, 1)));
            assertEquals(byCreation, ids(store.listNotificationsForJob(JOB)));
            assertEquals(byCreation, ids(store.listNotifications()));
            assertEquals(byCreation, ids(store.listAnswerable()));
            assertEquals(byCreation, ids(store.listAll()));
        });
        as("reader", () -> assertEquals(byCreation, ids(store.listReadableForJob(JOB))));
    }

    @Test
    void expireOverdueMarksExpired(JenkinsRule j) throws Exception {
        secure(j);
        QuestionStore store = QuestionStore.get();
        long created = System.currentTimeMillis() - 120_000L;
        store.submit(question("q4", null, 60_000L, created)); // expired 60s ago

        store.expireOverdue(System.currentTimeMillis());

        assertEquals(QuestionStatus.EXPIRED, store.get("q4").getStatus());
    }

    @Test
    void compactRemovesOldTerminalQuestions(JenkinsRule j) throws Exception {
        secure(j);
        QuestionStore store = QuestionStore.get();
        store.submit(question("q5", null, 0L, System.currentTimeMillis()));
        Answer a = store.answer("q5", "a", null, "builder", QuestionStore.SOURCE_UI);

        long retentionMs = 7L * 24 * 60 * 60 * 1000;
        // Nothing to compact yet.
        store.compact(a.getAnsweredTs() + 1000L, retentionMs);
        assertEquals(QuestionStatus.ANSWERED, store.get("q5").getStatus());
        // Well past retention -> compacted away.
        store.compact(a.getAnsweredTs() + retentionMs + 1000L, retentionMs);
        assertNull(store.get("q5"), "answered question should be compacted after retention");
    }

    // ---- helpers ----

    private static List<String> ids(List<Question> questions) {
        return questions.stream().map(Question::getId).toList();
    }

    private static void secure(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().toEveryone();
        auth.grant(Item.READ).everywhere().to("reader", "builder", "alice", "canceler");
        auth.grant(Item.BUILD).everywhere().to("builder", "alice");
        auth.grant(Item.CANCEL).everywhere().to("canceler");
        j.jenkins.setAuthorizationStrategy(auth);
        j.createFreeStyleProject(JOB);
    }

    private static Question question(String id, String submitterFilter, long slaMs, long createdTs) {
        return new Question(
                id,
                "prompt",
                List.of(new Choice("a", "A")),
                false,
                slaMs,
                null,
                submitterFilter,
                JOB,
                1,
                "tester",
                createdTs,
                false);
    }

    private static Question questionForBuild(String id, int build) {
        return new Question(
                id,
                "prompt",
                List.of(new Choice("a", "A")),
                false,
                0L,
                null,
                null,
                JOB,
                build,
                "tester",
                System.currentTimeMillis(),
                false);
    }

    @FunctionalInterface
    private interface Body {
        void run();
    }

    private static void as(String userId, Body body) {
        User u = User.getById(userId, true);
        try (ACLContext ignored = ACL.as2(u.impersonate2())) {
            body.run();
        }
    }
}
