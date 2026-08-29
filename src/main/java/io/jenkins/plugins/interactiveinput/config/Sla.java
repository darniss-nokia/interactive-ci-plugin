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
 * Default SLA settings (§6.4). Applies only when an {@code askInteractive} invocation does not set
 * its own {@code slaMinutes}. {@code 0} means no SLA (the question waits indefinitely).
 */
public class Sla extends AbstractDescribableImpl<Sla> {

    /** Default SLA in minutes; {@code 0} disables the SLA. */
    public static final int DEFAULT_MINUTES = 0;

    private int defaultMinutes = DEFAULT_MINUTES;

    @DataBoundConstructor
    public Sla() {
        // Default set via field initialiser.
    }

    public int getDefaultMinutes() {
        return defaultMinutes;
    }

    /**
     * @param defaultMinutes default SLA in minutes; negatives are treated as {@code 0} (no SLA).
     */
    @DataBoundSetter
    public void setDefaultMinutes(int defaultMinutes) {
        this.defaultMinutes = Math.max(0, defaultMinutes);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Sla> {
        @Override
        public String getDisplayName() {
            return "Interactive Input SLA";
        }
    }
}
