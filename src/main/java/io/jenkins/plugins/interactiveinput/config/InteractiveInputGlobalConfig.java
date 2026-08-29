// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Global, JCasC-compatible configuration for the plugin (§6.4, §8.8).
 *
 * <p>Maps to the JCasC path {@code unclassified.interactiveInput}. Every capability is opt-in via
 * {@link Features}; the bell cadence and default SLA live in {@link Polling} and {@link Sla}.
 *
 * <p>This class also holds the two <em>authorization</em> switches ({@link #isUserScopedNotifications()
 * userScopedNotifications}, {@link #isLockToBuildStarter() lockToBuildStarter}). They govern <em>who</em>
 * may see/answer a question, so they belong under <em>System</em> (functional config) rather than
 * <em>Appearance</em> (look-and-feel) — review item B18. Both default off and only ever <em>restrict</em>
 * access on top of the core permission checks in {@link io.jenkins.plugins.interactiveinput.store.QuestionStore}
 * — they never widen it.
 */
@Extension
@Symbol("interactiveInput")
public class InteractiveInputGlobalConfig extends GlobalConfiguration {

    /** Default retention for answered/aborted/expired questions before compaction (§8.2). */
    public static final int DEFAULT_RETENTION_DAYS = 7;

    /** Default display label for automation (AI) replies posted under an Interactive View comment. */
    public static final String DEFAULT_AUTOMATION_REPLY_NAME = "AI response";

    /** Bound the automation reply label so a runaway config value cannot break the comment header. */
    private static final int MAX_AUTOMATION_REPLY_NAME = 64;

    @NonNull
    private Features features = new Features();

    @NonNull
    private Polling polling = new Polling();

    @NonNull
    private Sla sla = new Sla();

    private int retentionDays = DEFAULT_RETENTION_DAYS;

    private boolean userScopedNotifications;
    private boolean lockToBuildStarter;
    private boolean reopenBuildDialogEveryVisit;

    @NonNull
    private String automationReplyName = DEFAULT_AUTOMATION_REPLY_NAME;

    public InteractiveInputGlobalConfig() {
        load();
    }

    /**
     * @return the singleton instance, or {@code null} only in the unusual case that the extension is
     *     not registered (e.g. some minimal test harnesses). Callers should treat {@code null} as
     *     "all defaults" via the {@code *OrDefault} helpers below.
     */
    public static InteractiveInputGlobalConfig get() {
        return GlobalConfiguration.all().get(InteractiveInputGlobalConfig.class);
    }

    @NonNull
    public Features getFeatures() {
        return features;
    }

    @DataBoundSetter
    public void setFeatures(@NonNull Features features) {
        this.features = features;
        save();
    }

    @NonNull
    public Polling getPolling() {
        return polling;
    }

    @DataBoundSetter
    public void setPolling(@NonNull Polling polling) {
        this.polling = polling;
        save();
    }

    @NonNull
    public Sla getSla() {
        return sla;
    }

    @DataBoundSetter
    public void setSla(@NonNull Sla sla) {
        this.sla = sla;
        save();
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    @DataBoundSetter
    public void setRetentionDays(int retentionDays) {
        this.retentionDays = Math.max(0, retentionDays);
        save();
    }

    public boolean isUserScopedNotifications() {
        return userScopedNotifications;
    }

    @DataBoundSetter
    public void setUserScopedNotifications(boolean userScopedNotifications) {
        this.userScopedNotifications = userScopedNotifications;
        save();
    }

    public boolean isLockToBuildStarter() {
        return lockToBuildStarter;
    }

    @DataBoundSetter
    public void setLockToBuildStarter(boolean lockToBuildStarter) {
        this.lockToBuildStarter = lockToBuildStarter;
        save();
    }

    public boolean isReopenBuildDialogEveryVisit() {
        return reopenBuildDialogEveryVisit;
    }

    @DataBoundSetter
    public void setReopenBuildDialogEveryVisit(boolean reopenBuildDialogEveryVisit) {
        this.reopenBuildDialogEveryVisit = reopenBuildDialogEveryVisit;
        save();
    }

    /** @return the global display label for automation (AI) replies; never blank (defaulted). */
    @NonNull
    public String getAutomationReplyName() {
        return automationReplyName;
    }

    @DataBoundSetter
    public void setAutomationReplyName(String automationReplyName) {
        String v = automationReplyName == null ? "" : automationReplyName.trim();
        if (v.length() > MAX_AUTOMATION_REPLY_NAME) {
            v = v.substring(0, MAX_AUTOMATION_REPLY_NAME).trim();
        }
        this.automationReplyName = v.isEmpty() ? DEFAULT_AUTOMATION_REPLY_NAME : v;
        save();
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        // Feature flags are booleans and Stapler omits unchecked checkboxes, so start them all-off and
        // let the submitted form re-enable the checked ones (config.jelly renders every flag). The two
        // authorization checkboxes (userScopedNotifications, lockToBuildStarter) are reset the same way.
        // Polling, SLA and retention are on the form too (B20) and are always submitted, so bindJSON
        // binds them from the request; their setters clamp to safe bounds.
        this.features = allFeaturesOff();
        this.userScopedNotifications = false;
        this.lockToBuildStarter = false;
        this.reopenBuildDialogEveryVisit = false;
        req.bindJSON(this, json);
        save();
        return true;
    }

    @NonNull
    private static Features allFeaturesOff() {
        Features f = new Features();
        f.setAskInteractiveStep(false);
        f.setRichModal(false);
        f.setRestApi(false);
        f.setInputStepBridge(false);
        f.setDashboardTile(false);
        f.setInteractiveView(false);
        f.setInteractiveOutput(false);
        return f;
    }

    // ---- Null-safe convenience accessors used across the plugin ----

    @NonNull
    public static Features featuresOrDefault() {
        InteractiveInputGlobalConfig c = get();
        return c != null ? c.getFeatures() : new Features();
    }

    public static int pollingIntervalSecondsOrDefault() {
        InteractiveInputGlobalConfig c = get();
        return c != null ? c.getPolling().getIntervalSeconds() : Polling.DEFAULT_INTERVAL_SECONDS;
    }

    public static int defaultSlaMinutesOrDefault() {
        InteractiveInputGlobalConfig c = get();
        return c != null ? c.getSla().getDefaultMinutes() : Sla.DEFAULT_MINUTES;
    }

    public static int retentionDaysOrDefault() {
        InteractiveInputGlobalConfig c = get();
        return c != null ? c.getRetentionDays() : DEFAULT_RETENTION_DAYS;
    }

    /**
     * @return the configured global display label for automation (AI) replies, or the built-in
     *     {@link #DEFAULT_AUTOMATION_REPLY_NAME default} when unset. Used by the REST comment endpoint to
     *     label an automated reply that does not carry its own per-reply override.
     */
    @NonNull
    public static String automationReplyNameOrDefault() {
        InteractiveInputGlobalConfig c = get();
        String v = c != null ? c.getAutomationReplyName() : null;
        return v != null && !v.trim().isEmpty() ? v : DEFAULT_AUTOMATION_REPLY_NAME;
    }

    /** @return whether notification surfaces are scoped to the build starter. Off by default. */
    public static boolean userScopedNotificationsEnabled() {
        InteractiveInputGlobalConfig c = get();
        return c != null && c.isUserScopedNotifications();
    }

    /** @return whether only the build starter (or an admin) may answer. Off by default. */
    public static boolean lockToBuildStarterEnabled() {
        InteractiveInputGlobalConfig c = get();
        return c != null && c.isLockToBuildStarter();
    }

    /**
     * @return whether the build-page auto-open dialog should re-open on <em>every</em> visit (mode B).
     *     Off by default, which selects the gentler mode A (open once per browser session per build,
     *     dismissible). Controls {@code bell.js} auto-popup behaviour; only ever takes effect when the
     *     rich modal is enabled and a build is actually waiting for input.
     */
    public static boolean reopenBuildDialogEveryVisitEnabled() {
        InteractiveInputGlobalConfig c = get();
        return c != null && c.isReopenBuildDialogEveryVisit();
    }
}
