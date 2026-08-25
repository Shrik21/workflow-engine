package com.orchpilot.workflow.forms;

import java.util.Map;

/**
 * Turns submitted form data into workflow variables.
 *
 * <p>An interface with one job, kept out of the controller deliberately. The mapping is a security boundary,
 * not a convenience: the server loads the authoritative form version from MongoDB and decides which field
 * writes which variable. A browser sends only values, never mappings, so a caller cannot nominate the
 * variable their input lands in.
 */
public interface FormVariableMapper {

    /**
     * Maps submitted values onto the variable paths the form declares.
     *
     * <p>Only fields present in {@code version} are considered, and only those with a mapped variable. A key
     * in {@code formData} that matches no field is ignored rather than passed through, so a crafted payload
     * cannot inject a variable the form never declared.
     *
     * @param version  the authoritative form version, loaded server-side
     * @param formData the submitted values, keyed by field name
     * @return a nested variable structure, for example {@code {employee: {name: "Vivek"}}}
     */
    Map<String, Object> mapFormDataToVariables(FormVersion version, Map<String, Object> formData);

    /**
     * The same mapping, expressed as dotted paths rather than nested maps.
     *
     * <p>Both shapes are needed, and neither is derivable from the other without guessing. A caller writing into
     * the execution's variable store writes one path at a time — {@code set("employee.name", "…")} — so that a
     * form touching {@code employee.name} does not replace whatever else already lives under {@code employee}.
     * The nested form is what an API response and a test assertion want. Building the nested form from paths is
     * trivial; recovering paths from the nested form means walking it and cannot distinguish a dotted path from a
     * field whose value happens to be a map.
     *
     * @param version  the authoritative form version, loaded server-side
     * @param formData the submitted values, keyed by field name
     * @return variable path to coerced value, for example {@code {"employee.name": "Vivek", "salary": 120000.0}}
     */
    Map<String, Object> mapFormDataToVariablePaths(FormVersion version, Map<String, Object> formData);
}
