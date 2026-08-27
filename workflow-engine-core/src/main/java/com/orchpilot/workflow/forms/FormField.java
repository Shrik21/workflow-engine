package com.orchpilot.workflow.forms;

import java.util.ArrayList;
import java.util.List;

/**
 * One field on a form.
 *
 * <p>Two identifiers, and the difference matters. {@code id} is stable and internal, used by the designer to
 * track a field across reorders and renames. {@code name} is the key the submitted payload uses and must be
 * unique within the form; renaming it changes the contract with any saved draft.
 *
 * <p>{@code variable} is the workflow variable this field writes on submission, as a dotted path such as
 * {@code employee.name}. Null means the field collects a value that goes nowhere, which is occasionally
 * deliberate and always worth flagging in the designer.
 */
public class FormField {

    private String id;
    private FormFieldType type = FormFieldType.TEXT;

    /** Payload key. Unique within the form. */
    private String name;

    private String label;
    private String placeholder;

    /** Help text shown beneath the control. */
    private String description;

    /** Dotted workflow variable path this field writes, for example {@code employee.name}. */
    private String variable;

    /**
     * Declared type of the target variable, checked against the field type when the form is saved.
     *
     * <p>Null means unchecked. Present, it catches a real class of mistake: mapping a checkbox to a numeric
     * variable produces a workflow that fails at the decision node rather than at design time.
     */
    private FormFieldType.DataType variableType;

    /**
     * Value used when the mapped variable has none.
     *
     * <p>May contain {@code ${...}} placeholders, which are resolved against the execution's variables when
     * the task is created, so a form can be prefilled from earlier nodes.
     */
    private Object defaultValue;

    /** Shown but not editable. Used for context the user needs but must not change. */
    private boolean readOnly;

    private FormValidationRule validation = new FormValidationRule();

    /** Options for a dropdown, radio group or multi-select. */
    private List<FormFieldOption> options = new ArrayList<>();

    /**
     * Expression controlling whether the field is shown, evaluated by the engine's safe evaluator.
     *
     * <p>Never JavaScript, and never evaluated in the browser as authority: the same expression decides
     * whether the field is validated on submission, so a hidden field cannot be made required by a client.
     */
    private String visibilityExpression;

    /** Display order within the form. */
    private int order;

    /** Columns the field spans in the form's grid. */
    private int width = 1;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public FormFieldType getType() {
        return type;
    }

    public void setType(FormFieldType type) {
        this.type = type == null ? FormFieldType.TEXT : type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVariable() {
        return variable;
    }

    public void setVariable(String variable) {
        this.variable = variable;
    }

    public FormFieldType.DataType getVariableType() {
        return variableType;
    }

    public void setVariableType(FormFieldType.DataType variableType) {
        this.variableType = variableType;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public FormValidationRule getValidation() {
        return validation;
    }

    public void setValidation(FormValidationRule validation) {
        this.validation = validation == null ? new FormValidationRule() : validation;
    }

    public List<FormFieldOption> getOptions() {
        return options;
    }

    public void setOptions(List<FormFieldOption> options) {
        this.options = options == null ? new ArrayList<>() : new ArrayList<>(options);
    }

    public String getVisibilityExpression() {
        return visibilityExpression;
    }

    public void setVisibilityExpression(String visibilityExpression) {
        this.visibilityExpression = visibilityExpression;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = Math.max(1, width);
    }

    /** @return whether this field produces a value to validate and map */
    public boolean collectsValue() {
        return type != null && type.collectsValue();
    }

    /** @return whether this field writes a workflow variable */
    public boolean isMapped() {
        return variable != null && !variable.isBlank();
    }

    @Override
    public String toString() {
        return "FormField{" + name + " (" + type + ") -> " + variable + "}";
    }

    /**
     * One choice in a dropdown, radio group or multi-select.
     *
     * @param value what is stored and mapped to the variable
     * @param label what the user sees
     */
    public record FormFieldOption(String value, String label) {

        /** @return the label, falling back to the value when none was given */
        public String displayLabel() {
            return label == null || label.isBlank() ? value : label;
        }
    }
}
