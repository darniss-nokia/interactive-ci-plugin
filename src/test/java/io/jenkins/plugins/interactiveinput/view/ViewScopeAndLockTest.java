// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Notification-scoping parity for {@code interactiveView} (Req 1): {@link ViewStore} must honour the two
 * Appearance switches exactly as {@link io.jenkins.plugins.interactiveinput.store.QuestionStore} does.
 *
 * <p>Fixtures: three OPEN, notify-enabled reviews on one job — {@code vA} created by {@code alice},
 * {@code vB} by {@code bob}, and {@code vT} by the {@code timer} trigger (no human owner). Unlike
 * questions, a review's base visibility is {@code Item.READ} (any reader is notified); the user-scope
 * switch limits notifications to the viewer's own (and ownerless) builds, and the lock switch only
 * restricts contributing — never viewing.
 */
@WithJenkins
class ViewScopeAndLockTest {

    private static final String JOB = "shared-job";

    @Test
    void userScopeShowsOnlyOwnBuildsPlusOwnerlessOnes(JenkinsRule j) throws Exception {
        ViewStore store = seed(j);
        config().setUserScopedNotifications(true);

        as("alice", () -> {
            assertEquals(2, store.listNotifications().size(), "alice sees her own (vA) plus the ownerless vT");
            assertEquals(2, store.countNotificationsForJob(JOB));
            assertTrue(store.hasNotificationForBuild(JOB, 1), "vA is alice's build");
            assertFalse(store.hasNotificationForBuild(JOB, 2), "vB is bob's build — hidden from alice");
            assertTrue(store.hasNotificationForBuild(JOB, 3), "vT is ownerless — shared");
            assertEquals(1, store.countNotificationsForBuild(JOB, 1), "alice's own build shows one");
            assertEquals(0, store.countNotificationsForBuild(JOB, 2), "bob's build is hidden from alice");
            assertEquals(1, store.countNotificationsForBuild(JOB, 3), "ownerless build is shared");
        });
        as("bob", () -> assertEquals(2, store.listNotifications().size(), "bob sees his own (vB) plus ownerless vT"));
    }

    @Test
    void withoutUserScopeAnyReaderIsNotified(JenkinsRule j) throws Exception {
        ViewStore store = seed(j);
        // Default: user-scope off. A review's base visibility is Item.READ, so any reader is notified
        // (this is the documented difference from questions, whose notifications are answer-scoped).
        as("alice", () -> assertEquals(3, store.listNotifications().size(), "all three visible when not user-scoped"));
        as("reader", () -> assertEquals(3, store.listNotifications().size(), "a reader is notified of reviews too"));
    }

    @Test
    void lockRestrictsContributingButNotViewing(JenkinsRule j) throws Exception {
        ViewStore store = seed(j);
        config().setLockToBuildStarter(true);

        as("alice", () -> {
            assertEquals(3, store.listNotifications().size(), "lock never hides reviews — others can view");
            assertTrue(store.canContributeEffective(store.get("vA")), "alice may contribute to her own build");
            assertFalse(store.canContributeEffective(store.get("vB")), "alice may NOT contribute to bob's build");
            assertTrue(store.canContributeEffective(store.get("vT")), "ownerless builds are never locked");
        });
        as("reader", () -> {
            assertEquals(3, store.listNotifications().size(), "reader can see all three (view only)");
            assertFalse(store.canContributeEffective(store.get("vA")), "reader lacks BUILD, cannot contribute");
        });
    }

    @Test
    void adminCanAlwaysContributeEvenWhenLocked(JenkinsRule j) throws Exception {
        ViewStore store = seed(j);
        config().setLockToBuildStarter(true);
        as("admin", () -> {
            assertTrue(store.canContributeEffective(store.get("vA")), "admin overrides the lock");
            assertTrue(store.canContributeEffective(store.get("vB")), "admin overrides the lock");
        });
    }

    @Test
    void userScopeAndLockTogetherShowOnlyOwnAndContributable(JenkinsRule j) throws Exception {
        ViewStore store = seed(j);
        config().setUserScopedNotifications(true);
        config().setLockToBuildStarter(true);
        as("alice", () -> {
            assertEquals(2, store.listNotifications().size(), "only alice's own build plus the ownerless one");
            assertTrue(store.canContributeEffective(store.get("vA")));
            assertTrue(store.canContributeEffective(store.get("vT")));
            assertFalse(store.canContributeEffective(store.get("vB")), "bob's build is hidden AND locked");
        });
    }

    // ---- helpers ----

    private static InteractiveInputGlobalConfig config() {
        InteractiveInputGlobalConfig c = InteractiveInputGlobalConfig.get();
        assertNotNull(c);
        return c;
    }

    private static ViewStore seed(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().toEveryone();
        auth.grant(Item.READ).everywhere().to("reader", "alice", "bob");
        auth.grant(Item.BUILD).everywhere().to("alice", "bob");
        auth.grant(Jenkins.ADMINISTER).everywhere().to("admin");
        j.jenkins.setAuthorizationStrategy(auth);
        j.createFreeStyleProject(JOB);

        ViewStore store = ViewStore.get();
        store.submit(review("vA", 1, "alice"), "content A");
        store.submit(review("vB", 2, "bob"), "content B");
        store.submit(review("vT", 3, "timer"), "content T"); // ownerless trigger
        return store;
    }

    private static ReviewDocument review(String id, int build, String createdBy) {
        return new ReviewDocument(
                id,
                JOB,
                build,
                "Report",
                "Title",
                "file",
                ReviewDocument.FORMAT_TEXT,
                "text",
                createdBy,
                System.currentTimeMillis(),
                true, // commentable
                false, // editable
                true, // notify
                false, // blocking
                null, // submitterFilter
                0L, // slaMs
                null, // groupId
                null); // mode
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
