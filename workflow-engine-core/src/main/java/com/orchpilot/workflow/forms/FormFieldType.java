package com.orchpilot.workflow.forms;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The field types a form may contain, with the metadata that drives everything else.
 *
 * <p>A registry rather than scattered conditionals. Each constant declares its category, its label, the
 * variable data types it may map to, whether it carries options, and whether it collects a value at all.
 * Validation, the variable-type check, the designer palette and the runtime renderer all read those
 * declarations, so adding {@code CURRENCY} or {@code RATING} later means one constant here rather than a
 * search for every {@code if (type == ...)} in the code base.
 */
public enum FormFieldType {

    // ------------------------------------------------------------------- basic
    TEXT(Category.BASIC, "Text", DataType.STRING),
    TEXTAREA(Category.BASIC, "Text area", DataType.STRING),
    NUMBER(Category.BASIC, "Number", DataType.INTEGER, DataType.LONG, DataType.DOUBLE),
    EMAIL(Category.BASIC, "Email", DataType.STRING),
    PASSWORD(Category.BASIC, "Password", DataType.STRING),
    PHONE(Category.BASIC, "Phone", DataType.STRING),
    URL(Category.BASIC, "URL", DataType.STRING),

    // -------------------------------------------------------------------- date
    DATE(Category.DATE, "Date", DataType.DATE),
    DATETIME(Category.DATE, "Date and time", DataType.DATETIME),
    TIME(Category.DATE, "Time", DataType.STRING),

    // ------------------------------------------------------------------ choice
    DROPDOWN(Category.CHOICE, "Dropdown", true, DataType.STRING),
    MULTI_SELECT(Category.CHOICE, "Multi-select", true, DataType.LIST),
    RADIO(Category.CHOICE, "Radio group", true, DataType.STRING),
    CHECKBOX(Category.CHOICE, "Checkbox", DataType.BOOLEAN),
    CHECKBOX_GROUP(Category.CHOICE, "Checkbox group", true, DataType.LIST),

    // -------------------------------------------------------------------- file
    FILE(Category.FILE, "File upload", DataType.STRING),
    IMAGE(Category.FILE, "Image upload", DataType.STRING),

    // ------------------------------------------------------------------ layout
    /** Carries a value but is never shown; used to pass a workflow variable through a form. */
    HIDDEN(Category.LAYOUT, "Hidden", DataType.STRING),

    /** Presentational only. Collects nothing, so it is never validated and never mapped. */
    LABEL(Category.LAYOUT, "Label", false, false),

    /** Presentational only: a heading that groups the fields after it. */
    SECTION(Category.LAYOUT, "Section", false, false);

    /** Palette grouping. */
    public enum Category {
        BASIC("Basic"),
        DATE("Date and time"),
        CHOICE("Choice"),
        FILE("File"),
        LAYOUT("Layout");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Variable data types a field can be mapped to.
     *
     * <p>Deliberately coarse. The point is to catch a genuine mistake, such as mapping a checkbox to a
     * numeric variable, not to model a type system.
     */
    public enum DataType {
        STRING, INTEGER, LONG, DOUBLE, BOOLEAN, DATE, DATETIME, LIST, OBJECT
    }

    private final Category category;
    private final String label;
    private final boolean collectsValue;
    private final boolean hasOptions;
    private final Set<DataType> compatibleTypes;

    FormFieldType(Category category, String label, DataType... compatible) {
        this(category, label, false, true, compatible);
    }

    FormFieldType(Category category, String label, boolean hasOptions, DataType... compatible) {
        this(category, label, hasOptions, true, compatible);
    }

    FormFieldType(Category category, String label, boolean hasOptions, boolean collectsValue,
                  DataType... compatible) {
        this.category = category;
        this.label = label;
        this.hasOptions = hasOptions;
        this.collectsValue = collectsValue;
        this.compatibleTypes = compatible.length == 0
                ? EnumSet.noneOf(DataType.class)
                : EnumSet.copyOf(Arrays.asList(compatible));
    }

    public Category category() {
        return category;
    }

    public String label() {
        return label;
    }

    /** @return whether the field produces a value to validate and map */
    public boolean collectsValue() {
        return collectsValue;
    }

    /** @return whether the field requires a list of options, such as a dropdown */
    public boolean hasOptions() {
        return hasOptions;
    }

    /** @return the variable data types this field may be mapped to */
    public Set<DataType> compatibleTypes() {
        return compatibleTypes;
    }

    /**
     * @param dataType the declared variable type
     * @return whether this field may be mapped to it
     */
    public boolean isCompatibleWith(DataType dataType) {
        return dataType == null || compatibleTypes.contains(dataType);
    }

    /** @return the natural type a value from this field should be coerced to */
    public DataType primaryType() {
        return compatibleTypes.isEmpty() ? DataType.STRING : compatibleTypes.iterator().next();
    }

    /**
     * Parses a type name defensively, so a value written by a newer version does not break a read.
     *
     * @param value candidate name
     * @return the type, or empty when unknown
     */
    public static Optional<FormFieldType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.name().equals(normalised)).findFirst();
    }

    /** @return the palette, grouped by category, for the designer to render from */
    public static Map<String, List<FormFieldType>> byCategory() {
        Map<String, List<FormFieldType>> grouped = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            grouped.put(category.label(),
                    Arrays.stream(values()).filter(type -> type.category() == category).toList());
        }
        return grouped;
    }
}
