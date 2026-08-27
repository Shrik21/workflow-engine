package com.orchpilot.workflow.task;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional narrowing applied to an inbox query.
 *
 * <p>Not a security boundary. Which tasks a caller may see is decided by the bucket and by
 * {@link TaskAuthorizationService}; this only narrows what is already visible to them. A filter that widened
 * access would be a filter that could be used to escape one.
 *
 * @param workflowId only tasks from this workflow, or null for any
 * @param statuses   only these statuses; empty means the actionable ones
 */
public record TaskFilter(String workflowId, Set<TaskStatus> statuses) {

    public TaskFilter {
        workflowId = workflowId == null || workflowId.isBlank() ? null : workflowId.trim();
        statuses = statuses == null || statuses.isEmpty()
                ? Set.of()
                : EnumSet.copyOf(statuses);
    }

    /** @return a filter that narrows nothing */
    public static TaskFilter none() {
        return new TaskFilter(null, Set.of());
    }

    /**
     * Builds a filter from raw query parameters.
     *
     * <p>An unrecognised status name is ignored rather than rejected. The alternative is a 400 on a bookmarked
     * inbox URL after a status is renamed, which punishes the user for a change on the server.
     *
     * @param workflowId workflow id, may be blank
     * @param statuses   status names, may be null or contain nonsense
     * @return the filter
     */
    public static TaskFilter of(String workflowId, List<String> statuses) {
        Set<TaskStatus> parsed = new LinkedHashSet<>();
        List<String> candidates = statuses == null ? List.of() : statuses;
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            for (String part : candidate.split(",")) {
                try {
                    parsed.add(TaskStatus.valueOf(part.trim().toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // Unknown status: narrows nothing rather than failing the request.
                }
            }
        }
        return new TaskFilter(workflowId, parsed);
    }

    /** @return the statuses as a list, for a repository parameter */
    public List<TaskStatus> statusList() {
        return new ArrayList<>(statuses);
    }
}
