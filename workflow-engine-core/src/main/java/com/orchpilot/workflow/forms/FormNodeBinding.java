package com.orchpilot.workflow.forms;

import com.orchpilot.workflow.exception.FormSubmissionInvalidException;
import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.WorkflowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Works out which published form a form node refers to, and validates a submission against it.
 *
 * <p>Exists because three unrelated callers need the same answer and must not disagree about it: the node
 * executor when it parks and when it applies a submission, the execution service when somebody posts to
 * {@code /api/executions/{id}/form}, and the task completion service. Three copies of "read {@code formId},
 * read {@code formVersion}, resolve" is three chances for one of them to accept a submission the others would
 * have rejected.
 *
 * <p>Resolution is tolerant. A {@code formId} naming no published form yields empty rather than an exception:
 * form nodes predate the form designer, so an id may legitimately be an arbitrary string, and failing a running
 * execution over a definition that was already accepted would be the wrong trade.
 */
@Component
public class FormNodeBinding {

    private static final Logger log = LoggerFactory.getLogger(FormNodeBinding.class);

    private final FormDefinitionService forms;
    private final FormValidationService validation;

    public FormNodeBinding(FormDefinitionService forms, FormValidationService validation) {
        this.forms = forms;
        this.validation = validation;
    }

    /**
     * @param node          the form node
     * @param configuration its configuration, resolved against the variables when a caller has them
     * @return the form id the node addresses, falling back to the node's own id
     */
    public String formIdOf(WorkflowNode node, Map<String, Object> configuration) {
        if (node.getFormId() != null && !node.getFormId().isBlank()) {
            return node.getFormId();
        }
        String configured = text(configuration == null ? null : configuration.get("formId"));
        return configured == null ? node.getId() : configured;
    }

    /**
     * The version a node pins, from either place it can be written.
     *
     * <p>The node's own {@code formVersion} field wins. {@code configuration.formVersion} is the fallback,
     * because workflows authored before the field existed put it there and must keep resolving to the same
     * version rather than silently jumping to the newest one on their next run.
     *
     * @param node          the form node
     * @param configuration its configuration
     * @return the pinned version, or null to follow the newest published one
     */
    public Integer pinnedVersionOf(WorkflowNode node, Map<String, Object> configuration) {
        if (node != null && node.getFormVersion() != null) {
            return node.getFormVersion();
        }
        return pinnedVersionOf(configuration);
    }

    /**
     * @param configuration the node's configuration
     * @return the pinned form version, or null to follow the newest published one
     */
    public Integer pinnedVersionOf(Map<String, Object> configuration) {
        Object value = configuration == null ? null : configuration.get("formVersion");
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ex) {
            log.warn("Ignoring unparseable formVersion '{}' on a form node", text);
            return null;
        }
    }

    /**
     * @param node          the form node
     * @param configuration its configuration
     * @return the form version to render and validate against, or empty when the node references no published
     *         form
     */
    public Optional<FormVersion> resolve(WorkflowNode node, Map<String, Object> configuration) {
        return resolve(formIdOf(node, configuration), pinnedVersionOf(node, configuration));
    }

    /**
     * @param formId  the form definition id
     * @param pinned  the pinned version, or null for the newest published one
     * @return the version, or empty when nothing resolves
     */
    public Optional<FormVersion> resolve(String formId, Integer pinned) {
        if (formId == null || formId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(forms.resolveVersion(formId, pinned));
        } catch (RuntimeException ex) {
            log.debug("'{}' resolves to no published form version: {}", formId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Validates a submission against the node's published form.
     *
     * <p>Does nothing when the node references no published form. That is not a hole: there is no schema to check
     * against, and the mapping step will likewise write nothing, so an arbitrary payload reaches the node's
     * outputs and no further — exactly the pre-form behaviour.
     *
     * @param node     the node being satisfied
     * @param formData the submitted values
     * @throws FormSubmissionInvalidException when the form rejects the submission
     */
    public void validateOrThrow(WorkflowNode node, Map<String, Object> formData) {
        if (node == null || !NodeTypes.FORM.equals(node.getType())) {
            return;
        }
        resolve(node, node.getConfiguration()).ifPresent(version -> validateOrThrow(version, formData));
    }

    /**
     * @param version  the authoritative form version
     * @param formData the submitted values
     * @throws FormSubmissionInvalidException when the form rejects the submission
     */
    public void validateOrThrow(FormVersion version, Map<String, Object> formData) {
        Map<String, List<String>> problems = validation.validate(version, formData);
        if (!problems.isEmpty()) {
            throw new FormSubmissionInvalidException(problems);
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() || "null".equals(string) ? null : string;
    }
}
