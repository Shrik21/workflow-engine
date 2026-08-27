package com.orchpilot.workflow.portability;

import com.orchpilot.workflow.model.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The strict, gadget-safe codec: a package round-trips, a malformed body is a schema error rather than a
 * crash, and a payload naming a Java type does not cause that type to be instantiated.
 */
class PackageCodecTest {

    private final PackageCodec codec = new PackageCodec();

    @Test
    @DisplayName("a package serialises and deserialises back to an equal shape")
    void roundTrips() {
        WorkflowPackage pkg = new WorkflowPackage();
        pkg.setName("Invoice approval");
        pkg.setExportedBy("alice");
        pkg.setExportedAt(Instant.parse("2026-01-01T00:00:00Z"));
        pkg.setSourceWorkflowId("wf-1");
        WorkflowNode node = new WorkflowNode();
        node.setId("start");
        node.setType("START");
        pkg.setNodes(List.of(node));
        pkg.setPluginDependencies(List.of(new WorkflowPackage.PluginDependency("email", "1.0.1")));

        WorkflowPackage read = codec.deserialize(codec.serialize(pkg));

        assertThat(read.getName()).isEqualTo("Invoice approval");
        assertThat(read.getExportedBy()).isEqualTo("alice");
        assertThat(read.getExportedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(read.getNodes()).singleElement()
                .satisfies(n -> assertThat(n.getId()).isEqualTo("start"));
        assertThat(read.getPluginDependencies()).singleElement()
                .satisfies(d -> assertThat(d.getPluginId()).isEqualTo("email"));
    }

    @Test
    @DisplayName("bytes that are not a package are a schema error, not a crash")
    void rejectsMalformed() {
        assertThatThrownBy(() -> codec.deserialize("not json at all".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PackageIntegrityException.class);
    }

    @Test
    @DisplayName("a payload naming a Java type via @class does not instantiate it")
    void ignoresPolymorphicTypeHints() {
        // With default typing off, an "@class" property is just an unknown field on a Map value: ignored, never
        // resolved to a class or constructed. This is the deserialization-gadget defence, asserted directly.
        String hostile = "{\"name\":\"x\",\"metadata\":{\"@class\":"
                + "\"java.lang.Runtime\",\"payload\":\"whatever\"}}";

        WorkflowPackage read = codec.deserialize(hostile.getBytes(StandardCharsets.UTF_8));

        assertThat(read.getName()).isEqualTo("x");
        // The @class value survives only as inert string data inside a plain map — no type was loaded.
        assertThat(read.getMetadata()).containsEntry("@class", "java.lang.Runtime");
        assertThat(read.getMetadata().get("@class")).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("an empty document is rejected")
    void rejectsEmpty() {
        assertThatThrownBy(() -> codec.deserialize("null".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PackageIntegrityException.class);
    }
}
