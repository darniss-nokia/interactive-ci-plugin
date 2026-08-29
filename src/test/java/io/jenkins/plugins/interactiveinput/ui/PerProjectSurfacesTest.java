// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The per-project surfaces are contributed by {@link TransientActionFactory}s and behave as
 * notification indicators: the job action shows a link only when the job has pending questions, and
 * the run action attaches only to builds that actually used interactive-input.
 */
@WithJenkins
class PerProjectSurfacesTest {

    @Test
    void jobActionSurfacesOnlyWhenPending(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("surf-job");

        InteractiveInputJobAction before = p.getAction(InteractiveInputJobAction.class);
        assertNotNull(before, "job action is always contributed when the feature is on");
        assertEquals(0, before.getPendingCount());
        assertNull(before.getIconFileName(), "no sidebar link while nothing is pending");

        QuestionStore.get()
                .submit(new Question(
                        "sq1",
                        "Approve?",
                        List.of(new Choice("yes", "Yes")),
                        false,
                        0L,
                        null,
                        null,
                        p.getFullName(),
                        1,
                        "alice",
                        System.currentTimeMillis(),
                        false));

        InteractiveInputJobAction after = p.getAction(InteractiveInputJobAction.class);
        assertEquals(1, after.getPendingCount());
        assertNotNull(after.getIconFileName(), "link appears once a question is pending");
    }

    @Test
    void runActionAttachesOnlyToBuildsWithQuestions(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("surf-run");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);

        assertNull(
                b.getAction(InteractiveInputRunAction.class),
                "no run action for a build that never used interactive-input");

        QuestionStore.get()
                .submit(new Question(
                        "rq1",
                        "Approve?",
                        List.of(new Choice("yes", "Yes")),
                        false,
                        0L,
                        null,
                        null,
                        p.getFullName(),
                        b.getNumber(),
                        "alice",
                        System.currentTimeMillis(),
                        false));

        InteractiveInputRunAction ra = b.getAction(InteractiveInputRunAction.class);
        assertNotNull(ra, "run action attaches once the build has a question");
        assertTrue(ra.isWaiting(), "badge is active while the question waits");
        assertNotNull(ra.getIconFileName());
        assertEquals(b.getNumber(), ra.getBuildNumber());
    }
}
