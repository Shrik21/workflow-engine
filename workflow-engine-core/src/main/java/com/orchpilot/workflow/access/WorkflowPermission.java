package com.orchpilot.workflow.access;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A capability on a single workflow, granted through group membership.
 *
 * <p>Distinct from {@link com.orchpilot.workflow.auth.model.Permission}, and the distinction is the whole point
 * of this package. A system permission answers "may this account use the workflow feature at all"; a workflow
 * permission answers "may this account do this to <em>that</em> workflow". A user can hold
 * {@code WORKFLOW_EDIT} at the system level and still be refused an edit on a workflow whose groups they do
 * not belong to.
 *
 * <p>Each constant carries a category and a label so the group editor can render grouped checkboxes without
 * a parallel table in the front end that would drift from this enum.
 */
public enum WorkflowPermission {

    WORKFLOW_VIEW(Category.WORKFLOW, "View workflow"),
    WORKFLOW_EDIT(Category.WORKFLOW, "Edit workflow"),
    WORKFLOW_EXECUTE(Category.WORKFLOW, "Execute workflow"),
    WORKFLOW_DELETE(Category.WORKFLOW, "Delete workflow"),
    WORKFLOW_PUBLISH(Category.WORKFLOW, "Publish workflow"),
    WORKFLOW_CLONE(Category.WORKFLOW, "Clone workflow"),

    EXECUTION_VIEW(Category.EXECUTION, "View executions"),
    EXECUTION_CANCEL(Category.EXECUTION, "Cancel executions"),
    EXECUTION_RETRY(Category.EXECUTION, "Retry executions"),

    WORKFLOW_VERSION_VIEW(Category.VERSION, "View versions"),
    WORKFLOW_VERSION_CREATE(Category.VERSION, "Create versions");

    /** Grouping for the permission editor. */
    public enum Category {
        WORKFLOW("Workflow"),
        EXECUTION("Execution"),
        VERSION("Version");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Category category;
    private final String label;

    WorkflowPermission(Category category, String label) {
        this.category = category;
        this.label = label;
    }

    public Category category() {
        return category;
    }

    public String label() {
        return label;
    }

    /**
     * Parses a permission name, rejecting anything unrecognised.
     *
     * <p>Returns empty rather than throwing so a stored value written by an older version is dropped rather
     * than breaking the read. That fails closed: an unrecognised permission grants nothing.
     *
     * @param value candidate name, case-insensitive
     * @return the permission, or empty when unknown
     */
    public static Optional<WorkflowPermission> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.name().equals(normalised)).findFirst();
    }

    /**
     * @return every permission grouped by category, for the group permission editor
     */
    public static Map<String, List<WorkflowPermission>> byCategory() {
        Map<String, List<WorkflowPermission>> grouped = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            grouped.put(category.label(), Arrays.stream(values())
                    .filter(permission -> permission.category() == category)
                    .toList());
        }
        return grouped;
    }

    /**
     * What a workflow's owner may do to it without belonging to any group.
     *
     * <p>Everything except cancelling and retrying other people's executions, which is an operational duty
     * rather than an ownership one and is granted through a group like any other.
     *
     * @return the owner's default permissions
     */
    public static java.util.Set<WorkflowPermission> ownerDefaults() {
        return java.util.EnumSet.of(
                WORKFLOW_VIEW, WORKFLOW_EDIT, WORKFLOW_EXECUTE, WORKFLOW_DELETE,
                WORKFLOW_PUBLISH, WORKFLOW_CLONE, EXECUTION_VIEW,
                WORKFLOW_VERSION_VIEW, WORKFLOW_VERSION_CREATE);
    }
}
