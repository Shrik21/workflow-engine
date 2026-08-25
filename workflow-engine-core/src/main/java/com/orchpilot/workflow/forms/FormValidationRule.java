package com.orchpilot.workflow.forms;

/**
 * Validation constraints for one field.
 *
 * <p>Enforced on the server. The Angular renderer applies the same rules for immediate feedback, but that is
 * a convenience for the person filling the form and is never trusted: a submission arriving by {@code curl}
 * is validated identically.
 *
 * <p>Every constraint is nullable, meaning "not configured". A mutable POJO because Spring Data materialises
 * it and because the designer edits it field by field.
 */
public class FormValidationRule {

    private Boolean required;
    private Integer minLength;
    private Integer maxLength;
    private Double min;
    private Double max;

    /** Java regular expression the value must match in full. */
    private String pattern;

    /** Message shown when {@link #pattern} fails, since a regex is not an explanation. */
    private String patternMessage;

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public Integer getMinLength() {
        return minLength;
    }

    public void setMinLength(Integer minLength) {
        this.minLength = minLength;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(Integer maxLength) {
        this.maxLength = maxLength;
    }

    public Double getMin() {
        return min;
    }

    public void setMin(Double min) {
        this.min = min;
    }

    public Double getMax() {
        return max;
    }

    public void setMax(Double max) {
        this.max = max;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getPatternMessage() {
        return patternMessage;
    }

    public void setPatternMessage(String patternMessage) {
        this.patternMessage = patternMessage;
    }

    /** @return whether the field must have a value */
    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
}
