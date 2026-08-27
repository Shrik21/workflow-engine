package com.orchpilot.workflow.externalform;

import com.orchpilot.workflow.forms.FormField;
import com.orchpilot.workflow.forms.FormField.FormFieldOption;
import com.orchpilot.workflow.forms.FormFieldType;
import com.orchpilot.workflow.forms.FormValidationRule;
import com.orchpilot.workflow.forms.FormVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything the public form page needs, and deliberately nothing else.
 *
 * <p>The response the specification's rule 29 describes: a title, the fields to render, the expiry, and what the
 * customer may do. It carries no workflow instance id, no tenant id, no task id, no form id, no node name and no
 * variable mapping — a browser sends field values by name, and the server alone knows where they go. The state
 * tells the page which screen to show; {@code fields} is populated only when there is a form to fill in.
 *
 * @param state       which screen to render
 * @param message     a customer-safe explanation, for the non-open states
 * @param formTitle   the form's title
 * @param formDescription the form's description, or null
 * @param fields      the fields to render, or empty when the form is not fillable
 * @param allowSubmit whether the Submit control is offered (instance running and submission allowed)
 * @param allowDraft  whether Save Draft is offered
 * @param expiresAt   when the link expires
 * @param draftData   previously saved values to restore, keyed by field name
 */
public record PublicFormView(ExternalFormState state, String message, String formTitle,
                             String formDescription, List<PublicFormField> fields, boolean allowSubmit,
                             boolean allowDraft, Instant expiresAt, Map<String, Object> draftData) {

    /** A minimal view for a non-fillable state (expired, revoked, already submitted). */
    public static PublicFormView of(ExternalFormState state, String message) {
        return new PublicFormView(state, message, null, null, List.of(), false, false, null, Map.of());
    }

    /** A fillable view built from the pinned form version. */
    public static PublicFormView fillable(ExternalFormState state, String message, FormVersion form,
                                          boolean allowSubmit, boolean allowDraft, Instant expiresAt,
                                          Map<String, Object> draftData) {
        List<PublicFormField> fields = form.getFields().stream().map(PublicFormField::of).toList();
        return new PublicFormView(state, message, form.getTitle(), form.getDescription(), fields,
                allowSubmit, allowDraft, expiresAt, draftData == null ? Map.of() : draftData);
    }

    /**
     * One field, stripped to what a customer's browser needs to render and validate it.
     *
     * <p>No {@code id}, no {@code variable} and no {@code variableType}: the mapping from a field to a workflow
     * variable is the server's business, and telling the customer about it would leak how the workflow is wired.
     */
    public record PublicFormField(FormFieldType type, String name, String label, String placeholder,
                                  String description, Object defaultValue, boolean readOnly,
                                  FormValidationRule validation, List<FormFieldOption> options,
                                  String visibilityExpression, int order, int width) {

        static PublicFormField of(FormField field) {
            return new PublicFormField(field.getType(), field.getName(), field.getLabel(),
                    field.getPlaceholder(), field.getDescription(), field.getDefaultValue(),
                    field.isReadOnly(), field.getValidation(), field.getOptions(),
                    field.getVisibilityExpression(), field.getOrder(), field.getWidth());
        }
    }
}
