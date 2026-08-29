// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * User-scoped notifications and lock-to-build-starter (§user-scoped surfaces).
 *
 * <p>Fixtures: three WAITING questions on one job — {@code qA} started by {@code alice}, {@code qB}
 * started by {@code bob}, and {@code qT} started by the {@code timer} trigger (no human owner). The
 * two Appearance switches are toggled per test and asserted against the notification-centre queries
 * and the effective answer permission.
 */
@WithJenkins
class UserScopeAndLockTest {

    private static final String JOB = "shared-job";

    @Test
    void userScopeShowsOnlyOwnBuildsPlusOwnerlessOnes(JenkinsRule j) throws Exception {
        QuestionStore store = seed(j);
        config().setUserScopedNotifications(true);

        as("alice", () -> {
            assertEquals(2, store.countNotifications(), "alice sees her own (qA) plus the ownerless qT");
            assertEquals(2, store.countNotificationsForJob(JOB));
            assertTrue(store.hasNotificationForBuild(JOB, 1), "qA is alice's build");
            assertFalse(store.hasNotificationForBuild(JOB, 2), "qB is bob's build — hidden from alice");
            assertTrue(store.hasNotificationForBuild(JOB, 3), "qT is ownerless — shared");
            // The per-build count (run-page sidebar badge) honours the same user-scope rules.
            assertEquals(1, store.countNotificationsForBuild(JOB, 1), "alice's own build shows one");
            assertEquals(0, store.countNotificationsForBuild(JOB, 2), "bob's build is hidden from alice");
            assertEquals(1, store.countNotificationsForBuild(JOB, 3), "ownerless build is shared");
        });
        as("bob", () -> assertEquals(2, store.countNotifications(), "bob sees his own (qB) plus ownerless qT"));
    }

    @Test
    void withoutUserScopeEveryoneWhoCanAnswerSeesEverything(JenkinsRule j) throws Exception {
        QuestionStore store = seed(j);
        // Defaults: user-scope off, lock off -> notification set == answerable set.
        as("alice", () -> assertEquals(3, store.countNotifications(), "all three visible when not user-scoped"));
        as("reader", () -> assertEquals(0, store.countNotifications(), "reader cannot answer any (lock off)"));
    }

    @Test
    void lockLetsOthersSeeButNotAnswer(JenkinsRule j) throws Exception {
        QuestionStore store = seed(j);
        config().setLockToBuildStarter(true);

        as("alice", () -> {
            // Lock surfaces readable questions (including others') so they are visible-but-locked.
            assertEquals(3, store.listNotifications().size(), "alice can see all three when lock is on");
            assertTrue(store.canAnswerEffective(store.get("qA")), "alice may answer her own build");
            assertFalse(store.canAnswerEffective(store.get("qB")), "alice may NOT answer bob's build");
            assertTrue(store.canAnswerEffective(store.get("qT")), "ownerless builds are never locked");
        });
        as("reader", () -> {
            assertEquals(3, store.listNotifications().size(), "reader can see all three (view only)");
            assertFalse(store.canAnswerEffective(store.get("qA")), "reader lacks BUILD, cannot answer");
        });
    }

    @Test
    void adminCanAlwaysAnswerEvenWhenLocked(JenkinsRule j) throws Exception {
        QuestionStore store = seed(j);
        config().setLockToBuildStarter(true);
        as("admin", () -> {
            assertTrue(store.canAnswerEffective(store.get("qA")), "admin overrides the lock");
            assertTrue(store.canAnswerEffective(store.get("qB")), "admin overrides the lock");
        });
    }

    @Test
    void userScopeAndLockTogetherShowOnlyOwnAndAnswerable(JenkinsRule j) throws Exception {
        QuestionStore store = seed(j);
        config().setUserScopedNotifications(true);
        config().setLockToBuildStarter(true);
        as("alice", () -> {
            assertEquals(2, store.listNotifications().size(), "only alice's own build plus the ownerless one");
            assertTrue(store.canAnswerEffective(store.get("qA")));
            assertTrue(store.canAnswerEffective(store.get("qT")));
            assertFalse(store.canAnswerEffective(store.get("qB")), "bob's build is hidden AND locked");
        });
    }

    // ---- helpers ----

    private static InteractiveInputGlobalConfig config() {
        InteractiveInputGlobalConfig c = InteractiveInputGlobalConfig.get();
        assertNotNull(c);
        return c;
    }

    private static QuestionStore seed(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().toEveryone();
        auth.grant(Item.READ).everywhere().to("reader", "alice", "bob");
        auth.grant(Item.BUILD).everywhere().to("alice", "bob");
        auth.grant(Jenkins.ADMINISTER).everywhere().to("admin");
        j.jenkins.setAuthorizationStrategy(auth);
        j.createFreeStyleProject(JOB);

        QuestionStore store = QuestionStore.get();
        store.submit(question("qA", 1, "alice"));
        store.submit(question("qB", 2, "bob"));
        store.submit(question("qT", 3, "timer")); // ownerless trigger
        return store;
    }

    private static Question question(String id, int build, String startedBy) {
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
                startedBy,
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
