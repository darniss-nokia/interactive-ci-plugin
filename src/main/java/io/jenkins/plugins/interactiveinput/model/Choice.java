// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.io.Serializable;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * A single selectable option offered to the human, optionally carrying a {@code why} rationale
 * that explains, in plain language, when this option is the right one.
 *
 * <p>Instances are immutable once constructed and are safe to persist via XStream and to serialise
 * across a Jenkins restart.
 *
 * <p>It is a {@link hudson.model.Describable} (B17) so the {@code askInteractive} step's
 * {@code config.jelly} can render the choice list as a repeatable nested form block in the Pipeline
 * Snippet Generator. The descriptor adds no persisted state, so existing XStream data is unaffected.
 */
public class Choice extends AbstractDescribableImpl<Choice> implements Serializable {

    private static final long serialVersionUID = 1L;

    @NonNull
    private final String id;

    @NonNull
    private final String label;

    @CheckForNull
    private String why;

    /**
     * @param id    stable identifier returned to the pipeline when this choice is picked (required)
     * @param label human-readable label shown in the UI (required)
     */
    @DataBoundConstructor
    public Choice(@NonNull String id, @NonNull String label) {
        this.id = requireNonBlank(id, "choice id");
        this.label = requireNonBlank(label, "choice label");
    }

    /**
     * @param why optional rationale shown in muted text beneath the label
     */
    @DataBoundSetter
    public void setWhy(@CheckForNull String why) {
        this.why = why;
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getLabel() {
        return label;
    }

    @CheckForNull
    public String getWhy() {
        return why;
    }

    /**
     * @return this choice as a JSON object with {@code id}, {@code label} and (if present)
     *     {@code why}. Values are stored raw; JSON string escaping is handled by the JSON library
     *     and HTML escaping is the caller/Jelly's responsibility.
     */
    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("label", label);
        if (why != null && !why.isEmpty()) {
            o.put("why", why);
        }
        return o;
    }

    @NonNull
    private static String requireNonBlank(@CheckForNull String value, @NonNull String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /**
     * Descriptor so {@link Choice} is a first-class {@code Describable} for the step's repeatable
     * choice form (B17). It carries no configuration of its own; the fields are bound directly on the
     * {@link Choice} instance via its {@code @DataBoundConstructor} / {@code @DataBoundSetter}.
     */
    @Extension
    public static class DescriptorImpl extends Descriptor<Choice> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Choice";
        }
    }
}
