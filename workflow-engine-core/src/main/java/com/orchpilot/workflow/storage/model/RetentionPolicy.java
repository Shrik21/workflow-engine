package com.orchpilot.workflow.storage.model;

/**
 * How long files survive after the version that owns them is archived.
 *
 * <p>The structure is stored now and <strong>nothing enforces it yet</strong>. That is deliberate: a retention
 * setting that silently deletes files is the single most destructive thing this module could do, and shipping the
 * schema first means the day a sweeper is written it operates on policies administrators already chose, rather
 * than on a default nobody reviewed.
 */
public enum RetentionPolicy {

    /** Keep forever. The default, and the only safe one to apply to existing data. */
    NEVER(null),

    DAYS_30(30),
    DAYS_90(90),
    DAYS_180(180),

    /** Uses {@code retentionDays} on the settings document. */
    CUSTOM(null);

    private final Integer days;

    RetentionPolicy(Integer days) {
        this.days = days;
    }

    /**
     * @param customDays the configured value, used only by {@link #CUSTOM}
     * @return the retention window in days, or null when files are kept indefinitely
     */
    public Integer resolveDays(Integer customDays) {
        if (this == CUSTOM) {
            return customDays != null && customDays > 0 ? customDays : null;
        }
        return days;
    }
}
