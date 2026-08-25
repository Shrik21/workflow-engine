package com.orchpilot.workflow.forms;

import com.orchpilot.workflow.dto.ValidationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Form authoring.
 *
 * <p>The version endpoints are the ones the runtime uses: a workflow node references a form id and a version
 * number, and the task renderer loads that exact snapshot. Editing the draft afterwards cannot change what a
 * waiting task displays.
 *
 * <p>Authorship requires {@code WORKFLOW_EDIT} rather than a form-specific permission. A form only exists to
 * serve a workflow, and giving it a separate permission set would mean two things to configure for one
 * capability. Reading is open to anyone who can view workflows, since a form is not sensitive on its own.
 */
@RestController
@RequestMapping("/api/forms")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Forms", description = "Form definitions, versioning and the field catalogue")
public class FormController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FormDefinitionService forms;
    private final FormValidationService validation;

    public FormController(FormDefinitionService forms, FormValidationService validation) {
        this.forms = forms;
        this.validation = validation;
    }

    @GetMapping("/field-types")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The field catalogue",
            description = """
                    Every field type with its label, category, whether it collects a value, whether it needs
                    options, and the variable data types it may be mapped to.

                    The designer renders its palette and its variable-type check from this, so adding a field
                    type to the server makes it available with no front-end change.""")
    public Map<String, List<Map<String, Object>>> fieldTypes() {
        Map<String, List<Map<String, Object>>> catalogue = new java.util.LinkedHashMap<>();
        FormFieldType.byCategory().forEach((category, types) ->
                catalogue.put(category, types.stream().map(type -> Map.<String, Object>of(
                        "name", type.name(),
                        "label", type.label(),
                        "collectsValue", type.collectsValue(),
                        "hasOptions", type.hasOptions(),
                        "compatibleTypes", type.compatibleTypes().stream().map(Enum::name).sorted().toList()
                )).toList()));
        return catalogue;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('WORKFLOW_VIEW')")
    @Operation(summary = "List forms")
    public Page<FormResponse> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String name,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        FormStatus parsed = status == null || status.isBlank()
                ? null : FormStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        return forms.list(parsed, name, pageable).map(FormResponse::from);
    }

    @GetMapping("/available")
    @PreAuthorize("hasAuthority('WORKFLOW_VIEW')")
    @Operation(summary = "Forms a workflow node can be pointed at",
            description = """
                    The picker feed for the designer's form node, matching /api/groups/available and
                    /api/users/available.

                    Returns forms that have a published version and are not archived — which is not the same as
                    "status is PUBLISHED". Editing a published form returns its head to DRAFT while the
                    published snapshot stays in use, so filtering on the status would hide a usable form from
                    the moment somebody opened it in the designer.

                    Carries no fields. A dropdown needs a name and an id; the definition comes from
                    /api/forms/{id}/versions/{version} when something actually needs to render it.""")
    public List<FormSummaryResponse> available() {
        return forms.available().stream().map(FormSummaryResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('WORKFLOW_VIEW')")
    @Operation(summary = "Get a form's editable draft")
    public FormDefinition get(@PathVariable String id) {
        return forms.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('WORKFLOW_CREATE')")
    @Operation(summary = "Create a form",
            description = "Field ids are assigned by the server; any supplied in the request are replaced.")
    @ApiResponse(responseCode = "201", description = "Created as a draft")
    public ResponseEntity<FormDefinition> create(@Valid @RequestBody FormDefinition request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(forms.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('WORKFLOW_EDIT')")
    @Operation(summary = "Replace a form's draft",
            description = "A published form returns to DRAFT. Its published versions stay intact and any task "
                    + "already waiting continues to render the version it was created with.")
    public FormDefinition update(@PathVariable String id, @Valid @RequestBody FormDefinition request) {
        return forms.update(id, request);
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasAuthority('WORKFLOW_VIEW')")
    @Operation(summary = "Check whether a form can be published",
            description = "Returns every problem at once: duplicate or malformed field names, choice fields "
                    + "with no options, unmapped fields, and field types incompatible with their variable.")
    public ValidationResponse validate(@PathVariable String id) {
        List<String> errors = forms.validateForPublish(forms.get(id));
        return new ValidationResponse(errors.isEmpty(), errors, List.of());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('WORKFLOW_PUBLISH')")
    @Operation(summary = "Publish an immutable version",
            description = "Snapshots the draft. Republishing unchanged content reuses the existing version "
                    + "rather than creating a duplicate.")
    @ApiResponse(responseCode = "422", description = "The form is not publishable; the detail list says why")
    public FormVersion publish(@PathVariable String id) {
        return forms.publish(id);
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('WORKFLOW_VIEW')")
    @Operation(summary = "List a form's versions, newest first")
    public List<FormVersion> versions(@PathVariable String id) {
        return forms.versionsOf(id);
    }

    @GetMapping("/{id}/versions/{version}")
    @PreAuthorize("hasAuthority('WORKFLOW_VIEW')")
    @Operation(summary = "Get one immutable version",
            description = "What the runtime renders. This document never changes once written.")
    public FormVersion version(@PathVariable String id, @PathVariable int version) {
        return forms.version(id, version);
    }

    @PostMapping("/{id}/clone")
    @PreAuthorize("hasAuthority('WORKFLOW_CREATE')")
    @Operation(summary = "Copy a form as a new draft")
    @ApiResponse(responseCode = "201", description = "Created")
    public ResponseEntity<FormDefinition> clone(@PathVariable String id,
                                                @RequestParam(required = false) String name) {
        return ResponseEntity.status(HttpStatus.CREATED).body(forms.clone(id, name));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('WORKFLOW_DELETE')")
    @Operation(summary = "Delete a form and every version",
            description = "Any workflow node referencing it will fail validation afterwards.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        forms.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * One selectable form, for a dropdown.
     *
     * <p>Deliberately smaller than {@link FormResponse}: an id, a name to display, the version a node would
     * pin, and enough context to choose between two similarly named forms. No fields, no authorship, no
     * timestamps.
     *
     * @param id          what the node stores
     * @param name        what the dropdown shows
     * @param description one line of context under the name
     * @param version     the newest published version, which is what a node selecting this form pins
     * @param status      the head's status, so the designer can say "being edited" without a second call
     */
    public record FormSummaryResponse(String id, String name, String description, Integer version,
                                      FormStatus status) {

        static FormSummaryResponse from(FormDefinition form) {
            return new FormSummaryResponse(form.getId(), form.getName(), form.getDescription(),
                    form.getPublishedVersion(), form.getStatus());
        }
    }

    /**
     * A form in a list, without its fields.
     *
     * <p>The list is for choosing a form, and a page of twenty forms with every field expanded is a large
     * response nobody reads. The full draft comes from {@code GET /api/forms/{id}}.
     */
    public record FormResponse(
            String id,
            String name,
            String description,
            FormStatus status,
            Integer publishedVersion,
            int fieldCount,
            String createdBy,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {

        static FormResponse from(FormDefinition form) {
            return new FormResponse(form.getId(), form.getName(), form.getDescription(), form.getStatus(),
                    form.getPublishedVersion(), form.getFields().size(), form.getCreatedBy(),
                    form.getCreatedAt(), form.getUpdatedAt());
        }
    }
}
