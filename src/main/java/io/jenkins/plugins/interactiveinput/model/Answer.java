// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.Secret;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import net.sf.json.JSONObject;

/**
 * An immutable record of a human's answer to a {@link Question}.
 *
 * <p>Exactly one of {@link #getChoiceId()}, {@link #getFreeText()} or {@link #getParameterValues()}
 * is normally set. Two special choice ids resolve the input without a positive answer while still
 * <em>continuing</em> the pipeline (an outright abort uses the store's abort path, not an answer):
 * {@code __deny__} (a human denied but chose to continue) and {@code __skip__} (an automation skipped
 * the input). The step returns the sentinel verbatim so the pipeline can branch on it.
 *
 * <p>When the question declared {@code input}-style parameters (B24), the submitted values are held in
 * {@link #getParameterValues()} and returned to the pipeline with the built-in {@code input} step's
 * contract (a single parameter returns its value; several return a name&#8594;value map).
 */
public class Answer implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Sentinel choice id for "denied, but continue the pipeline" (the modal's "Continue" action). */
    public static final String DENY_CHOICE_ID = "__deny__";

    /** Sentinel choice id for "skip this input" (primarily for automation/AI answering via REST). */
    public static final String SKIP_CHOICE_ID = "__skip__";

    /** Masks a secret parameter value in JSON/audit so it is never echoed back to a client. */
    private static final String REDACTED = "\u2022\u2022\u2022\u2022\u2022\u2022";

    @NonNull
    private final String questionId;

    @CheckForNull
    private final String choiceId;

    @CheckForNull
    private final String freeText;

    /** Submitted {@code input}-style parameter values (name&#8594;value), or {@code null} (B24). */
    @CheckForNull
    private final Map<String, Object> parameterValues;

    @NonNull
    private final String answeredBy;

    private final long answeredTs;

    /**
     * @param questionId the answered question's id (required)
     * @param choiceId   the picked choice id, or {@code null} if a free-text answer
     * @param freeText   the free-text answer, or {@code null} if a choice was picked
     * @param answeredBy Jenkins user id of the submitter (or {@code "SYSTEM"} for internal paths)
     * @param answeredTs epoch millis when the answer was recorded
     */
    public Answer(
            @NonNull String questionId,
            @CheckForNull String choiceId,
            @CheckForNull String freeText,
            @NonNull String answeredBy,
            long answeredTs) {
        this(questionId, choiceId, freeText, null, answeredBy, answeredTs);
    }

    /** Canonical constructor; {@code parameterValues} carries an {@code input}-style answer (B24). */
    public Answer(
            @NonNull String questionId,
            @CheckForNull String choiceId,
            @CheckForNull String freeText,
            @CheckForNull Map<String, Object> parameterValues,
            @NonNull String answeredBy,
            long answeredTs) {
        this.questionId = questionId;
        this.choiceId = choiceId;
        this.freeText = freeText;
        this.parameterValues = parameterValues == null ? null : new LinkedHashMap<>(parameterValues);
        this.answeredBy = answeredBy;
        this.answeredTs = answeredTs;
    }

    @NonNull
    public String getQuestionId() {
        return questionId;
    }

    @CheckForNull
    public String getChoiceId() {
        return choiceId;
    }

    @CheckForNull
    public String getFreeText() {
        return freeText;
    }

    /** @return the submitted {@code input}-style parameter values (name&#8594;value), or {@code null}. */
    @CheckForNull
    public Map<String, Object> getParameterValues() {
        return parameterValues == null ? null : new LinkedHashMap<>(parameterValues);
    }

    /** @return {@code true} if this answer carries {@code input}-style parameter values (B24). */
    public boolean isParameterized() {
        return parameterValues != null && !parameterValues.isEmpty();
    }

    @NonNull
    public String getAnsweredBy() {
        return answeredBy;
    }

    public long getAnsweredTs() {
        return answeredTs;
    }

    /** @return {@code true} if this answer is the sentinel "deny (continue)" answer. */
    public boolean isDeny() {
        return DENY_CHOICE_ID.equals(choiceId);
    }

    /** @return {@code true} if this answer is the sentinel "skip" answer. */
    public boolean isSkip() {
        return SKIP_CHOICE_ID.equals(choiceId);
    }

    /**
     * Resolve the value handed back to the pipeline by the {@code askInteractive} step.
     *
     * @return for a parameterized answer (B24), the single parameter's value when exactly one was
     *     declared, otherwise an ordered name&#8594;value {@link Map} — matching the built-in
     *     {@code input} step's contract exactly; for a choice, its id {@link String}; for free text, a
     *     {@code {text, choice}} map (§6.1).
     */
    @NonNull
    public Object toStepReturnValue() {
        Map<String, Object> pv = parameterValues;
        if (pv != null && !pv.isEmpty()) {
            if (pv.size() == 1) {
                Object only = pv.values().iterator().next();
                return only != null ? only : "";
            }
            return new LinkedHashMap<>(pv);
        }
        if (choiceId != null) {
            return choiceId;
        }
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("text", freeText);
        m.put("choice", null);
        return m;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("questionId", questionId);
        o.put("choiceId", choiceId);
        o.put("freeText", freeText);
        if (parameterValues != null) {
            JSONObject params = new JSONObject();
            for (Map.Entry<String, Object> e : parameterValues.entrySet()) {
                Object v = e.getValue();
                // Never echo a secret value back to any client, even in the audit view.
                params.put(e.getKey(), v instanceof Secret ? REDACTED : v);
            }
            o.put("parameters", params);
        }
        o.put("answeredBy", answeredBy);
        o.put("answeredTs", answeredTs);
        return o;
    }
}
