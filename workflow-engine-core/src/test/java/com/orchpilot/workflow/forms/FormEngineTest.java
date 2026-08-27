package com.orchpilot.workflow.forms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two pieces of the form engine that carry real logic: validation and variable mapping.
 *
 * <p>Both are security boundaries rather than conveniences. Validation is the only thing standing between a
 * hand-crafted payload and the workflow's variables, and the mapper is what decides which variable a value
 * lands in, using the server's copy of the form rather than anything the browser sent.
 */
class FormEngineTest {

    private FormValidationService validation;
    private FormVariableMapper mapper;

    @BeforeEach
    void setUp() {
        validation = new FormValidationService();
        mapper = new DefaultFormVariableMapper();
    }

    private static FormField field(FormFieldType type, String name, String variable) {
        FormField field = new FormField();
        field.setId(name + "-id");
        field.setType(type);
        field.setName(name);
        field.setLabel(name);
        field.setVariable(variable);
        return field;
    }

    private static FormVersion version(FormField... fields) {
        FormVersion version = new FormVersion();
        version.setFormDefinitionId("form-1");
        version.setVersion(1);
        version.setFields(List.of(fields));
        return version;
    }

    @Nested
    @DisplayName("Variable mapping")
    class Mapping {

        @Test
        @DisplayName("builds the nested structure the specification describes")
        void mapsToNestedVariables() {
            FormVersion form = version(
                    field(FormFieldType.TEXT, "employeeName", "employee.name"),
                    field(FormFieldType.EMAIL, "employeeEmail", "employee.email"),
                    field(FormFieldType.TEXTAREA, "comments", "approval.comments"));

            Map<String, Object> variables = mapper.mapFormDataToVariables(form, Map.of(
                    "employeeName", "Vivek",
                    "employeeEmail", "vivek@example.com",
                    "comments", "Approved"));

            assertThat(variables).containsOnlyKeys("employee", "approval");
            @SuppressWarnings("unchecked")
            Map<String, Object> employee = (Map<String, Object>) variables.get("employee");
            assertThat(employee).containsEntry("name", "Vivek").containsEntry("email", "vivek@example.com");
            @SuppressWarnings("unchecked")
            Map<String, Object> approval = (Map<String, Object>) variables.get("approval");
            assertThat(approval).containsEntry("comments", "Approved");
        }

        @Test
        @DisplayName("handles a deeply nested path")
        void mapsDeepPaths() {
            FormVersion form = version(field(FormFieldType.TEXT, "city", "customer.address.city"));

            Map<String, Object> variables = mapper.mapFormDataToVariables(form, Map.of("city", "Pune"));

            assertThat(variables).containsKey("customer");
            assertThat(variables.toString()).contains("Pune");
        }

        @Test
        @DisplayName("coerces a numeric field so a later decision compares numbers")
        void coercesNumbers() {
            FormField salary = field(FormFieldType.NUMBER, "salary", "employee.salary");
            salary.setVariableType(FormFieldType.DataType.DOUBLE);

            Map<String, Object> variables = mapper.mapFormDataToVariables(
                    version(salary), Map.of("salary", "120000"));

            @SuppressWarnings("unchecked")
            Map<String, Object> employee = (Map<String, Object>) variables.get("employee");
            // The property that matters: a decision node evaluating salary > 100000 must not compare a
            // string to a number, which would take the wrong branch.
            assertThat(employee.get("salary")).isInstanceOf(Double.class).isEqualTo(120000.0);
        }

        @Test
        @DisplayName("coerces a checkbox to a boolean")
        void coercesBooleans() {
            FormField approved = field(FormFieldType.CHECKBOX, "approved", "approval.approved");

            assertThat(nested(mapper.mapFormDataToVariables(version(approved), Map.of("approved", "true")),
                    "approval", "approved")).isEqualTo(true);
            assertThat(nested(mapper.mapFormDataToVariables(version(approved), Map.of("approved", false)),
                    "approval", "approved")).isEqualTo(false);
        }

        @Test
        @DisplayName("ignores a key the form does not declare")
        void ignoresUnknownKeys() {
            FormVersion form = version(field(FormFieldType.TEXT, "employeeName", "employee.name"));

            Map<String, Object> variables = mapper.mapFormDataToVariables(form, Map.of(
                    "employeeName", "Vivek",
                    // A crafted payload trying to write a variable the form never declared.
                    "isAdmin", "true"));

            assertThat(variables).containsOnlyKeys("employee");
            assertThat(variables.toString()).doesNotContain("isAdmin");
        }

        @Test
        @DisplayName("ignores an unmapped field, so its value goes nowhere")
        void ignoresUnmappedFields() {
            FormVersion form = version(field(FormFieldType.TEXT, "scratch", null));
            assertThat(mapper.mapFormDataToVariables(form, Map.of("scratch", "ignored"))).isEmpty();
        }

        @Test
        @DisplayName("ignores presentational fields")
        void ignoresLayoutFields() {
            FormVersion form = version(field(FormFieldType.SECTION, "heading", "some.variable"));
            assertThat(mapper.mapFormDataToVariables(form, Map.of("heading", "x"))).isEmpty();
        }

        @SuppressWarnings("unchecked")
        private static Object nested(Map<String, Object> variables, String outer, String inner) {
            return ((Map<String, Object>) variables.get(outer)).get(inner);
        }
    }

    @Nested
    @DisplayName("Server-side validation")
    class Validation {

        @Test
        @DisplayName("reports a missing required field")
        void enforcesRequired() {
            FormField name = field(FormFieldType.TEXT, "employeeName", "employee.name");
            name.getValidation().setRequired(true);

            assertThat(validation.validate(version(name), Map.of())).containsKey("employeeName");
            assertThat(validation.validate(version(name), Map.of("employeeName", "   ")))
                    .containsKey("employeeName");
            assertThat(validation.validate(version(name), Map.of("employeeName", "Vivek"))).isEmpty();
        }

        @Test
        @DisplayName("enforces length, range and pattern")
        void enforcesConstraints() {
            FormField code = field(FormFieldType.TEXT, "code", "order.code");
            code.getValidation().setMinLength(3);
            code.getValidation().setMaxLength(5);
            code.getValidation().setPattern("^[A-Z]+$");
            code.getValidation().setPatternMessage("Upper-case letters only");

            assertThat(validation.validate(version(code), Map.of("code", "AB"))).containsKey("code");
            assertThat(validation.validate(version(code), Map.of("code", "ABCDEF"))).containsKey("code");
            assertThat(validation.validate(version(code), Map.of("code", "abc")).get("code"))
                    .contains("Upper-case letters only");
            assertThat(validation.validate(version(code), Map.of("code", "ABC"))).isEmpty();

            FormField amount = field(FormFieldType.NUMBER, "amount", "order.amount");
            amount.getValidation().setMin(10.0);
            amount.getValidation().setMax(100.0);
            assertThat(validation.validate(version(amount), Map.of("amount", "5"))).containsKey("amount");
            assertThat(validation.validate(version(amount), Map.of("amount", "500"))).containsKey("amount");
            assertThat(validation.validate(version(amount), Map.of("amount", "not a number")))
                    .containsKey("amount");
            assertThat(validation.validate(version(amount), Map.of("amount", "50"))).isEmpty();
        }

        @Test
        @DisplayName("checks email and URL shape")
        void enforcesFormats() {
            FormField email = field(FormFieldType.EMAIL, "email", "employee.email");
            assertThat(validation.validate(version(email), Map.of("email", "not-an-email")))
                    .containsKey("email");
            assertThat(validation.validate(version(email), Map.of("email", "vivek@example.com"))).isEmpty();

            FormField url = field(FormFieldType.URL, "site", "company.site");
            assertThat(validation.validate(version(url), Map.of("site", "example.com"))).containsKey("site");
            assertThat(validation.validate(version(url), Map.of("site", "https://example.com"))).isEmpty();
        }

        @Test
        @DisplayName("refuses a dropdown value that is not an offered option")
        void enforcesOptions() {
            FormField department = field(FormFieldType.DROPDOWN, "department", "employee.department");
            department.setOptions(List.of(
                    new FormField.FormFieldOption("ENG", "Engineering"),
                    new FormField.FormFieldOption("FIN", "Finance")));

            // A real control, not tidiness: a later decision branching on this value must only ever see a
            // value the designer defined.
            assertThat(validation.validate(version(department), Map.of("department", "EXEC")))
                    .containsKey("department");
            assertThat(validation.validate(version(department), Map.of("department", "ENG"))).isEmpty();
        }

        @Test
        @DisplayName("reports a key that matches no field")
        void reportsUnknownKeys() {
            FormVersion form = version(field(FormFieldType.TEXT, "employeeName", "employee.name"));
            assertThat(validation.validate(form, Map.of("employeeName", "Vivek", "injected", "x")))
                    .containsKey("injected");
        }

        @Test
        @DisplayName("ignores whatever arrives for a read-only field")
        void ignoresReadOnlyFields() {
            FormField name = field(FormFieldType.TEXT, "employeeName", "employee.name");
            name.setReadOnly(true);
            name.getValidation().setRequired(true);

            // Its value comes from the workflow, not the user, so a missing one is not their problem.
            assertThat(validation.validate(version(name), Map.of())).isEmpty();
        }

        @Test
        @DisplayName("reports a broken validation pattern rather than silently enforcing nothing")
        void reportsBrokenPattern() {
            FormField code = field(FormFieldType.TEXT, "code", "order.code");
            code.getValidation().setPattern("([unclosed");

            assertThat(validation.validate(version(code), Map.of("code", "anything")).get("code"))
                    .anyMatch(message -> message.contains("invalid validation pattern"));
        }
    }

    @Nested
    @DisplayName("Publish validation")
    class PublishRules {

        private FormDefinition form;

        @BeforeEach
        void setUp() {
            form = new FormDefinition();
            form.setId("form-1");
            form.setName("Employee Approval");
        }

        /** Uses the service's rules without needing repositories, which the checks do not touch. */
        private List<String> errors() {
            return new FormDefinitionService(null, null, null).validateForPublish(form);
        }

        @Test
        @DisplayName("accepts a well-formed form")
        void acceptsValidForm() {
            FormField name = field(FormFieldType.TEXT, "employeeName", "employee.name");
            name.setVariableType(FormFieldType.DataType.STRING);
            form.setFields(List.of(name));

            assertThat(errors()).isEmpty();
        }

        @Test
        @DisplayName("refuses a duplicate field name")
        void refusesDuplicateNames() {
            form.setFields(List.of(
                    field(FormFieldType.TEXT, "name", "a.b"),
                    field(FormFieldType.EMAIL, "name", "c.d")));

            // Otherwise the second silently overwrites the first on submission.
            assertThat(errors()).anyMatch(error -> error.contains("used more than once"));
        }

        @Test
        @DisplayName("refuses a field name that cannot be a payload key")
        void refusesMalformedNames() {
            form.setFields(List.of(field(FormFieldType.TEXT, "employee name!", "a.b")));
            assertThat(errors()).anyMatch(error -> error.contains("must start with a letter"));
        }

        @Test
        @DisplayName("refuses a choice field with no options")
        void refusesEmptyOptions() {
            form.setFields(List.of(field(FormFieldType.DROPDOWN, "department", "employee.department")));
            assertThat(errors()).anyMatch(error -> error.contains("has no options"));
        }

        @Test
        @DisplayName("refuses an unmapped field, whose value would be discarded")
        void refusesUnmappedField() {
            form.setFields(List.of(field(FormFieldType.TEXT, "employeeName", null)));
            assertThat(errors()).anyMatch(error -> error.contains("not mapped to a workflow variable"));
        }

        @Test
        @DisplayName("refuses a field type incompatible with its variable type")
        void refusesIncompatibleTypes() {
            FormField approved = field(FormFieldType.CHECKBOX, "approved", "approval.approved");
            approved.setVariableType(FormFieldType.DataType.DOUBLE);
            form.setFields(List.of(approved));

            // The check the specification asks for: a checkbox cannot write a numeric variable.
            assertThat(errors()).anyMatch(error -> error.contains("cannot be mapped to a DOUBLE variable"));
        }

        @Test
        @DisplayName("refuses a read-only required field, which could never be satisfied")
        void refusesUnsatisfiableField() {
            FormField name = field(FormFieldType.TEXT, "employeeName", "employee.name");
            name.setReadOnly(true);
            name.getValidation().setRequired(true);
            form.setFields(List.of(name));

            assertThat(errors()).anyMatch(error -> error.contains("read-only and required"));
        }

        @Test
        @DisplayName("refuses a form with nothing to collect")
        void refusesEmptyForm() {
            form.setFields(List.of(field(FormFieldType.SECTION, "heading", null)));
            assertThat(errors()).anyMatch(error -> error.contains("no fields that collect a value"));
        }
    }
}
