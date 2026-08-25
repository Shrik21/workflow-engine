package com.orchpilot.workflow.plugins.gcp;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Risk classification is the contract the AI Agent relies on: exactly Delete is destructive, Get and List are
 * read-only, and every state-change is a modify. The AI tool name each node maps to is derived from its node type,
 * so this also pins the {@code gcp_compute_*} names the specification lists.
 */
class GcpOperationTest {

    @Test
    void onlyDeleteIsDestructive() {
        assertThat(GcpOperation.DELETE.destructive()).isTrue();
        assertThat(GcpOperation.DELETE.risk()).isEqualTo(GcpOperation.Risk.DESTRUCTIVE);
        for (GcpOperation op : GcpOperation.values()) {
            if (op != GcpOperation.DELETE) {
                assertThat(op.destructive()).as("%s should not be destructive", op).isFalse();
            }
        }
    }

    @Test
    void readsAreReadOnlyAndChangesAreModify() {
        assertThat(GcpOperation.GET.risk()).isEqualTo(GcpOperation.Risk.READ_ONLY);
        assertThat(GcpOperation.LIST.risk()).isEqualTo(GcpOperation.Risk.READ_ONLY);
        assertThat(GcpOperation.CREATE.risk()).isEqualTo(GcpOperation.Risk.MODIFY);
        assertThat(GcpOperation.START.risk()).isEqualTo(GcpOperation.Risk.MODIFY);
        assertThat(GcpOperation.STOP.risk()).isEqualTo(GcpOperation.Risk.MODIFY);
    }

    @Test
    void nodeTypesMapToTheSpecifiedAiToolNames() {
        // The AI tool registry derives a tool name from the node type: lower-cased, non-alphanumerics to '_'.
        assertThat(toolName(GcpOperation.CREATE)).isEqualTo("gcp_compute_create_instance");
        assertThat(toolName(GcpOperation.GET)).isEqualTo("gcp_compute_get_instance");
        assertThat(toolName(GcpOperation.DELETE)).isEqualTo("gcp_compute_delete_instance");
        assertThat(GcpOperation.forNodeType("GCP_COMPUTE_LIST_INSTANCES")).isEqualTo(GcpOperation.LIST);
        assertThat(GcpOperation.forNodeType("NOPE")).isNull();
    }

    private static String toolName(GcpOperation op) {
        return op.nodeType().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
    }
}
