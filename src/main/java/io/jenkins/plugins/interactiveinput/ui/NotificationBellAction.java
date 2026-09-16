// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import hudson.Extension;
import hudson.model.RootAction;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import jenkins.model.Jenkins;

/**
 * Header-button {@link RootAction} for the notification centre.
 *
 * <p>Core renders primary root actions in the page header ({@code lib/layout/header/primaryAction.jelly})
 * with a stable element id {@code root-action-${action.class.simpleName}}. {@code bell.js} then only
 * attaches the live pending-count badge to that button — it no longer invents the button or guesses a
 * header insertion point (hosting review, mawinter69 2026-09-15).
 *
 * <p>{@link #getUrlName()} is {@code null} on purpose, matching core's {@code SearchAction}: the
 * control is a header {@code <button>}, not a navigable page. The existing dropdown/modal stays on
 * click via {@code bell.js}.
 */
@Extension
public class NotificationBellAction implements RootAction {

    /**
     * Id core assigns to this action's header button. Keep in lockstep with
     * {@code root-action-${simpleName}} in {@code lib/layout/header/primaryAction.jelly}.
     */
    public static final String HEADER_BUTTON_ID = "root-action-" + NotificationBellAction.class.getSimpleName();

    /**
     * @return the configured notification symbol when the centre is on and the viewer has Overall/Read;
     *     {@code null} hides the header button (same gate as {@link NotificationBell#isBellVisible()}).
     */
    @Override
    public String getIconFileName() {
        if (!InteractiveInputAppearanceConfig.notificationCentreEnabled()) {
            return null;
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null || !j.hasPermission(Jenkins.READ)) {
            return null;
        }
        return InteractiveInputAppearanceConfig.iconClassNameOrDefault();
    }

    @Override
    public String getDisplayName() {
        return "Interactive Input";
    }

    /** {@code null} so core renders a {@code <button>} rather than a navigable {@code <a>}. */
    @Override
    public String getUrlName() {
        return null;
    }

    @Override
    public boolean isPrimaryAction() {
        return true;
    }
}
