// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.model;

/**
 * Lifecycle state of a human-in-the-loop {@link Question}.
 *
 * <p>Transitions are strictly forward: {@code WAITING -> (ANSWERED | ABORTED | EXPIRED)}. A
 * question never leaves a terminal state.
 */
public enum QuestionStatus {

    /** The pipeline is paused and waiting for a human to answer. */
    WAITING,

    /** A human submitted an answer (choice or free text); the pipeline has resumed. */
    ANSWERED,

    /** A human (or the pipeline) aborted the input; the pipeline received an abort. */
    ABORTED,

    /** The SLA elapsed with no answer; the pipeline received a timeout. */
    EXPIRED;

    /** @return {@code true} if this is a terminal state (no further transitions allowed). */
    public boolean isTerminal() {
        return this != WAITING;
    }
}
