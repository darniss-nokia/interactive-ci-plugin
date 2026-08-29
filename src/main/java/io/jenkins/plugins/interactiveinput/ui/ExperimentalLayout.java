// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.experimentalflags.UserExperimentalFlag;

/**
 * Detects whether the current viewer has opted into Jenkins' experimental "new build/job page"
 * layouts, so the plugin can render its per-build/per-job content as native cards there instead of
 * inside core's hardcoded "Legacy" card (see {@code jenkins/run/OverviewTab/index.jelly} and
 * {@code jenkins/job/OverviewTab/index.jelly}).
 *
 * <p>The concrete flag classes ({@code NewBuildPageUserExperimentalFlag},
 * {@code NewJobPageUserExperimentalFlag}) are {@code @Restricted(NoExternalUse)}, so we must not
 * compile against them (the access-modifier enforcer would fail the build). Instead we resolve the
 * flag exactly like core's own {@code <l:userExperimentalFlag>} tag does: by passing the flag's
 * fully-qualified class name (a plain {@link String}) to the public, non-restricted static
 * {@link UserExperimentalFlag#getFlagValueForCurrentUser(String)}, which loads the class reflectively.
 *
 * <p>Every read fails safe to {@code false} (classic layout) so a missing flag, a non-request thread,
 * or a future core change can never break rendering.
 */
public final class ExperimentalLayout {

    private static final Logger LOGGER = Logger.getLogger(ExperimentalLayout.class.getName());

    /** Fully-qualified class name of core's new-build-page flag (resolved reflectively, never linked). */
    private static final String NEW_BUILD_PAGE_FLAG =
            "jenkins.model.experimentalflags.NewBuildPageUserExperimentalFlag";

    /** Fully-qualified class name of core's new-job-page flag (resolved reflectively, never linked). */
    private static final String NEW_JOB_PAGE_FLAG = "jenkins.model.experimentalflags.NewJobPageUserExperimentalFlag";

    private ExperimentalLayout() {}

    /** @return {@code true} when the current viewer has the experimental "new build page" enabled. */
    public static boolean newBuildPage() {
        return flagEnabled(NEW_BUILD_PAGE_FLAG);
    }

    /** @return {@code true} when the current viewer has the experimental "new job page" enabled. */
    public static boolean newJobPage() {
        return flagEnabled(NEW_JOB_PAGE_FLAG);
    }

    private static boolean flagEnabled(String flagClassName) {
        try {
            Object value = UserExperimentalFlag.getFlagValueForCurrentUser(flagClassName);
            return value != null && Boolean.parseBoolean(String.valueOf(value));
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "could not read experimental-layout flag " + flagClassName);
            return false;
        }
    }
}
