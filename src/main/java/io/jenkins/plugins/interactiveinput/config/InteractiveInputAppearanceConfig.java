// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.ListBoxModel;
import java.util.List;
import jenkins.appearance.AppearanceCategory;
import jenkins.model.GlobalConfiguration;
import jenkins.model.GlobalConfigurationCategory;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * Appearance-facing configuration for the plugin's notification surfaces (§6.4).
 *
 * <p>Kept deliberately separate from {@link InteractiveInputGlobalConfig} (which holds functional
 * flags under <em>Manage Jenkins → System</em>): Jenkins core guidance is that look-and-feel settings
 * belong in their own {@link AppearanceCategory} class so they surface under <em>Manage Jenkins →
 * Appearance</em>. Because {@code AppearanceCategory} has no {@code @Symbol}, JCasC maps this block to
 * the {@code appearance.interactiveInputAppearance} path (verified against
 * {@code GlobalConfigurationCategoryConfigurator}).
 *
 * <p>Look-and-feel switches plus an icon chooser:
 * <ul>
 *   <li>{@link #isNotificationCentre() notificationCentre} — the global bell (off by default). When
 *       on, it lists <em>all</em> answerable questions on the dashboard but scopes to the current
 *       pipeline's questions when viewing a job/build.</li>
 *   <li>{@link #isPerProjectCentre() perProjectCentre} — per-pipeline/per-build surfaces (sidebar
 *       page, build-history "awaiting input" badge, per-build audit view). On by default.</li>
 *   <li>{@link #isJobPageBox() jobPageBox} — the large inline box on the job page. On by default;
 *       independently switchable so an operator can keep the badge/sidebar without the big box.</li>
 *   <li>{@link #isTabNotificationBadge() tabNotificationBadge} — on by default. Mirrors the viewer's
 *       pending count in the browser tab (a "(N)" title prefix and a small dot painted on top of the
 *       existing favicon) when the header bell is enabled. It never replaces the site favicon, so a
 *       custom favicon (e.g. from the Simple Theme plugin) is preserved.</li>
 *   <li>{@link #isViewBuildCard() viewBuildCard} — the Interactive View card on the build overview
 *       (experimental layout). On by default; turn off to hide the card and its top-nav tab while the
 *       dedicated review page, sidebar link and badge stay reachable.</li>
 *   <li>{@link #isOutputBuildCard() outputBuildCard} — the Interactive Output card on the build page.
 *       On by default; gates both the experimental overview card and the classic summary row, while the
 *       dedicated output page stays reachable.</li>
 *   <li>{@link #getIcon() icon} — which Ionicon represents interactive input across the bell, badge
 *       and sidebar.</li>
 * </ul>
 *
 * <p>The authorization switches that govern <em>who</em> may see/answer a question
 * ({@code userScopedNotifications}, {@code lockToBuildStarter}) are <strong>not</strong> look-and-feel
 * and live under <em>Manage Jenkins → System</em> in {@link InteractiveInputGlobalConfig}
 * ({@code unclassified.interactiveInput}), per Jenkins core guidance (review item B18).
 */
@Extension
@Symbol("interactiveInputAppearance")
public class InteractiveInputAppearanceConfig extends GlobalConfiguration {

    /**
     * Selectable icon stems. Most are Ionicons (rendered via the {@code -outline} variant from
     * ionicons-api); {@link #CUSTOM_SYMBOLS} lists the ones this plugin ships itself (Ionicons has no
     * robot glyph, so {@code robot} is a bundled symbol under {@code src/main/resources/images/symbols}).
     */
    public static final List<String> ICON_CHOICES = List.of(
            "chatbubble-ellipses",
            "hand-left",
            "git-pull-request",
            "megaphone",
            "hourglass",
            "alert-circle",
            "notifications",
            "robot",
            "hardware-chip",
            "sparkles");

    /**
     * Icon stems shipped by this plugin (SVGs under {@code src/main/resources/images/symbols/}) rather
     * than sourced from ionicons-api. These render as {@code symbol-<stem> plugin-interactive-ci}
     * (no {@code -outline} suffix, which is an Ionicons-only convention).
     */
    private static final List<String> CUSTOM_SYMBOLS = List.of("robot");

    /** Default icon: a megaphone conveying "needs attention / announcement". */
    public static final String DEFAULT_ICON = "megaphone";

    private boolean notificationCentre;
    private boolean perProjectCentre = true;
    private boolean jobPageBox = true;
    private boolean tabNotificationBadge = true;
    private boolean viewBuildCard = true;
    private boolean outputBuildCard = true;

    @NonNull
    private String icon = DEFAULT_ICON;

    public InteractiveInputAppearanceConfig() {
        load();
    }

    /**
     * @return the singleton, or {@code null} only if the extension is not registered (some minimal
     *     test harnesses). Callers should prefer the {@code *Enabled}/{@code *OrDefault} helpers.
     */
    @CheckForNull
    public static InteractiveInputAppearanceConfig get() {
        return GlobalConfiguration.all().get(InteractiveInputAppearanceConfig.class);
    }

    @Override
    @NonNull
    public GlobalConfigurationCategory getCategory() {
        return GlobalConfigurationCategory.get(AppearanceCategory.class);
    }

    public boolean isNotificationCentre() {
        return notificationCentre;
    }

    @DataBoundSetter
    public void setNotificationCentre(boolean notificationCentre) {
        this.notificationCentre = notificationCentre;
        save();
    }

    public boolean isPerProjectCentre() {
        return perProjectCentre;
    }

    @DataBoundSetter
    public void setPerProjectCentre(boolean perProjectCentre) {
        this.perProjectCentre = perProjectCentre;
        save();
    }

    public boolean isJobPageBox() {
        return jobPageBox;
    }

    @DataBoundSetter
    public void setJobPageBox(boolean jobPageBox) {
        this.jobPageBox = jobPageBox;
        save();
    }

    public boolean isTabNotificationBadge() {
        return tabNotificationBadge;
    }

    @DataBoundSetter
    public void setTabNotificationBadge(boolean tabNotificationBadge) {
        this.tabNotificationBadge = tabNotificationBadge;
        save();
    }

    public boolean isViewBuildCard() {
        return viewBuildCard;
    }

    @DataBoundSetter
    public void setViewBuildCard(boolean viewBuildCard) {
        this.viewBuildCard = viewBuildCard;
        save();
    }

    public boolean isOutputBuildCard() {
        return outputBuildCard;
    }

    @DataBoundSetter
    public void setOutputBuildCard(boolean outputBuildCard) {
        this.outputBuildCard = outputBuildCard;
        save();
    }

    @NonNull
    public String getIcon() {
        return ICON_CHOICES.contains(icon) ? icon : DEFAULT_ICON;
    }

    @DataBoundSetter
    public void setIcon(@CheckForNull String icon) {
        this.icon = icon != null && ICON_CHOICES.contains(icon) ? icon : DEFAULT_ICON;
        save();
    }

    /** @return the theme-aware Jenkins symbol class for the configured icon (via ionicons-api). */
    @NonNull
    public String getIconClassName() {
        return iconClassName(getIcon());
    }

    /**
     * @param iconStem an icon stem (validated against {@link #ICON_CHOICES}; unknown values fall back to
     *     {@link #DEFAULT_ICON})
     * @return the Jenkins symbol class rendered as an SVG: {@code symbol-<name> plugin-interactive-ci}
     *     for plugin-shipped symbols ({@link #CUSTOM_SYMBOLS}), otherwise
     *     {@code symbol-<name>-outline plugin-ionicons-api}
     */
    @NonNull
    public static String iconClassName(@CheckForNull String iconStem) {
        String stem = iconStem != null && ICON_CHOICES.contains(iconStem) ? iconStem : DEFAULT_ICON;
        if (CUSTOM_SYMBOLS.contains(stem)) {
            return "symbol-" + stem + " plugin-interactive-ci";
        }
        return "symbol-" + stem + "-outline plugin-ionicons-api";
    }

    // ---- Null-safe convenience accessors used across the plugin ----

    /** @return whether the global notification bell should render. Off by default. */
    public static boolean notificationCentreEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c != null && c.isNotificationCentre();
    }

    /** @return whether per-pipeline/per-build surfaces are enabled. On by default. */
    public static boolean perProjectCentreEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c == null || c.isPerProjectCentre();
    }

    /** @return whether the large inline job-page box is enabled. On by default. */
    public static boolean jobPageBoxEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c == null || c.isJobPageBox();
    }

    /** @return whether the browser-tab pending badge (title + favicon dot) is enabled. On by default. */
    public static boolean tabNotificationBadgeEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c == null || c.isTabNotificationBadge();
    }

    /** @return whether the Interactive View card on the build overview is enabled. On by default. */
    public static boolean viewBuildCardEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c == null || c.isViewBuildCard();
    }

    /** @return whether the Interactive Output card on the build page is enabled. On by default. */
    public static boolean outputBuildCardEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c == null || c.isOutputBuildCard();
    }

    /** @return the configured icon's symbol class, or the default's when unconfigured. */
    @NonNull
    public static String iconClassNameOrDefault() {
        InteractiveInputAppearanceConfig c = get();
        return c != null ? c.getIconClassName() : iconClassName(DEFAULT_ICON);
    }

    /**
     * Populates the icon dropdown on the Appearance config page (label ⇒ stem). {@code @POST} for
     * the Security Scan CSRF check; {@link Jenkins#ADMINISTER} because this page is overall config.
     */
    @POST
    @NonNull
    public ListBoxModel doFillIconItems() {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return new ListBoxModel();
        }
        ListBoxModel m = new ListBoxModel();
        m.add("Speech bubble — awaiting your response", "chatbubble-ellipses");
        m.add("Raised hand — human action needed", "hand-left");
        m.add("Pull request — approval / review gate", "git-pull-request");
        m.add("Megaphone — needs attention", "megaphone");
        m.add("Hourglass — waiting / pending decision", "hourglass");
        m.add("Alert — attention needed", "alert-circle");
        m.add("Bell — classic notification", "notifications");
        m.add("Robot — automated agent awaiting input", "robot");
        m.add("Chip — automation / agent", "hardware-chip");
        m.add("Sparkles — AI / assistant", "sparkles");
        return m;
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        // Rebind from scratch so unchecked boxes reset to false (checkbox fields are absent when off).
        this.notificationCentre = false;
        this.perProjectCentre = false;
        this.jobPageBox = false;
        this.tabNotificationBadge = false;
        this.viewBuildCard = false;
        this.outputBuildCard = false;
        this.icon = DEFAULT_ICON;
        req.bindJSON(this, json);
        save();
        return true;
    }
}
