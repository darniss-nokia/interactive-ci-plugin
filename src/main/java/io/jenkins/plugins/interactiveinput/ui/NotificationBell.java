// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.PageDecorator;
import hudson.model.Run;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Injects the notification-centre data mount and shared client into every Jenkins page (§6.2.1, §8.4).
 *
 * <p>The header <em>button</em> itself is a primary {@link hudson.model.RootAction}
 * ({@link NotificationBellAction}); this {@link PageDecorator} only contributes {@code header.jelly}
 * (the {@code interactive-input.bell} adjunct) and {@code footer.jelly} (a hidden data mount plus the
 * run-scoped sidebar/console controllers). {@code bell.js} finds the core header button by
 * {@link NotificationBellAction#HEADER_BUTTON_ID} and attaches the live pending-count badge.
 *
 * <p>The bell is context-aware: on the dashboard it lists every question the viewer can answer;
 * inside a pipeline (a page under a {@link Job}) it scopes to that pipeline's questions. The current
 * job is resolved from the Stapler ancestor chain and handed to the client via a {@code data-job}
 * attribute; the initial server-rendered count is scoped to match.
 */
@Extension
public class NotificationBell extends PageDecorator {

    private static final Logger LOGGER = Logger.getLogger(NotificationBell.class.getName());

    /**
     * @return {@code true} if the bell should render on the current page: the feature is enabled and
     *     the viewer has at least Overall/Read (so it never shows on the login page to anonymous).
     */
    public boolean isBellVisible() {
        if (!InteractiveInputAppearanceConfig.notificationCentreEnabled()) {
            return false;
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        return j != null && j.hasPermission(Jenkins.READ);
    }

    /**
     * @return {@code true} if the shared client adjunct should load on this page: the viewer has at
     *     least Overall/Read. The adjunct is loaded even when the bell mount itself is hidden (the
     *     notification centre is off) so its delegated click handlers work on every page — the
     *     build-history badge and, for B6, the console "Open interactive input" link, which opens the
     *     dialog <em>in place</em> on the Console Output page. Without a mount the adjunct only wires
     *     those handlers; it never polls. It stays gated on Overall/Read so it never loads for
     *     anonymous users on the login page.
     */
    public boolean isClientVisible() {
        Jenkins j = Jenkins.getInstanceOrNull();
        return j != null && j.hasPermission(Jenkins.READ);
    }

    /**
     * @return the full name of the {@link Job} the current request is under, or an empty string on
     *     the dashboard / non-job pages. Drives the client's dashboard-vs-pipeline scoping.
     */
    public String getCurrentJobFullName() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return "";
        }
        Job<?, ?> job = req.findAncestorObject(Job.class);
        return job != null ? job.getFullName() : "";
    }

    /** @return the pending count for the initial server render, scoped to the current job if any. */
    public int getInitialCount() {
        try {
            QuestionStore store = QuestionStore.get();
            String jobFullName = getCurrentJobFullName();
            return jobFullName.isEmpty() ? store.countNotifications() : store.countNotificationsForJob(jobFullName);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "could not compute initial bell count", e);
            return 0;
        }
    }

    // ---- Run-scoped surfaces (footer.jelly) --------------------------------------------------
    // Two run-page surfaces cannot live in the classic run summary (summary.jelly renders ONLY on the
    // build's main page): the sidebar pending-count badge must stay live on every run sub-page (main,
    // console, audit) that shows the "Interactive Input" side link, and the auto-open dialog must fire
    // on the Console Output page — a core view we cannot edit. Emitting their tiny controllers from
    // this global PageDecorator (which already loads bell.js everywhere) is the only injection point
    // present on all of those pages. The block is gated on runContextActive so nothing renders off a
    // build page.

    /** @return the {@link Run} the current request is under, or {@code null} off a build page. */
    private Run<?, ?> currentRun() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        return req != null ? req.findAncestorObject(Run.class) : null;
    }

    /**
     * @return {@code true} when the run-scoped controllers should render: the per-project centre is on
     *     (so the run's "Interactive Input" side link exists), the page is under a build, and that
     *     build has at least one interactive-input question. Mirrors the run-action attach condition.
     */
    public boolean isRunContextActive() {
        if (!InteractiveInputAppearanceConfig.perProjectCentreEnabled()) {
            return false;
        }
        Run<?, ?> run = currentRun();
        if (run == null) {
            return false;
        }
        try {
            return QuestionStore.get().hasAnyForBuild(run.getParent().getFullName(), run.getNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "could not evaluate run context for interactive-input surfaces", e);
            return false;
        }
    }

    /** @return the full name of the job owning the current build, or an empty string off a build page. */
    public String getCurrentRunJobFullName() {
        Run<?, ?> run = currentRun();
        return run != null ? run.getParent().getFullName() : "";
    }

    /** @return the current build number, or {@code 0} off a build page. */
    public int getCurrentRunBuildNumber() {
        Run<?, ?> run = currentRun();
        return run != null ? run.getNumber() : 0;
    }

    /** @return the pending (WAITING) count scoped to the current build for the sidebar badge's initial render. */
    public int getCurrentRunPendingCount() {
        Run<?, ?> run = currentRun();
        if (run == null) {
            return 0;
        }
        try {
            return QuestionStore.get()
                    .countNotificationsForBuild(run.getParent().getFullName(), run.getNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "could not compute run-page pending count", e);
            return 0;
        }
    }

    /**
     * @return {@code true} when the run-scoped <em>Interactive View</em> controller should render:
     *     the per-project centre and the {@code interactiveView} feature are on, the page is under a
     *     build, and that build has at least one review document. Mirrors {@code InteractiveViewRunAction}'s
     *     attach condition so the sidebar "Interactive View" link's count stays live on every run sub-page.
     */
    public boolean isRunViewContextActive() {
        if (!InteractiveInputAppearanceConfig.perProjectCentreEnabled()
                || !InteractiveInputGlobalConfig.featuresOrDefault().isInteractiveView()) {
            return false;
        }
        Run<?, ?> run = currentRun();
        if (run == null) {
            return false;
        }
        try {
            return ViewStore.get().hasAnyForBuild(run.getParent().getFullName(), run.getNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "could not evaluate run context for interactive-view surfaces", e);
            return false;
        }
    }

    /** @return the open-review count scoped to the current build for the view sidebar badge's initial render. */
    public int getCurrentRunViewPendingCount() {
        Run<?, ?> run = currentRun();
        if (run == null) {
            return 0;
        }
        try {
            // Notification-scoped (OPEN + notify + readable, honouring the user-scope switch) so the
            // view sidebar badge matches the bell and the "own build's notifications" setting.
            return ViewStore.get().countNotificationsForBuild(run.getParent().getFullName(), run.getNumber());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "could not compute run-page view pending count", e);
            return 0;
        }
    }

    /**
     * @return {@code true} when the current request is a build's HTML Console Output page
     *     ({@code …/console} or {@code …/consoleFull}). Used to scope the auto-open dialog to the
     *     console page only (the raw {@code …/consoleText} endpoint returns plain text with no page
     *     decoration, so it never reaches this decorator).
     */
    public boolean isConsolePage() {
        if (currentRun() == null) {
            return false;
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return false;
        }
        String uri = req.getRequestURI();
        if (uri == null) {
            return false;
        }
        int q = uri.indexOf('?');
        if (q >= 0) {
            uri = uri.substring(0, q);
        }
        uri = uri.replaceAll("/+$", "");
        return uri.endsWith("/console") || uri.endsWith("/consoleFull");
    }

    /**
     * @return whether the auto-open dialog re-opens on every console visit (mode B) rather than once
     *     per browser session (mode A, the default). Read by the client from the auto-open controller.
     */
    public boolean isReopenBuildDialogEveryVisit() {
        return InteractiveInputGlobalConfig.reopenBuildDialogEveryVisitEnabled();
    }

    public int getPollingIntervalSeconds() {
        return InteractiveInputGlobalConfig.pollingIntervalSecondsOrDefault();
    }

    public boolean isRichModalEnabled() {
        return InteractiveInputGlobalConfig.featuresOrDefault().isRichModal();
    }

    /** @return the theme-aware symbol class for the configured notification icon. */
    public String getIconClassName() {
        return InteractiveInputAppearanceConfig.iconClassNameOrDefault();
    }

    /**
     * @return whether the client should mirror the pending count in the browser tab (title prefix +
     *     a dot painted on top of the existing favicon). On by default; a look-and-feel toggle under
     *     <em>Manage Jenkins → Appearance</em>. The client reads this from the bell mount's
     *     {@code data-tab-badge} attribute.
     */
    public boolean isTabNotificationBadge() {
        return InteractiveInputAppearanceConfig.tabNotificationBadgeEnabled();
    }

    /**
     * @return the id core assigns to {@link NotificationBellAction}'s header button, so {@code bell.js}
     *     can attach the live badge without guessing a CSS insertion point.
     */
    public String getHeaderActionId() {
        return NotificationBellAction.HEADER_BUTTON_ID;
    }
}
