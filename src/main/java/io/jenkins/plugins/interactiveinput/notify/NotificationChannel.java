// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.DescriptorExtensionList;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;

/**
 * A notify-only outbound delivery channel (email, Teams webhook, Slack webhook, or a future
 * extension). Each implementation ships its own config form; the job property holds a list of them.
 *
 * <p>{@link #send} must not throw and must not block a pipeline thread — callers already dispatch
 * off-thread. Do not add Stapler {@code do*} methods that contact the remote service.
 */
public abstract class NotificationChannel extends AbstractDescribableImpl<NotificationChannel> {

    /**
     * Deliver {@code event}. Implementations log and return on failure; they must not throw.
     *
     * @param event a snapshot with job metadata and an absolute Jenkins URL; never the review file or
     *     question context markdown
     */
    public abstract void send(@NonNull NotificationEvent event);

    @NonNull
    public static DescriptorExtensionList<NotificationChannel, Descriptor<NotificationChannel>> all() {
        return Jenkins.get().getDescriptorList(NotificationChannel.class);
    }
}
