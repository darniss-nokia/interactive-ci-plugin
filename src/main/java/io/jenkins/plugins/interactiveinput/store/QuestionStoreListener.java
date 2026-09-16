// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import io.jenkins.plugins.interactiveinput.model.Question;

/**
 * Extension point fired by {@link QuestionStore} on every question state transition.
 *
 * <p>Used internally for the structured audit log (§3.1 S8) and available to other plugins that want
 * to react to human-in-the-loop events (e.g. outbound Slack/Teams/email via
 * {@code NotificationDispatcher}). Implementations
 * must be non-blocking and must not throw; the store isolates each listener.
 */
public abstract class QuestionStoreListener implements ExtensionPoint {

    /** A new question has been submitted and is now {@code WAITING}. */
    public void onSubmitted(@NonNull Question question) {}

    /** A question was answered by a human (choice or free text). */
    public void onAnswered(@NonNull Question question) {}

    /** A question was aborted (cancelled). */
    public void onAborted(@NonNull Question question) {}

    /** A question expired without an answer (SLA elapsed). */
    public void onExpired(@NonNull Question question) {}

    @NonNull
    public static ExtensionList<QuestionStoreListener> all() {
        return ExtensionList.lookup(QuestionStoreListener.class);
    }
}
