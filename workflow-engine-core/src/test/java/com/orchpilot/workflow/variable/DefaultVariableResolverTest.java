package com.orchpilot.workflow.variable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultVariableResolverTest {

    private DefaultVariableResolver resolver;
    private VariableStore store;

    @BeforeEach
    void setUp() {
        resolver = new DefaultVariableResolver();
        store = VariableStore.create();
        store.seed(VariableScope.INPUT, Map.of("employeeId", "E-42", "recipientEmail", "user@example.com"));
        store.seed(VariableScope.WORKFLOW, Map.of("amount", 15000, "subject", "Approval Required",
                "approved", true));
        store.putNodeOutputs("call-1", Map.of("statusCode", 201));
    }

    @Test
    @DisplayName("a whole-string placeholder keeps the referenced value's type")
    void wholeStringPlaceholderPreservesType() {
        Object resolved = resolver.resolve("${amount}", store);

        assertInstanceOf(Integer.class, resolved, "a number must not arrive at a plugin as a string");
        assertEquals(15000, resolved);
        assertInstanceOf(Boolean.class, resolver.resolve("${approved}", store));
    }

    @Test
    @DisplayName("a placeholder inside surrounding text renders as text")
    void embeddedPlaceholderRendersAsText() {
        assertEquals("Order total: 15000 INR",
                resolver.resolveText("Order total: ${amount} INR", store));
        assertEquals("E-42/15000", resolver.resolveText("${employeeId}/${amount}", store));
    }

    @Test
    @DisplayName("scope-qualified and bare paths both resolve")
    void resolvesQualifiedAndBarePaths() {
        assertEquals("E-42", resolver.resolve("${input.employeeId}", store));
        assertEquals("E-42", resolver.resolve("${employeeId}", store));
        assertEquals(201, resolver.resolve("${node.call-1.statusCode}", store));
    }

    @Test
    @DisplayName("an unresolved placeholder is left literal, so the mistake is visible downstream")
    void unresolvedPlaceholderStaysLiteral() {
        assertEquals("${missing}", resolver.resolve("${missing}", store));
        assertEquals("to: ${missing}", resolver.resolveText("to: ${missing}", store));
    }

    @Test
    @DisplayName("a doubled dollar escapes a literal placeholder")
    void doubledDollarEscapes() {
        assertEquals("${amount}", resolver.resolveText("$${amount}", store));
        assertEquals("cost ${x} and 15000", resolver.resolveText("cost $${x} and ${amount}", store));
    }

    @Test
    @DisplayName("resolution walks the whole configuration tree, including keys")
    void resolvesNestedConfiguration() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("to", "${recipientEmail}");
        configuration.put("subject", "${subject}");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employee", "${employeeId}");
        body.put("total", "${amount}");
        configuration.put("body", body);
        configuration.put("tags", List.of("${employeeId}", "static"));
        configuration.put("${employeeId}", "keys are resolved too");

        Map<String, Object> resolved = resolver.resolveConfiguration(configuration, store);

        assertEquals("user@example.com", resolved.get("to"));
        assertEquals("Approval Required", resolved.get("subject"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resolvedBody = (Map<String, Object>) resolved.get("body");
        assertEquals("E-42", resolvedBody.get("employee"));
        assertEquals(15000, resolvedBody.get("total"));
        assertEquals(List.of("E-42", "static"), resolved.get("tags"));
        assertEquals("keys are resolved too", resolved.get("E-42"));
    }

    @Test
    @DisplayName("the input configuration is never mutated")
    void doesNotMutateInput() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("to", "${recipientEmail}");

        resolver.resolveConfiguration(configuration, store);

        assertEquals("${recipientEmail}", configuration.get("to"));
    }

    @Test
    @DisplayName("a resolved value containing a placeholder is not expanded again")
    void doesNotRecursivelyExpand() {
        store.seed(VariableScope.WORKFLOW, Map.of("indirect", "${amount}"));

        assertEquals("${amount}", resolver.resolve("${indirect}", store));
    }

    @Test
    @DisplayName("strings with no placeholder are returned as-is")
    void passesThroughPlainValues() {
        String plain = "nothing to resolve";
        assertSame(plain, resolver.resolve(plain, store));
        assertNull(resolver.resolveText(null, store));
        assertEquals(42, resolver.resolve(42, store));
        assertNull(resolver.resolve(null, store));
    }

    @Test
    @DisplayName("an unterminated placeholder is passed through rather than throwing")
    void toleratesUnterminatedPlaceholder() {
        assertEquals("prefix ${unclosed", resolver.resolveText("prefix ${unclosed", store));
    }
}
