// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import jenkins.management.Badge;

/**
 * Shared pending-count {@link Badge} for Interactive Input / Interactive View job, run, and tab
 * surfaces. Core's sidepanel and experimental tab bar render this on the right via
 * {@code task-icon-badge} when the action implements {@link jenkins.model.Badgeable}.
 */
final class PendingCountBadge {

    private PendingCountBadge() {}

    /**
     * @return {@code null} when nothing is pending; otherwise a short numeric DANGER badge whose
     *     tooltip is "{@code n pending}".
     */
    @CheckForNull
    static Badge of(int n) {
        if (n <= 0) {
            return null;
        }
        return new Badge(String.valueOf(n), n + " pending", Badge.Severity.DANGER);
    }
}
