package com.orchpilot.workflow.sdk.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeExecutionResultTest {

    @Test
    @DisplayName("success carries outputs and no error")
    void successCarriesOutputs() {
        NodeExecutionResult result = NodeExecutionResult.success(Map.of("id", 7));

        assertTrue(result.isSuccess());
        assertFalse(result.isFailed());
        assertEquals(7, result.outputs().get("id"));
        assertNull(result.errorCode());
    }

    @Test
    @DisplayName("outputs are unmodifiable so a caller cannot mutate a returned result")
    void outputsAreUnmodifiable() {
        NodeExecutionResult result = NodeExecutionResult.success(Map.of("a", 1));

        assertThrows(UnsupportedOperationException.class, () -> result.outputs().put("b", 2));
    }

    @Test
    @DisplayName("the outputs map is copied, so later changes to the source do not leak in")
    void outputsAreCopied() {
        Map<String, Object> source = new HashMap<>();
        source.put("a", 1);

        NodeExecutionResult result = NodeExecutionResult.success(source);
        source.put("b", 2);

        assertEquals(1, result.outputs().size());
    }

    @Test
    @DisplayName("branch results name the outgoing port")
    void branchNamesThePort() {
        NodeExecutionResult result = NodeExecutionResult.branch("approved", Map.of("amount", 15000));

        assertTrue(result.isSuccess());
        assertEquals("approved", result.selectedBranch());
    }

    @Test
    @DisplayName("failures default to non-retryable")
    void failuresDefaultToNonRetryable() {
        NodeExecutionResult result = NodeExecutionResult.failure("API_TIMEOUT", "timed out");

        assertTrue(result.isFailed());
        assertEquals("API_TIMEOUT", result.errorCode());
        assertFalse(result.retryable(), "a failure must opt in to being retried");
    }

    @Test
    @DisplayName("waiting results carry a reason and are neither success nor failure")
    void waitingCarriesReason() {
        NodeExecutionResult result = NodeExecutionResult.waiting("awaiting approval",
                Map.of("formId", "approval"));

        assertTrue(result.isWaiting());
        assertFalse(result.isSuccess());
        assertFalse(result.isFailed());
        assertEquals("awaiting approval", result.waitReason());
        assertEquals("approval", result.outputs().get("formId"));
    }

    @Test
    @DisplayName("withOutputs merges without mutating the original")
    void withOutputsMerges() {
        NodeExecutionResult original = NodeExecutionResult.branch("yes", Map.of("a", 1));

        NodeExecutionResult merged = original.withOutputs(Map.of("b", 2));

        assertEquals(1, original.outputs().size());
        assertEquals(2, merged.outputs().size());
        assertEquals("yes", merged.selectedBranch(), "merging must preserve the selected branch");
    }

    @Test
    @DisplayName("null outputs are treated as empty rather than throwing")
    void nullOutputsAreEmpty() {
        assertTrue(NodeExecutionResult.success(null).outputs().isEmpty());
        assertTrue(NodeExecutionResult.success().outputs().isEmpty());
    }

    @Test
    @DisplayName("equality covers status, outputs, branch and error")
    void equalityIsValueBased() {
        assertEquals(NodeExecutionResult.success(Map.of("a", 1)), NodeExecutionResult.success(Map.of("a", 1)));
        assertNotEquals(NodeExecutionResult.success(Map.of("a", 1)), NodeExecutionResult.success(Map.of("a", 2)));
        assertNotEquals(NodeExecutionResult.failure("A", "m"), NodeExecutionResult.failure("B", "m"));
    }

    @Test
    @DisplayName("toString does not include output values, only their keys")
    void toStringOmitsValues() {
        String text = NodeExecutionResult.success(Map.of("token", "super-secret-value")).toString();

        assertTrue(text.contains("token"));
        assertFalse(text.contains("super-secret-value"), "values could be credentials");
    }
}
