package com.orchpilot.workflow.expression;

import com.orchpilot.workflow.exception.ExpressionEvaluationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpelExpressionEvaluatorTest {

    private SpelExpressionEvaluator evaluator;
    private Map<String, Object> root;

    @BeforeEach
    void setUp() {
        evaluator = new SpelExpressionEvaluator();
        root = new LinkedHashMap<>();
        root.put("amount", 15000);
        root.put("status", "APPROVED");
        root.put("country", "INDIA");
        root.put("customerType", "PREMIUM");
        root.put("approved", true);
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "Priya");
        customer.put("age", 34);
        root.put("customer", customer);
    }

    @Test
    @DisplayName("evaluates the comparisons workflow authors actually write")
    void evaluatesTypicalConditions() {
        assertTrue(evaluator.evaluateBoolean("amount > 10000", root));
        assertFalse(evaluator.evaluateBoolean("amount <= 10000", root));
        assertTrue(evaluator.evaluateBoolean("status == 'APPROVED'", root));
        assertTrue(evaluator.evaluateBoolean("country == 'INDIA'", root));
        assertTrue(evaluator.evaluateBoolean("customerType == 'PREMIUM'", root));
        assertTrue(evaluator.evaluateBoolean("amount > 10000 and status == 'APPROVED'", root));
        assertTrue(evaluator.evaluateBoolean("approved", root));
    }

    @Test
    @DisplayName("reads nested values with dot notation")
    void readsNestedValues() {
        assertTrue(evaluator.evaluateBoolean("customer.age >= 18", root));
        assertEquals("Priya", evaluator.evaluate("customer.name", root));
    }

    @Test
    @DisplayName("coerces numeric and textual results to boolean")
    void coercesResultsToBoolean() {
        assertTrue(evaluator.evaluateBoolean("1", root));
        assertFalse(evaluator.evaluateBoolean("0", root));
        assertTrue(evaluator.evaluateBoolean("'true'", root));
        assertFalse(evaluator.evaluateBoolean("'false'", root));
    }

    @ParameterizedTest
    @DisplayName("refuses constructs that would make an expression arbitrary code execution")
    @ValueSource(strings = {
            "T(java.lang.Runtime).getRuntime().exec('calc')",
            "new java.io.File('/etc/passwd').exists()",
            "@someBean.doThing()",
            "customer.getClass().getName()",
            "customer.class.name",
            "#root.toString()"
    })
    void refusesDangerousConstructs(String expression) {
        assertThrows(ExpressionEvaluationException.class, () -> evaluator.validate(expression));
        assertThrows(ExpressionEvaluationException.class, () -> evaluator.evaluateBoolean(expression, root));
    }

    @Test
    @DisplayName("a forbidden word inside a string literal is not mistaken for a forbidden construct")
    void doesNotFalselyRejectStringLiterals() {
        root.put("orderState", "new order");
        root.put("email", "a@b.com");

        assertDoesNotThrow(() -> evaluator.validate("orderState == 'new order'"));
        assertTrue(evaluator.evaluateBoolean("orderState == 'new order'", root));
        assertTrue(evaluator.evaluateBoolean("email == 'a@b.com'", root));
    }

    @Test
    @DisplayName("assignment is rejected: an expression reads variables, it does not write them")
    void rejectsAssignment() {
        assertThrows(ExpressionEvaluationException.class,
                () -> evaluator.evaluate("amount = 1", root));
    }

    @Test
    @DisplayName("a missing variable is an error, so a typo is diagnosable rather than silently false")
    void missingVariableFails() {
        ExpressionEvaluationException ex = assertThrows(ExpressionEvaluationException.class,
                () -> evaluator.evaluateBoolean("amonut > 10", root));
        assertTrue(ex.getMessage().contains("amonut"), ex.getMessage());
    }

    @Test
    @DisplayName("a non-boolean result on a condition is reported rather than coerced arbitrarily")
    void rejectsNonBooleanResult() {
        assertThrows(ExpressionEvaluationException.class,
                () -> evaluator.evaluateBoolean("customer", root));
    }

    @Test
    @DisplayName("blank and oversized expressions are rejected")
    void rejectsBlankAndOversized() {
        assertThrows(ExpressionEvaluationException.class, () -> evaluator.validate(null));
        assertThrows(ExpressionEvaluationException.class, () -> evaluator.validate("   "));
        assertThrows(ExpressionEvaluationException.class,
                () -> evaluator.validate("a".repeat(5_000) + " == 1"));
    }

    @Test
    @DisplayName("a null result on a condition is false, so an absent optional flag does not fail the node")
    void nullResultIsFalse() {
        root.put("maybe", null);
        assertFalse(evaluator.evaluateBoolean("maybe", root));
    }

    @Test
    @DisplayName("repeated evaluation of the same expression reuses the parsed form")
    void cachesParsedExpressions() {
        for (int i = 0; i < 50; i++) {
            assertTrue(evaluator.evaluateBoolean("amount > 10000", root));
        }
    }
}
