// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Per-capability <em>functional</em> feature flags (§6.4), shown under <em>Manage Jenkins →
 * System</em>. Every capability is opt-in-able so an operator can enable or disable it via the UI or
 * JCasC without uninstalling the plugin.
 *
 * <p>Defaults: the step, modal and REST API are on; the input-step bridge and dashboard tile are
 * off. Notification-surface visibility (global bell, per-project centre, job-page box, icon) lives
 * separately in {@link InteractiveInputAppearanceConfig} under <em>Manage Jenkins → Appearance</em>,
 * per Jenkins core guidance to keep look-and-feel settings out of functional configuration.
 */
public class Features extends AbstractDescribableImpl<Features> {

    private boolean askInteractiveStep = true;
    private boolean richModal = true;
    private boolean restApi = true;
    private boolean inputStepBridge = false;
    private boolean dashboardTile = false;

    // interactiveView/interactiveOutput were added after the plugin's first release. Controllers that
    // upgrade already have a saved config whose <features> block predates these two flags. XStream
    // instantiates this class without running field initialisers, so a plain "boolean = true" field would
    // load as false for any absent element and silently hide the new surfaces on upgrade — the opposite
    // of the intended "default on". Using a nullable Boolean lets "absent in the persisted XML" (null)
    // mean default-on via the getters, while an explicit true/false from the System form or JCasC is
    // honoured. Fresh construction still defaults on through these initialisers.
    private Boolean interactiveView = Boolean.TRUE;
    private Boolean interactiveOutput = Boolean.TRUE;

    /**
     * Whether an {@code interactiveView} HTML snapshot can be <em>rendered</em> (in a sandboxed frame)
     * instead of only shown as escaped source. Same nullable-Boolean upgrade handling as the two flags
     * above: absent from an older persisted config means default-on.
     */
    private Boolean htmlRendering = Boolean.TRUE;

    @DataBoundConstructor
    public Features() {
        // Defaults set via field initialisers; JCasC/Stapler apply overrides through setters.
    }

    public boolean isAskInteractiveStep() {
        return askInteractiveStep;
    }

    @DataBoundSetter
    public void setAskInteractiveStep(boolean askInteractiveStep) {
        this.askInteractiveStep = askInteractiveStep;
    }

    public boolean isRichModal() {
        return richModal;
    }

    @DataBoundSetter
    public void setRichModal(boolean richModal) {
        this.richModal = richModal;
    }

    public boolean isRestApi() {
        return restApi;
    }

    @DataBoundSetter
    public void setRestApi(boolean restApi) {
        this.restApi = restApi;
    }

    public boolean isInputStepBridge() {
        return inputStepBridge;
    }

    @DataBoundSetter
    public void setInputStepBridge(boolean inputStepBridge) {
        this.inputStepBridge = inputStepBridge;
    }

    public boolean isDashboardTile() {
        return dashboardTile;
    }

    @DataBoundSetter
    public void setDashboardTile(boolean dashboardTile) {
        this.dashboardTile = dashboardTile;
    }

    /**
     * @return whether the {@code interactiveView} review surfaces (page, sidebar, bell, REST) show.
     *     A {@code null} field (upgrade from a config saved before this flag existed) defaults on.
     */
    public boolean isInteractiveView() {
        return interactiveView == null || interactiveView;
    }

    @DataBoundSetter
    public void setInteractiveView(boolean interactiveView) {
        this.interactiveView = interactiveView;
    }

    /**
     * @return whether the {@code interactiveOutput} statistics surfaces (build/job pages) show.
     *     A {@code null} field (upgrade from a config saved before this flag existed) defaults on.
     */
    public boolean isInteractiveOutput() {
        return interactiveOutput == null || interactiveOutput;
    }

    @DataBoundSetter
    public void setInteractiveOutput(boolean interactiveOutput) {
        this.interactiveOutput = interactiveOutput;
    }

    /**
     * @return whether an HTML review snapshot may be rendered in a sandboxed frame (the review page's
     *     "Rendered" view) as well as shown as escaped source. Turning this off leaves HTML documents
     *     source-only, which is how the plugin behaved before the feature existed. A {@code null} field
     *     (upgrade from a config saved before this flag existed) defaults on.
     */
    public boolean isHtmlRendering() {
        return htmlRendering == null || htmlRendering;
    }

    @DataBoundSetter
    public void setHtmlRendering(boolean htmlRendering) {
        this.htmlRendering = htmlRendering;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Features> {
        @Override
        public String getDisplayName() {
            return "Interactive Input features";
        }
    }
}
