package com.orchpilot.workflow.forms;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * An immutable snapshot of a form, taken when it is published.
 *
 * <p>This is what a workflow node references and what a human task renders. Nothing in the application
 * updates one of these documents after it is written: a task created against version 3 must still render
 * version 3 next week, whatever the designer has done to the draft since.
 *
 * <p>The compound index is unique, so publishing twice concurrently cannot produce two version 4s.
 */
@Document(collection = "form_definition_versions")
@CompoundIndex(name = "uk_form_version", def = "{'formDefinitionId': 1, 'version': -1}", unique = true)
public class FormVersion {

    @Id
    private String id;

    @Indexed
    private String formDefinitionId;

    private int version;

    private String name;
    private String description;
    private String title;

    private List<FormField> fields = new ArrayList<>();

    private int columns = 1;
    private String submitButtonText;
    private String saveButtonText;
    private String successMessage;

    /**
     * Fingerprint of the field definitions, used to skip publishing an identical version.
     *
     * <p>Without it, clicking publish twice produces two indistinguishable versions and makes the history
     * useless for finding when something actually changed.
     */
    private String definitionHash;

    private String publishedBy;
    private Instant publishedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFormDefinitionId() {
        return formDefinitionId;
    }

    public void setFormDefinitionId(String formDefinitionId) {
        this.formDefinitionId = formDefinitionId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
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

    public int getColumns() {
        return columns;
    }

    public void setColumns(int columns) {
        this.columns = columns;
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

    public String getDefinitionHash() {
        return definitionHash;
    }

    public void setDefinitionHash(String definitionHash) {
        this.definitionHash = definitionHash;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
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

    /**
     * Builds a snapshot from the editable head.
     *
     * @param definition the form being published
     * @param version    the version number to assign
     * @param actor      who published it
     * @return the snapshot, not yet persisted
     */
    public static FormVersion snapshot(FormDefinition definition, int version, String actor) {
        FormVersion snapshot = new FormVersion();
        snapshot.setFormDefinitionId(definition.getId());
        snapshot.setVersion(version);
        snapshot.setName(definition.getName());
        snapshot.setDescription(definition.getDescription());
        snapshot.setTitle(definition.getTitle());
        snapshot.setColumns(definition.getColumns());
        snapshot.setSubmitButtonText(definition.getSubmitButtonText());
        snapshot.setSaveButtonText(definition.getSaveButtonText());
        snapshot.setSuccessMessage(definition.getSuccessMessage());
        snapshot.setFields(definition.orderedFields());
        snapshot.setPublishedBy(actor);
        snapshot.setPublishedAt(Instant.now());
        return snapshot;
    }
}
