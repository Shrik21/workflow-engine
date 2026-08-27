package com.orchpilot.workflow.dto;

import java.util.List;

/**
 * Result of validating a workflow without publishing it.
 *
 * @param valid    whether the workflow could be published
 * @param errors   problems that block publishing
 * @param warnings problems worth knowing about that do not block publishing
 */
public record ValidationResponse(boolean valid, List<String> errors, List<String> warnings) {

    /**
     * @param errors   blocking problems
     * @param warnings non-blocking problems
     * @return a response whose {@code valid} flag reflects the error list
     */
    public static ValidationResponse of(List<String> errors, List<String> warnings) {
        return new ValidationResponse(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }
}
