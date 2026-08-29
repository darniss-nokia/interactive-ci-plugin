// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

/**
 * Lifecycle state of an {@code interactiveView} review document.
 *
 * <p>A document starts {@link #OPEN}. A reviewer decision moves it to {@link #APPROVED},
 * {@link #REJECTED}, {@link #ACKNOWLEDGED} or {@link #CHANGES_REQUESTED}; a blocking ({@code wait:true})
 * view whose SLA elapses moves to {@link #EXPIRED}. All states other than {@link #OPEN} are terminal
 * decisions and resolve a paused pipeline step.
 */
public enum ReviewStatus {

    /** Published for review; no decision recorded yet (comments/edits are still allowed). */
    OPEN,

    /** A reviewer approved the report. */
    APPROVED,

    /** A reviewer rejected the report. */
    REJECTED,

    /** A reviewer acknowledged the report without an approve/reject verdict. */
    ACKNOWLEDGED,

    /**
     * A reviewer requested changes ("Regenerate"): the review's comments are handed back to the pipeline
     * so a generator can course-correct and publish a new review. A terminal decision like the others.
     */
    CHANGES_REQUESTED,

    /** A blocking review's SLA elapsed with no decision; the pipeline received a timeout. */
    EXPIRED,

    /** The owning build was aborted/stopped while a blocking review was still open. */
    ABORTED;

    /** @return {@code true} once a decision (or expiry) has been recorded; no further transitions. */
    public boolean isDecided() {
        return this != OPEN;
    }

    /**
     * @return {@code true} while the durable review copy may still be edited (versioned). Editing is
     *     allowed while {@link #OPEN} and, deliberately, while {@link #CHANGES_REQUESTED}: that state
     *     exists precisely so a generator (e.g. the regenerate-agent) can course-correct the document
     *     in place and record the new version — every other terminal decision is final and read-only.
     */
    public boolean allowsEdit() {
        return this == OPEN || this == CHANGES_REQUESTED;
    }
}
