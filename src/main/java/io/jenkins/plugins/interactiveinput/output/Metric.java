// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.output;

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
 * A single statistic published by the {@code interactiveOutput} step: a labelled value with an
 * optional unit and an optional stable {@code key}.
 *
 * <p>Rendered as a KPI card / table row on the build page. When the {@code value} parses as a number
 * and a {@code key} is set, the value also feeds the per-job trend chart (the {@code key} identifies
 * the series across builds). Instances are immutable, XStream-persistable (they are stored inside the
 * build's {@link InteractiveOutputBuildAction}) and a {@link hudson.model.Describable} so the step's
 * {@code config.jelly} can render them as a repeatable nested form block.
 */
public class Metric extends AbstractDescribableImpl<Metric> implements Serializable {

    private static final long serialVersionUID = 1L;

    @NonNull
    private final String label;

    @NonNull
    private final String value;

    @CheckForNull
    private String unit;

    @CheckForNull
    private String key;

    /**
     * @param label human-readable label (required)
     * @param value the value as text (required); may be numeric (feeds the trend) or free-form
     */
    @DataBoundConstructor
    public Metric(@NonNull String label, @NonNull String value) {
        this.label = requireNonBlank(label, "metric label");
        this.value = value == null ? "" : value;
    }

    @DataBoundSetter
    public void setUnit(@CheckForNull String unit) {
        this.unit = unit;
    }

    @DataBoundSetter
    public void setKey(@CheckForNull String key) {
        this.key = key;
    }

    @NonNull
    public String getLabel() {
        return label;
    }

    @NonNull
    public String getValue() {
        return value;
    }

    @CheckForNull
    public String getUnit() {
        return unit;
    }

    @CheckForNull
    public String getKey() {
        return key;
    }

    /** @return the stable series id for the trend: {@code key} if set, otherwise the {@code label}. */
    @NonNull
    public String seriesKey() {
        return key != null && !key.trim().isEmpty() ? key.trim() : label;
    }

    /**
     * @return the value parsed as a double for the trend chart, or {@link Double#NaN} if it is not a
     *     plain number. Strips a leading currency symbol and grouping commas so "$1,234.5" parses.
     */
    public double numericValue() {
        String v = value.trim();
        if (v.isEmpty()) {
            return Double.NaN;
        }
        // Drop a single leading currency/label symbol and thousands separators; keep sign, digits, dot.
        String cleaned = v.replaceAll("[,\\s]", "");
        if (cleaned.length() > 1
                && !Character.isDigit(cleaned.charAt(0))
                && cleaned.charAt(0) != '-'
                && cleaned.charAt(0) != '+') {
            cleaned = cleaned.substring(1);
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** @return {@code true} if the value is numeric (and therefore usable in the trend chart). */
    public boolean isNumeric() {
        return !Double.isNaN(numericValue());
    }

    /** @return the value with its unit appended (for display), e.g. {@code "12.4 kg"}. */
    @NonNull
    public String getDisplayValue() {
        return unit != null && !unit.trim().isEmpty() ? value + " " + unit.trim() : value;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("label", label);
        o.put("value", value);
        if (unit != null && !unit.isEmpty()) {
            o.put("unit", unit);
        }
        if (key != null && !key.isEmpty()) {
            o.put("key", key);
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

    @Extension
    public static class DescriptorImpl extends Descriptor<Metric> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Metric";
        }
    }
}
