package com.orchpilot.workflow.task;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Which set of tasks an inbox request is asking for.
 *
 * <p>An enum rather than a set of boolean query parameters, because the three are mutually exclusive and each has
 * its own authorization rule. {@code ?assignedToMe=true&all=true} has no meaning and would have to be rejected
 * anyway.
 */
public enum TaskBucket {

    /** Assigned to the caller. */
    MINE,

    /** Open, offered to the caller through a candidate group or by name, claimed by nobody. */
    AVAILABLE,

    /** Everything. Requires {@code TASK_VIEW_ALL}. */
    ALL;

    /**
     * @param value candidate name, case-insensitive
     * @return the bucket, defaulting to {@link #MINE} when absent or unrecognised
     */
    public static TaskBucket parseOrMine(String value) {
        return parse(value).orElse(MINE);
    }

    public static Optional<TaskBucket> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(bucket -> bucket.name().equals(normalised)).findFirst();
    }
}
