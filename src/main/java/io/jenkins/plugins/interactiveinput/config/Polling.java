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
 * Notification-bell polling settings (§6.4). The bell polls the REST endpoint at this cadence; there
 * is deliberately no SSE/WebSocket in v0.1 (§8.4) so the plugin works through every corporate proxy.
 */
public class Polling extends AbstractDescribableImpl<Polling> {

    /** Lower bound to protect the master from overly aggressive polling. */
    public static final int MIN_INTERVAL_SECONDS = 5;

    /** Default polling cadence in seconds. */
    public static final int DEFAULT_INTERVAL_SECONDS = 15;

    private int intervalSeconds = DEFAULT_INTERVAL_SECONDS;

    @DataBoundConstructor
    public Polling() {
        // Default set via field initialiser.
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    /**
     * @param intervalSeconds polling cadence; clamped to at least {@link #MIN_INTERVAL_SECONDS} to
     *     avoid hammering the master.
     */
    @DataBoundSetter
    public void setIntervalSeconds(int intervalSeconds) {
        this.intervalSeconds = Math.max(MIN_INTERVAL_SECONDS, intervalSeconds);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Polling> {
        @Override
        public String getDisplayName() {
            return "Interactive Input polling";
        }
    }
}
