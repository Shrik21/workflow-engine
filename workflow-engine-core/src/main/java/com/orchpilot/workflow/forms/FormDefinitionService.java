package com.orchpilot.workflow.forms;

import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.auth.service.OperationNotAllowedException;
import com.orchpilot.workflow.exception.WorkflowNotFoundException;
import com.orchpilot.workflow.exception.WorkflowValidationException;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.utility.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Form authoring and versioning.
 *
 * <p>Publishing snapshots, exactly as it does for workflows. The head stays editable and a workflow node
 * references an immutable version, so editing a form cannot change what a task already waiting on it will
 * render. Republishing identical content reuses the existing version rather than creating a duplicate,
 * detected by fingerprinting the fields.
 */
@Service
public class FormDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(FormDefinitionService.class);

    /** Field names must be usable as a payload key and as part of a variable path. */
    private static final String NAME_PATTERN = "^[A-Za-z][A-Za-z0-9_]*$";

    private final FormDefinitionRepository definitions;
    private final FormVersionRepository versions;
    private final AuditService audit;

    public FormDefinitionService(FormDefinitionRepository definitions, FormVersionRepository versions,
                                 AuditService audit) {
        this.definitions = definitions;
        this.versions = versions;
        this.audit = audit;
    }

    public Page<FormDefinition> list(FormStatus status, String name, Pageable pageable) {
        if (status != null) {
            return definitions.findByStatus(status, pageable);
        }
        if (name != null && !name.isBlank()) {
            return definitions.findByNameContainingIgnoreCase(name.trim(), pageable);
        }
        return definitions.findAll(pageable);
    }

    /**
     * The forms a workflow's form node can be pointed at.
     *
     * <p>Separate from {@link #list} because it answers a different question. That one is the inventory screen:
     * every form, in whatever state, paged and filterable. This one is a picker feed — the forms that would
     * actually work if selected — and conflating the two would mean the designer offering a draft that has
     * never been published and produces a task with nothing to fill in.
     *
     * @return selectable forms, ordered by name
     */
    public List<FormDefinition> available() {
        return definitions.findByPublishedVersionNotNullAndStatusNotOrderByNameAsc(FormStatus.ARCHIVED);
    }

    public FormDefinition get(String id) {
        return definitions.findById(id)
                .orElseThrow(() -> new WorkflowNotFoundException("No form with id '" + id + "'"));
    }

    public FormDefinition create(FormDefinition submitted) {
        FormDefinition form = new FormDefinition();
        form.setId(UUID.randomUUID().toString());
        form.setStatus(FormStatus.DRAFT);
        form.setCreatedAt(Instant.now());
        CurrentUser.principal().ifPresent(principal -> {
            form.setOwnerId(principal.getUserId());
            form.setCreatedBy(principal.getUsername());
            form.setTenantId(principal.getTenantId());
        });
        apply(form, submitted);

        FormDefinition saved = definitions.save(form);
        audit.record(CurrentUser.actorOrSystem(), "FORM_CREATED", "FORM", saved.getId(), "OK",
                Map.of("name", String.valueOf(saved.getName()), "fields", saved.getFields().size()));
        log.info("Created form {} '{}' with {} field(s)", saved.getId(), saved.getName(),
                saved.getFields().size());
        return saved;
    }

    /**
     * Replaces the draft.
     *
     * <p>A published form returns to {@code DRAFT}, leaving its published versions intact and still in use.
     * That mirrors workflow editing, and it is what makes editing a live form safe.
     */
    public FormDefinition update(String id, FormDefinition submitted) {
        FormDefinition form = get(id);
        if (form.getStatus() == FormStatus.ARCHIVED) {
            throw archived(form);
        }
        apply(form, submitted);
        if (form.getStatus() == FormStatus.PUBLISHED) {
            form.setStatus(FormStatus.DRAFT);
            log.info("Form {} moved back to DRAFT after an edit; version {} remains published",
                    id, form.getPublishedVersion());
        }
        FormDefinition saved = definitions.save(form);
        audit.record(CurrentUser.actorOrSystem(), "FORM_UPDATED", "FORM", id, "OK", null);
        return saved;
    }

    /**
     * Validates and snapshots an immutable version.
     *
     * @param id the form
     * @return the published version, which may be an existing one when nothing changed
     * @throws WorkflowValidationException listing every problem when the form is not publishable
     */
    public FormVersion publish(String id) {
        FormDefinition form = get(id);
        if (form.getStatus() == FormStatus.ARCHIVED) {
            throw archived(form);
        }

        List<String> errors = validateForPublish(form);
        if (!errors.isEmpty()) {
            audit.record(CurrentUser.actorOrSystem(), "FORM_PUBLISH_REJECTED", "FORM", id, "FAILED",
                    Map.of("errors", errors));
            // The first argument is the entity id, which the handler reports alongside the error list.
            throw new WorkflowValidationException(id, errors);
        }

        String hash = fingerprint(form);
        Optional<FormVersion> identical = versions.findByFormDefinitionIdAndDefinitionHash(id, hash);
        if (identical.isPresent()) {
            // Nothing changed. Reusing the version keeps the history meaningful rather than filling it with
            // indistinguishable entries every time someone clicks publish.
            FormVersion existing = identical.get();
            markPublished(form, existing.getVersion());
            log.info("Form {} republished unchanged; reusing version {}", id, existing.getVersion());
            return existing;
        }

        int next = versions.findFirstByFormDefinitionIdOrderByVersionDesc(id)
                .map(version -> version.getVersion() + 1)
                .orElse(1);

        FormVersion snapshot = FormVersion.snapshot(form, next, CurrentUser.actorOrSystem());
        snapshot.setDefinitionHash(hash);
        FormVersion saved = versions.save(snapshot);

        markPublished(form, next);
        audit.record(CurrentUser.actorOrSystem(), "FORM_PUBLISHED", "FORM", id, "OK",
                Map.of("version", next, "fields", saved.getFields().size()));
        log.info("Published form {} as version {}", id, next);
        return saved;
    }

    /**
     * @param id the form
     * @return every version, newest first
     */
    public List<FormVersion> versionsOf(String id) {
        get(id);
        return versions.findByFormDefinitionIdOrderByVersionDesc(id);
    }

    /**
     * The exact version a workflow node references.
     *
     * @param id      the form
     * @param version the version number
     * @return the immutable snapshot
     */
    public FormVersion version(String id, int version) {
        return versions.findByFormDefinitionIdAndVersion(id, version)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Form '" + id + "' has no version " + version));
    }

    /**
     * The version a runtime should render.
     *
     * <p>Resolves to the published version when a node pins one, and otherwise to the latest. A node that
     * pins nothing is following the head, which is convenient in development and why publishing exists.
     *
     * @param id      the form
     * @param pinned  the pinned version, or null
     * @return the snapshot to render
     */
    public FormVersion resolveVersion(String id, Integer pinned) {
        if (pinned != null) {
            return version(id, pinned);
        }
        return versions.findFirstByFormDefinitionIdOrderByVersionDesc(id)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Form '" + id + "' has never been published, so it cannot be used at runtime"));
    }

    /** Copies a form as a new draft, so a variant can be built without risking the original. */
    public FormDefinition clone(String id, String newName) {
        FormDefinition source = get(id);
        FormDefinition copy = new FormDefinition();
        copy.setId(UUID.randomUUID().toString());
        copy.setName(newName == null || newName.isBlank() ? source.getName() + " (copy)" : newName.trim());
        copy.setDescription(source.getDescription());
        copy.setTitle(source.getTitle());
        copy.setColumns(source.getColumns());
        copy.setSubmitButtonText(source.getSubmitButtonText());
        copy.setSaveButtonText(source.getSaveButtonText());
        copy.setSuccessMessage(source.getSuccessMessage());
        // Fresh field ids: sharing them would make the two forms indistinguishable to the designer.
        List<FormField> fields = new ArrayList<>();
        for (FormField field : source.orderedFields()) {
            FormField copied = copyField(field);
            copied.setId(UUID.randomUUID().toString());
            fields.add(copied);
        }
        copy.setFields(fields);
        copy.setStatus(FormStatus.DRAFT);
        copy.setCreatedAt(Instant.now());
        CurrentUser.principal().ifPresent(principal -> {
            copy.setOwnerId(principal.getUserId());
            copy.setCreatedBy(principal.getUsername());
            copy.setTenantId(principal.getTenantId());
        });

        FormDefinition saved = definitions.save(copy);
        audit.record(CurrentUser.actorOrSystem(), "FORM_CLONED", "FORM", saved.getId(), "OK",
                Map.of("source", id));
        return saved;
    }

    public void delete(String id) {
        FormDefinition form = get(id);
        versions.deleteByFormDefinitionId(id);
        definitions.delete(form);
        audit.record(CurrentUser.actorOrSystem(), "FORM_DELETED", "FORM", id, "OK", null);
        log.info("Deleted form {} and its versions", id);
    }

    /**
     * Everything that must hold before a form can be published.
     *
     * <p>Returns every problem at once, as workflow publication does, so a designer fixes them in one pass.
     */
    public List<String> validateForPublish(FormDefinition form) {
        List<String> errors = new ArrayList<>();

        if (form.getName() == null || form.getName().isBlank()) {
            errors.add("The form needs a name.");
        }
        List<FormField> valueFields = form.getFields().stream().filter(FormField::collectsValue).toList();
        if (valueFields.isEmpty()) {
            errors.add("The form has no fields that collect a value.");
        }

        Set<String> names = new LinkedHashSet<>();
        for (FormField field : form.getFields()) {
            String label = field.getLabel() == null || field.getLabel().isBlank()
                    ? String.valueOf(field.getName()) : field.getLabel();

            if (!field.collectsValue()) {
                continue;
            }
            if (field.getName() == null || field.getName().isBlank()) {
                errors.add("Field '" + label + "' has no field name.");
                continue;
            }
            if (!field.getName().matches(NAME_PATTERN)) {
                errors.add("Field name '" + field.getName()
                        + "' must start with a letter and contain only letters, digits and underscores.");
            }
            if (!names.add(field.getName())) {
                // Two fields with one name means the second silently overwrites the first on submission.
                errors.add("Field name '" + field.getName() + "' is used more than once.");
            }
            if (field.getType().hasOptions() && field.getOptions().isEmpty()) {
                errors.add("Field '" + label + "' is a " + field.getType().label().toLowerCase(Locale.ROOT)
                        + " but has no options.");
            }
            if (!field.isMapped()) {
                errors.add("Field '" + label + "' is not mapped to a workflow variable, so its value would "
                        + "be discarded on submission.");
            } else if (field.getVariableType() != null
                    && !field.getType().isCompatibleWith(field.getVariableType())) {
                // The check the specification asks for: a checkbox cannot write a numeric variable.
                errors.add("Field '" + label + "' is a " + field.getType().label()
                        + " and cannot be mapped to a " + field.getVariableType() + " variable.");
            }
            if (field.isReadOnly() && field.getValidation().isRequired()) {
                errors.add("Field '" + label + "' is read-only and required, so it could never be satisfied "
                        + "by the user.");
            }
        }
        return errors;
    }

    // ------------------------------------------------------------------ helpers

    private void apply(FormDefinition target, FormDefinition submitted) {
        target.setName(submitted.getName() == null ? target.getName() : submitted.getName().trim());
        target.setDescription(submitted.getDescription());
        target.setTitle(submitted.getTitle());
        target.setColumns(submitted.getColumns());
        if (submitted.getSubmitButtonText() != null) {
            target.setSubmitButtonText(submitted.getSubmitButtonText());
        }
        if (submitted.getSaveButtonText() != null) {
            target.setSaveButtonText(submitted.getSaveButtonText());
        }
        if (submitted.getSuccessMessage() != null) {
            target.setSuccessMessage(submitted.getSuccessMessage());
        }

        // Ids are assigned server-side. A client-supplied id would let one field's properties overwrite
        // another's, and the designer has no need to choose them.
        List<FormField> fields = new ArrayList<>();
        int order = 0;
        for (FormField field : submitted.getFields()) {
            FormField copy = copyField(field);
            if (copy.getId() == null || copy.getId().isBlank()) {
                copy.setId(UUID.randomUUID().toString());
            }
            copy.setOrder(order++);
            fields.add(copy);
        }
        target.setFields(fields);

        target.setUpdatedAt(Instant.now());
        CurrentUser.username().ifPresent(target::setUpdatedBy);
    }

    private void markPublished(FormDefinition form, int version) {
        form.setStatus(FormStatus.PUBLISHED);
        form.setPublishedVersion(version);
        form.setPublishedAt(Instant.now());
        definitions.save(form);
    }

    /**
     * Fingerprints the parts of a form that change its behaviour.
     *
     * <p>Only the fields and the layout: renaming the submit button is not a new version of the form's
     * contract, and treating it as one would fill the history with noise.
     */
    private String fingerprint(FormDefinition form) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(form.getColumns()).append('\n');
        for (FormField field : form.orderedFields()) {
            canonical.append(field.getName()).append('|')
                    .append(field.getType()).append('|')
                    .append(field.getVariable()).append('|')
                    .append(field.getVariableType()).append('|')
                    .append(field.isReadOnly()).append('|')
                    .append(field.getValidation().isRequired()).append('|')
                    .append(field.getValidation().getMinLength()).append('|')
                    .append(field.getValidation().getMaxLength()).append('|')
                    .append(field.getValidation().getMin()).append('|')
                    .append(field.getValidation().getMax()).append('|')
                    .append(field.getValidation().getPattern()).append('|')
                    .append(field.getVisibilityExpression()).append('|');
            field.getOptions().forEach(option -> canonical.append(option.value()).append(','));
            canonical.append('\n');
        }
        return HashUtils.sha256Hex(canonical.toString());
    }

    private static FormField copyField(FormField source) {
        FormField copy = new FormField();
        copy.setId(source.getId());
        copy.setType(source.getType());
        copy.setName(source.getName() == null ? null : source.getName().trim());
        copy.setLabel(source.getLabel());
        copy.setPlaceholder(source.getPlaceholder());
        copy.setDescription(source.getDescription());
        copy.setVariable(source.getVariable() == null ? null : source.getVariable().trim());
        copy.setVariableType(source.getVariableType());
        copy.setDefaultValue(source.getDefaultValue());
        copy.setReadOnly(source.isReadOnly());
        copy.setValidation(source.getValidation());
        copy.setOptions(source.getOptions());
        copy.setVisibilityExpression(source.getVisibilityExpression());
        copy.setOrder(source.getOrder());
        copy.setWidth(source.getWidth());
        return copy;
    }

    /** Editing or publishing an archived form is refused with a reason the caller can act on. */
    private static OperationNotAllowedException archived(FormDefinition form) {
        return OperationNotAllowedException.conflict("Form '" + form.getName()
                + "' is archived. Clone it to make a new editable copy; its published versions keep working.");
    }
}
