package com.orchpilot.workflow.ai.node;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structured-output check, whose precision is what makes the repair re-prompt useful: it must name every way
 * the output misses the schema — missing field, wrong type, value outside an enum — so the executor can hand the
 * model an actionable correction, and it must accept a genuinely valid object without complaint.
 */
class StructuredOutputValidatorTest {

    private final Map<String, Object> schema = Map.of(
            "type", "object",
            "properties", Map.of(
                    "category", Map.of("type", "string", "enum", List.of("refund", "question", "complaint")),
                    "urgency", Map.of("type", "integer")),
            "required", List.of("category", "urgency"));

    @Test
    void acceptsAValidObject() {
        assertThat(StructuredOutputValidator.validate(
                Map.of("category", "refund", "urgency", 3), schema)).isEmpty();
    }

    @Test
    void flagsNonObjectOutput() {
        assertThat(StructuredOutputValidator.validate("just text", schema))
                .containsExactly("the output is not a JSON object");
    }

    @Test
    void flagsAMissingRequiredProperty() {
        assertThat(StructuredOutputValidator.validate(Map.of("category", "refund"), schema))
                .anyMatch(p -> p.contains("missing required property 'urgency'"));
    }

    @Test
    void flagsAWrongType() {
        assertThat(StructuredOutputValidator.validate(
                Map.of("category", "refund", "urgency", "high"), schema))
                .anyMatch(p -> p.contains("property 'urgency' should be integer"));
    }

    @Test
    void flagsAValueOutsideTheEnum() {
        assertThat(StructuredOutputValidator.validate(
                Map.of("category", "banana", "urgency", 1), schema))
                .anyMatch(p -> p.contains("property 'category' must be one of"));
    }

    @Test
    void anEmptySchemaAcceptsAnyObject() {
        assertThat(StructuredOutputValidator.validate(Map.of("anything", 1), Map.of())).isEmpty();
    }
}
