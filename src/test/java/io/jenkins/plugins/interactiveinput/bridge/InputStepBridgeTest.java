// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Behavioural tests for the opt-in {@link InputStepBridge}: it must mirror pending native
 * {@code input} steps into the {@link QuestionStore}, forward our answers to the native step, and
 * reconcile mirrors when the native input settles elsewhere or the feature is switched off.
 */
@WithJenkins
class InputStepBridgeTest {

    @Test
    void mirrorsPendingInputAndForwardsApprove(JenkinsRule j) throws Exception {
        enableBridge(true);
        WorkflowRun b = startPausedAtInput(j, "approve", "input message: 'Deploy to prod?'\necho 'resumed-ok'");

        InputStepBridge.get().sync();

        QuestionStore store = QuestionStore.get();
        Question q = onlyBridged(store);
        assertEquals("Deploy to prod?", q.getPrompt());
        assertTrue(q.isBridged(), "mirror must be flagged as bridged");
        assertFalse(q.getChoices().isEmpty(), "a parameterless input must offer a proceed choice");
        assertEquals(InputStepBridge.PROCEED_CHOICE_ID, q.getChoices().get(0).getId());

        // Answering through our store must forward a proceed to the native step and resume the build.
        store.answer(q.getId(), InputStepBridge.PROCEED_CHOICE_ID, null, "alice", QuestionStore.SOURCE_UI);

        j.waitForCompletion(b);
        j.assertBuildStatusSuccess(b);
        j.assertLogContains("resumed-ok", b);
    }

    @Test
    void forwardsDenyAsNativeAbort(JenkinsRule j) throws Exception {
        enableBridge(true);
        WorkflowRun b = startPausedAtInput(j, "deny", "input message: 'Proceed?'\necho 'should-not-print'");

        InputStepBridge.get().sync();
        QuestionStore store = QuestionStore.get();
        Question q = onlyBridged(store);

        // The deny sentinel on a bridged mirror (the modal's "Continue" path, or any REST client) must
        // forward as a native abort — as does an explicit /abort, which settles the mirror ABORTED.
        store.answer(
                q.getId(),
                io.jenkins.plugins.interactiveinput.model.Answer.DENY_CHOICE_ID,
                null,
                "bob",
                QuestionStore.SOURCE_UI);

        j.waitForCompletion(b);
        j.assertBuildStatus(Result.ABORTED, b);
    }

    @Test
    void parameterizedInputMirrorsWithNoChoicesAndLinksToInputPage(JenkinsRule j) throws Exception {
        // B27: a native input that declares parameters cannot be answered by a plain proceed, so it is
        // mirrored with NO choices (and no free text). The mirror carries a context link to the build's
        // own input page; ApiRootAction turns that same target into the modal's forwardUrl so the user
        // is forwarded there instead of dead-ending on "Pick a choice or type an answer".
        enableBridge(true);
        WorkflowRun b = startPausedAtInput(
                j, "params", "input message: 'Need params', parameters: [string(name: 'ENV', defaultValue: 'dev')]");

        InputStepBridge.get().sync();
        QuestionStore store = QuestionStore.get();
        Question q = onlyBridged(store);
        assertTrue(q.isBridged(), "mirror must be flagged as bridged");
        assertTrue(q.getChoices().isEmpty(), "a parameterized native input must be mirrored with no choices");
        assertFalse(q.isAllowFreeText(), "a parameterized native input mirror offers no free text either");
        assertNotNull(q.getContextMd(), "the mirror must explain how to answer a parameterized input");
        assertTrue(
                q.getContextMd().contains("input/"),
                "context must link to the build's input page: " + q.getContextMd());

        // Clean up the still-pending native input to end the build.
        b.getAction(InputAction.class).getExecutions().get(0).doAbort();
        j.waitForCompletion(b);
    }

    @Test
    void nativeAnswerDropsOrphanedMirror(JenkinsRule j) throws Exception {
        enableBridge(true);
        WorkflowRun b = startPausedAtInput(j, "native", "input message: 'go?'\necho 'done'");

        InputStepBridge bridge = InputStepBridge.get();
        bridge.sync();
        QuestionStore store = QuestionStore.get();
        Question q = onlyBridged(store);

        // Simulate the operator answering via the built-in input UI (bypassing our store entirely).
        InputStepExecution ise = b.getAction(InputAction.class).getExecutions().get(0);
        ise.proceed((Map<String, Object>) null);
        j.waitForCompletion(b);
        j.assertBuildStatusSuccess(b);

        // The next reconciliation must drop the now-orphaned mirror.
        bridge.sync();
        assertNull(store.get(q.getId()), "mirror should be dropped once the native input has settled");
    }

    @Test
    void scopedReconcileHealsAnsweredNativeInputForThatJobOnly(JenkinsRule j) throws Exception {
        enableBridge(true);
        WorkflowRun a = startPausedAtInput(j, "scoped-a", "input message: 'A?'\necho 'a-done'");
        WorkflowRun b = startPausedAtInput(j, "scoped-b", "input message: 'B?'\necho 'b-done'");

        InputStepBridge bridge = InputStepBridge.get();
        bridge.sync();
        QuestionStore store = QuestionStore.get();
        Question qa = onlyBridgedFor(store, a.getParent().getFullName());
        Question qb = onlyBridgedFor(store, b.getParent().getFullName());

        // Operator answers A via the built-in input UI (bypassing our store); A resumes and settles.
        a.getAction(InputAction.class).getExecutions().get(0).proceed((Map<String, Object>) null);
        j.waitForCompletion(a);
        j.assertBuildStatusSuccess(a);

        // The scoped read-path reconcile (as GET /questions?job=A triggers) must drop A's now-orphaned
        // mirror immediately — without waiting for the 30s ticker and without touching B's mirror.
        bridge.reconcile(a.getParent().getFullName());
        assertNull(store.get(qa.getId()), "A's mirror must be dropped by the scoped reconcile");
        assertNotNull(store.get(qb.getId()), "a reconcile scoped to A must not disturb B's mirror");

        // Clean up B's still-pending native input to end its build.
        b.getAction(InputAction.class).getExecutions().get(0).doAbort();
        j.waitForCompletion(b);
    }

    @Test
    void disablingBridgeDropsMirrorsWithoutTouchingTheBuild(JenkinsRule j) throws Exception {
        enableBridge(true);
        WorkflowRun b = startPausedAtInput(j, "disable", "input message: 'hold'\necho 'x'");

        InputStepBridge bridge = InputStepBridge.get();
        bridge.sync();
        QuestionStore store = QuestionStore.get();
        assertNotNull(onlyBridged(store));

        enableBridge(false);
        bridge.sync();
        assertTrue(
                store.listAll().stream().noneMatch(Question::isBridged), "disabling the bridge must drop all mirrors");

        // The underlying native input must be untouched (still pending); clean it up to end the build.
        InputStepExecution ise = b.getAction(InputAction.class).getExecutions().get(0);
        assertFalse(ise.isSettled(), "disabling the bridge must not settle the native input");
        ise.doAbort();
        j.waitForCompletion(b);
    }

    // ---- helpers ----

    private static void enableBridge(boolean on) {
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        assertNotNull(cfg, "global config must be registered");
        cfg.getFeatures().setInputStepBridge(on);
        cfg.save();
    }

    private static WorkflowRun startPausedAtInput(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        for (int i = 0; i < 100; i++) {
            InputAction ia = b.getAction(InputAction.class);
            if (ia != null && !ia.getExecutions().isEmpty()) {
                return b;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("build " + b + " never paused at an input step");
    }

    private static Question onlyBridged(QuestionStore store) {
        List<Question> bridged =
                store.listAll().stream().filter(Question::isBridged).collect(Collectors.toList());
        assertEquals(1, bridged.size(), "expected exactly one bridged mirror, got " + bridged);
        return bridged.get(0);
    }

    private static Question onlyBridgedFor(QuestionStore store, String jobFullName) {
        List<Question> bridged = store.listAll().stream()
                .filter(Question::isBridged)
                .filter(q -> jobFullName.equals(q.getJobFullName()))
                .collect(Collectors.toList());
        assertEquals(1, bridged.size(), "expected exactly one bridged mirror for " + jobFullName + ", got " + bridged);
        return bridged.get(0);
    }
}
