// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.step;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Result;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * End-to-end tests for the {@code askInteractive} step (§6.1): the pipeline must pause, resume with a
 * choice / free-text answer, fail cleanly on abort, and time out on SLA expiry.
 */
@WithJenkins
class AskInteractiveStepTest {

    @Test
    void stepShipsConfigFormAndChoiceIsDescribable(JenkinsRule j) {
        // B17: a Describable Choice plus a step config.jelly are what let the Pipeline Snippet Generator
        // render a form for askInteractive (previously the only way to learn the params was the plugin
        // page). getConfigPage() resolves the step's config.jelly resource, returning null if absent.
        assertNotNull(j.jenkins.getDescriptor(Choice.class), "Choice must be a Describable with a Descriptor");
        StepDescriptor d = (StepDescriptor) j.jenkins.getDescriptor(AskInteractiveStep.class);
        assertNotNull(d, "askInteractive step descriptor must be registered");
        assertNotNull(d.getConfigPage(), "askInteractive must ship a config.jelly so the Snippet Generator works");
    }

    @Test
    void choiceAnswerResumesWithChoiceId(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "choice",
                "def r = askInteractive(prompt: 'Pick env', choices: ["
                        + "[id: 'staging', label: 'Staging', why: 'matches'],"
                        + "[id: 'production', label: 'Production']])\n"
                        + "echo \"ANS=${r}\"");

        Question q = awaitOneWaiting(j);
        QuestionStore.get().answer(q.getId(), "production", null, "alice", QuestionStore.SOURCE_UI);

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("ANS=production", b);
    }

    @Test
    void freeTextAnswerResumesWithMap(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j, "freetext", "def r = askInteractive(prompt: 'Notes?', allowFreeText: true)\necho \"ANS=${r}\"");

        Question q = awaitOneWaiting(j);
        QuestionStore.get().answer(q.getId(), null, "ship it", "bob", QuestionStore.SOURCE_UI);

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("text:ship it", b);
    }

    @Test
    void denyAbortsRunWithAbortedResult(JenkinsRule j) throws Exception {
        // B26: Deny/abort must abort the RUN like the built-in input step (Result.ABORTED via
        // FlowInterruptedException), not merely fail it — and definitely not silently continue.
        WorkflowRun b = start(j, "abort", "askInteractive(prompt: 'Proceed?')\necho 'unreached'");

        Question q = awaitOneWaiting(j);
        // The store abort path is exactly what the modal's Deny button and REST /abort call.
        QuestionStore.get().abort(q.getId(), "carol", QuestionStore.SOURCE_UI);

        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
        j.assertLogContains("Aborted by carol", b);
        j.assertLogNotContains("unreached", b);
    }

    @Test
    void denyContinueResumesWithDenyMarker(JenkinsRule j) throws Exception {
        // B26: the "Continue" outcome (denied, but proceed) resumes the run with the "__deny__" marker so
        // the pipeline can branch on it.
        WorkflowRun b = start(j, "deny-continue", "def r = askInteractive(prompt: 'Proceed?')\necho \"ANS=${r}\"");

        Question q = awaitOneWaiting(j);
        QuestionStore.get().answer(q.getId(), "__deny__", null, "dave", QuestionStore.SOURCE_UI);

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("ANS=__deny__", b);
    }

    @Test
    void skipResumesWithSkipMarker(JenkinsRule j) throws Exception {
        // B26: the "Skip" outcome (automation/AI, via REST) resumes the run with the "__skip__" marker.
        WorkflowRun b = start(j, "skip", "def r = askInteractive(prompt: 'Proceed?')\necho \"ANS=${r}\"");

        Question q = awaitOneWaiting(j);
        QuestionStore.get().answer(q.getId(), "__skip__", null, "botuser", QuestionStore.SOURCE_REST);

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("ANS=__skip__", b);
    }

    @Test
    void singleParameterAnswerReturnsThatValueDirectly(JenkinsRule j) throws Exception {
        // B24: a single input-style parameter returns its value directly (not a map), exactly like the
        // built-in input step, so `def env = askInteractive(parameters: [string(...)])` is a drop-in.
        WorkflowRun b = start(
                j,
                "one-param",
                "def r = askInteractive(prompt: 'Deploy where?', parameters: ["
                        + "string(name: 'ENV', defaultValue: 'dev')])\n"
                        + "echo \"ANS=${r}\"");

        Question q = awaitOneWaiting(j);
        assertTrue(q.hasParameters(), "the question must carry the declared parameters");
        QuestionStore.get().answerParameters(q.getId(), Map.of("ENV", "prod"), "alice", QuestionStore.SOURCE_UI);

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("ANS=prod", b);
    }

    @Test
    void multipleParametersAnswerReturnsMap(JenkinsRule j) throws Exception {
        // B24: several parameters return a name->value map (again matching the built-in input step).
        WorkflowRun b = start(
                j,
                "multi-param",
                "def r = askInteractive(prompt: 'Release?', parameters: ["
                        + "string(name: 'ENV', defaultValue: 'dev'), booleanParam(name: 'DRY', defaultValue: false)])\n"
                        + "echo \"ENV=${r.ENV};DRY=${r.DRY}\"");

        Question q = awaitOneWaiting(j);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ENV", "prod");
        values.put("DRY", Boolean.TRUE);
        QuestionStore.get().answerParameters(q.getId(), values, "alice", QuestionStore.SOURCE_UI);

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("ENV=prod;DRY=true", b);
    }

    @Test
    void slaExpiryTimesOutTheBuild(JenkinsRule j) throws Exception {
        WorkflowRun b = start(j, "sla", "askInteractive(prompt: 'Deploy?', slaMinutes: 1)\necho 'unreached'");

        Question q = awaitOneWaiting(j);
        // Force the SLA clock past the deadline; the ticker's expiry path runs synchronously here.
        QuestionStore.get().expireOverdue(System.currentTimeMillis() + 61_000L);

        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains("SLA elapsed", b);
    }

    // ---- helpers ----

    private static WorkflowRun start(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p.scheduleBuild2(0).waitForStart();
    }

    private static Question awaitOneWaiting(JenkinsRule j) throws InterruptedException {
        QuestionStore store = QuestionStore.get();
        for (int i = 0; i < 100; i++) {
            if (store.listAll().size() == 1) {
                return store.listAll().get(0);
            }
            Thread.sleep(100L);
        }
        fail("no WAITING question appeared in the store");
        throw new AssertionError("unreachable");
    }
}
