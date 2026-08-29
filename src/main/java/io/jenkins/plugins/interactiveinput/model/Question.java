// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.ParameterDefinition;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * A single human-in-the-loop question raised by the {@code askInteractive} step (or mirrored from a
 * built-in {@code input} step by the bridge).
 *
 * <p>The metadata here is persisted via XStream under {@code $JENKINS_HOME/interactive-input}. State
 * transitions are guarded by the owning {@code QuestionStore}, which synchronises on the individual
 * {@code Question} instance (§8.9). This class performs the terminal-state bookkeeping but does not
 * itself enforce concurrency.
 */
public class Question implements Serializable {

    private static final long serialVersionUID = 1L;

    @NonNull
    private final String id;

    @NonNull
    private final String prompt;

    @NonNull
    private final List<Choice> choices;

    /**
     * Native {@code input}-style parameter definitions the human fills in (B24); empty for a plain
     * choice/free-text question. Persisted via XStream (each {@link ParameterDefinition} is
     * {@link Serializable}); {@code null} in legacy XML is normalised to an empty list on read.
     */
    @CheckForNull
    private List<ParameterDefinition> parameters;

    private final boolean allowFreeText;

    /** SLA in milliseconds; {@code 0} means no SLA. */
    private final long slaMs;

    @CheckForNull
    private final String contextMd;

    @CheckForNull
    private final String submitterFilter;

    @NonNull
    private final String jobFullName;

    private final int buildNumber;

    /** Jenkins user id (or trigger label) that started the owning build; {@code null} for legacy data. */
    @CheckForNull
    private final String startedBy;

    private final long createdTs;

    /** {@code true} when this question mirrors a built-in {@code input} step (bridge). */
    private final boolean bridged;

    @NonNull
    private QuestionStatus status;

    /** Epoch millis when the question expires, or {@code 0} if no SLA. */
    private final long expiresAt;

    @CheckForNull
    private Answer answer;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public Question(
            @NonNull String id,
            @NonNull String prompt,
            @CheckForNull List<Choice> choices,
            boolean allowFreeText,
            long slaMs,
            @CheckForNull String contextMd,
            @CheckForNull String submitterFilter,
            @NonNull String jobFullName,
            int buildNumber,
            @CheckForNull String startedBy,
            long createdTs,
            boolean bridged) {
        this(
                id,
                prompt,
                choices,
                allowFreeText,
                slaMs,
                contextMd,
                submitterFilter,
                jobFullName,
                buildNumber,
                startedBy,
                createdTs,
                bridged,
                null);
    }

    /** Canonical constructor; {@code parameters} carries native {@code input}-style fields (B24). */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Question(
            @NonNull String id,
            @NonNull String prompt,
            @CheckForNull List<Choice> choices,
            boolean allowFreeText,
            long slaMs,
            @CheckForNull String contextMd,
            @CheckForNull String submitterFilter,
            @NonNull String jobFullName,
            int buildNumber,
            @CheckForNull String startedBy,
            long createdTs,
            boolean bridged,
            @CheckForNull List<ParameterDefinition> parameters) {
        this.id = id;
        this.prompt = prompt;
        this.choices = choices == null ? new ArrayList<>() : new ArrayList<>(choices);
        this.parameters = parameters == null ? new ArrayList<>() : new ArrayList<>(parameters);
        this.allowFreeText = allowFreeText;
        this.slaMs = Math.max(0L, slaMs);
        this.contextMd = contextMd;
        this.submitterFilter = submitterFilter;
        this.jobFullName = jobFullName;
        this.buildNumber = buildNumber;
        this.startedBy = startedBy;
        this.createdTs = createdTs;
        this.bridged = bridged;
        this.status = QuestionStatus.WAITING;
        this.expiresAt = this.slaMs > 0 ? createdTs + this.slaMs : 0L;
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getPrompt() {
        return prompt;
    }

    /** @return an unmodifiable view of the choices (never {@code null}). */
    @NonNull
    public List<Choice> getChoices() {
        return Collections.unmodifiableList(choices);
    }

    /** @return an unmodifiable view of the native {@code input}-style parameters (never {@code null}). */
    @NonNull
    public List<ParameterDefinition> getParameters() {
        return Collections.unmodifiableList(parameters == null ? new ArrayList<>() : parameters);
    }

    /** @return {@code true} if this question is answered by filling in parameters rather than a choice. */
    public boolean hasParameters() {
        return parameters != null && !parameters.isEmpty();
    }

    public boolean isAllowFreeText() {
        return allowFreeText;
    }

    public long getSlaMs() {
        return slaMs;
    }

    @CheckForNull
    public String getContextMd() {
        return contextMd;
    }

    @CheckForNull
    public String getSubmitterFilter() {
        return submitterFilter;
    }

    @NonNull
    public String getJobFullName() {
        return jobFullName;
    }

    public int getBuildNumber() {
        return buildNumber;
    }

    /** @return the user id (or trigger label) that started the build, or {@code null} for legacy data. */
    @CheckForNull
    public String getStartedBy() {
        return startedBy;
    }

    public long getCreatedTs() {
        return createdTs;
    }

    public boolean isBridged() {
        return bridged;
    }

    @NonNull
    public QuestionStatus getStatus() {
        return status;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    @CheckForNull
    public Answer getAnswer() {
        return answer;
    }

    /** @return {@code true} if an SLA is configured and it has elapsed relative to {@code now}. */
    public boolean isExpired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }

    /** @return remaining SLA milliseconds ({@code -1} when no SLA, {@code 0} when already due). */
    public long remainingMs(long now) {
        if (expiresAt <= 0) {
            return -1L;
        }
        return Math.max(0L, expiresAt - now);
    }

    /**
     * Record a human answer, moving the question to {@link QuestionStatus#ANSWERED}.
     *
     * <p>Contract: invoked only by {@code QuestionStore} while holding this question's monitor; not
     * intended for direct use elsewhere.
     *
     * @throws IllegalStateException if the question is already in a terminal state
     */
    public void markAnswered(@NonNull Answer answer) {
        assertWaiting();
        this.answer = answer;
        this.status = QuestionStatus.ANSWERED;
    }

    /**
     * Move the question to {@link QuestionStatus#ABORTED}. Store-only; see {@link #markAnswered}.
     */
    public void markAborted(@NonNull Answer abortRecord) {
        assertWaiting();
        this.answer = abortRecord;
        this.status = QuestionStatus.ABORTED;
    }

    /**
     * Move the question to {@link QuestionStatus#EXPIRED}. Store-only; see {@link #markAnswered}.
     */
    public void markExpired() {
        assertWaiting();
        this.status = QuestionStatus.EXPIRED;
    }

    private void assertWaiting() {
        if (status.isTerminal()) {
            throw new IllegalStateException("Question " + id + " is already " + status);
        }
    }

    /**
     * Serialise this question to JSON for the REST API and the modal.
     *
     * @param includeAnswer whether to include the recorded answer (if any)
     * @param now           reference time for computing {@code remainingMs}
     * @return a JSON object; string values are raw (JSON-escaped by the library). HTML/markdown
     *     escaping for UI rendering is handled at render time, never here.
     */
    @NonNull
    public JSONObject toJson(boolean includeAnswer, long now) {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("prompt", prompt);
        JSONArray arr = new JSONArray();
        for (Choice c : choices) {
            arr.add(c.toJson());
        }
        o.put("choices", arr);
        o.put("allowFreeText", allowFreeText);
        o.put("contextMd", contextMd);
        o.put("jobFullName", jobFullName);
        o.put("buildNumber", buildNumber);
        o.put("startedBy", startedBy == null ? "" : startedBy);
        o.put("createdTs", createdTs);
        o.put("status", status.name());
        o.put("slaMs", slaMs);
        o.put("expiresAt", expiresAt);
        o.put("remainingMs", remainingMs(now));
        o.put("bridged", bridged);
        if (includeAnswer && answer != null) {
            o.put("answer", answer.toJson());
        }
        return o;
    }
}
