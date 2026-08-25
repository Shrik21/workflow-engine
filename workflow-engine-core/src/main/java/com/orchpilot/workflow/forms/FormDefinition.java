package com.orchpilot.workflow.forms;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The editable head of a form.
 *
 * <p>This document is a draft that changes as the designer works. Publishing snapshots it into an immutable
 * {@link FormVersion}, and a workflow node references {@code formDefinitionId} plus {@code formVersion}
 * rather than this document. That is the same arrangement workflows already use, and for the same reason: a
 * task opened two days ago must render the form it was created with, not whatever the designer has since
 * changed it to. Without the snapshot, editing a form would silently alter every task waiting on it.
 */
@Document(collection = "form_definitions")
public class FormDefinition {

    @Id
    private String id;

    @Indexed
    private String name;

    private String description;

    /** Heading shown above the fields at runtime. Defaults to the name. */
    private String title;

    private List<FormField> fields = new ArrayList<>();

    private FormStatus status = FormStatus.DRAFT;

    /** The highest published version, or null when never published. */
    private Integer publishedVersion;

    /** Columns in the field grid: 1, 2 or 3. */
    private int columns = 1;

    private String submitButtonText = "Submit";
    private String saveButtonText = "Save draft";
    private String successMessage = "Submitted successfully";

    @Indexed
    private String ownerId;

    @Indexed
    private String tenantId;

    private String createdBy;
    private Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;
    private Instant publishedAt;

    @Version
    private Long documentVersion;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<FormField> getFields() {
        return fields;
    }

    public void setFields(List<FormField> fields) {
        this.fields = fields == null ? new ArrayList<>() : new ArrayList<>(fields);
    }

    public FormStatus getStatus() {
        return status;
    }

    public void setStatus(FormStatus status) {
        this.status = status == null ? FormStatus.DRAFT : status;
    }

    public Integer getPublishedVersion() {
        return publishedVersion;
    }

    public void setPublishedVersion(Integer publishedVersion) {
        this.publishedVersion = publishedVersion;
    }

    public int getColumns() {
        return columns;
    }

    public void setColumns(int columns) {
        // Clamped rather than rejected: an out-of-range value is a designer slip, not a reason to fail a save.
        this.columns = Math.min(3, Math.max(1, columns));
    }

    public String getSubmitButtonText() {
        return submitButtonText;
    }

    public void setSubmitButtonText(String submitButtonText) {
        this.submitButtonText = submitButtonText;
    }

    public String getSaveButtonText() {
        return saveButtonText;
    }

    public void setSaveButtonText(String saveButtonText) {
        this.saveButtonText = saveButtonText;
    }

    public String getSuccessMessage() {
        return successMessage;
    }

    public void setSuccessMessage(String successMessage) {
        this.successMessage = successMessage;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Long getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Long documentVersion) {
        this.documentVersion = documentVersion;
    }

    /** @return the fields in display order */
    public List<FormField> orderedFields() {
        List<FormField> sorted = new ArrayList<>(fields);
        sorted.sort(Comparator.comparingInt(FormField::getOrder));
        return sorted;
    }

    /**
     * @param name payload key
     * @return the field with that name
     */
    public Optional<FormField> fieldByName(String name) {
        return fields.stream().filter(field -> name != null && name.equals(field.getName())).findFirst();
    }

    /** @return the payload keys this form accepts, so an unexpected key can be rejected */
    public Set<String> valueFieldNames() {
        Set<String> names = new LinkedHashSet<>();
        for (FormField field : fields) {
            if (field.collectsValue() && field.getName() != null) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /** @return the heading to show, falling back to the form name */
    public String displayTitle() {
        return title == null || title.isBlank() ? name : title;
    }

    @Override
    public String toString() {
        return "FormDefinition{" + id + " '" + name + "' v" + publishedVersion
                + " " + status + ", " + fields.size() + " field(s)}";
    }
}
