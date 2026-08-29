// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import hudson.Extension;
import hudson.model.Job;
import jenkins.model.OptionalJobProperty;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Per-pipeline opt-in for the run-page "needs attention" indicator (review item B5), shown in
 * <em>Configure</em> as its own checkbox alongside the separate "Interactive Input notifications"
 * property.
 *
 * <p>Extends {@link OptionalJobProperty} so it appears as an opt-in checkbox ("Interactive Input —
 * alert user on run page"); the property is attached to the job <strong>only when the operator ticks
 * it</strong>, so its mere presence means the feature is enabled. There are no inner fields — the
 * toggle is the whole setting.
 *
 * <p>When enabled, each build's main page renders a native summary row (see
 * {@code InteractiveInputRunAction/summary.jelly}) that stays hidden until the build has a WAITING
 * question and then reveals live with an "input needed" cue — mirroring the inline job-page box. The
 * indicator is otherwise inert: it never changes build behaviour, only visibility.
 */
public class InteractiveInputRunPageAlertJobProperty extends OptionalJobProperty<Job<?, ?>> {

    @DataBoundConstructor
    public InteractiveInputRunPageAlertJobProperty() {
        // No fields: the OptionalJobProperty enable checkbox is the entire setting.
    }

    /**
     * @param job the job to inspect (may be {@code null} in defensive callers)
     * @return {@code true} when this opt-in property is present on {@code job} (i.e. the operator
     *     enabled the run-page alert in Configure).
     */
    public static boolean isEnabledOn(Job<?, ?> job) {
        return job != null && job.getProperty(InteractiveInputRunPageAlertJobProperty.class) != null;
    }

    @Extension
    @Symbol("interactiveInputRunPageAlert")
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Interactive Input — alert user on run page";
        }
    }
}
