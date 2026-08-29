// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps interactive-input notifications in step with the build lifecycle: when a build is deleted its
 * questions and reviews must stop appearing in the global notification centre and stop counting towards
 * the sidebar badge (previously they lingered because nothing cleaned them up on deletion).
 *
 * <p>Two complementary hooks:
 *
 * <ul>
 *   <li>{@link Deletion} — a {@link RunListener} fired the moment a build is deleted. The build's
 *       questions are purged outright (a question is only meaningful alongside its build, whose audit
 *       page is gone), and its reviews are marked "build deleted": the review record is <em>kept</em>
 *       (its comment/decision history is a durable artefact) but it no longer notifies, and the per-job
 *       interactive-view page shows it as build-deleted.</li>
 *   <li>{@link Reconcile} — an {@link ItemListener} whose {@code onLoaded} runs once at startup to
 *       self-heal builds that were deleted before this cleanup existed (or while the controller was
 *       down). It only acts when the owning job still resolves but the build is gone, so nothing is
 *       lost for a job that is merely slow to load.</li>
 * </ul>
 *
 * <p>Both hooks are defensively wrapped: a store fault is logged, never propagated, so it can neither
 * break a build deletion nor abort Jenkins startup.
 */
public final class BuildLifecycleCleanup {

    private static final Logger LOGGER = Logger.getLogger(BuildLifecycleCleanup.class.getName());

    private BuildLifecycleCleanup() {}

    /** Purges questions and marks reviews build-deleted the moment a build is removed. */
    @Extension
    public static class Deletion extends RunListener<Run<?, ?>> {

        @Override
        public void onDeleted(@NonNull Run<?, ?> run) {
            final String job = run.getParent().getFullName();
            final int number = run.getNumber();
            try {
                QuestionStore.get().removeForBuild(job, number);
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING, e, () -> "failed to purge questions for deleted build " + job + " #" + number);
            }
            try {
                ViewStore.get().markBuildDeletedForBuild(job, number);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "failed to mark reviews for deleted build " + job + " #" + number);
            }
        }
    }

    /** One-shot startup reconciliation for builds deleted before this cleanup was in place. */
    @Extension
    public static class Reconcile extends ItemListener {

        @Override
        public void onLoaded() {
            try {
                QuestionStore.get().reconcileDeletedBuilds();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "question deleted-build reconciliation failed", e);
            }
            try {
                ViewStore.get().reconcileDeletedBuilds();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "review deleted-build reconciliation failed", e);
            }
        }
    }
}
