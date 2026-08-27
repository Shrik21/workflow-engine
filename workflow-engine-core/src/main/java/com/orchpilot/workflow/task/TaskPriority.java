package com.orchpilot.workflow.task;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * How urgent a task is.
 *
 * <p>Carries an explicit {@code weight} so the inbox can sort by urgency. Sorting on the enum name would order
 * tasks alphabetically, which puts HIGH below LOW, and sorting on the declaration ordinal makes the order a
 * side effect of the order somebody happened to type the constants in.
 */
public enum TaskPriority {

    LOW(10, "Low"),
    NORMAL(20, "Normal"),
    HIGH(30, "High"),
    URGENT(40, "Urgent");

    private final int weight;
    private final String label;

    TaskPriority(int weight, String label) {
        this.weight = weight;
        this.label = label;
    }

    /** @return higher means more urgent */
    public int weight() {
        return weight;
    }

    public String label() {
        return label;
    }

    /**
     * Parses a priority defensively.
     *
     * @param value candidate name, case-insensitive
     * @return the priority, or empty when unrecognised
     */
    public static Optional<TaskPriority> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.name().equals(normalised)).findFirst();
    }
}
