package com.orchpilot.pluginserver.model;

import com.orchpilot.workflow.sdk.manifest.PluginManifest;

import java.util.List;
import java.util.Map;

/**
 * One node type a plugin version contributes, as stored in the registry.
 *
 * <p>Everything a designer needs to draw the node and edit its configuration, held here so a workflow service can
 * render a plugin's nodes from the catalogue before the plugin is installed anywhere. Without that, a marketplace
 * could only list names, and deciding whether to install something would mean installing it.
 *
 * @param nodeType            stable identifier a workflow node references
 * @param displayName         label in the palette
 * @param description         one line of help
 * @param category            palette grouping
 * @param icon                icon name
 * @param configurationSchema JSON schema the property panel renders from
 * @param inputPorts          named inputs, empty for the usual single input
 * @param outputPorts         named outputs, such as decision branches
 */
public record PluginNode(
        String nodeType,
        String displayName,
        String description,
        String category,
        String icon,
        Map<String, Object> configurationSchema,
        List<String> inputPorts,
        List<String> outputPorts) {

    public PluginNode {
        configurationSchema = configurationSchema == null ? Map.of() : Map.copyOf(configurationSchema);
        inputPorts = inputPorts == null ? List.of() : List.copyOf(inputPorts);
        outputPorts = outputPorts == null ? List.of() : List.copyOf(outputPorts);
    }

    /**
     * @param node the declared node from a plugin's manifest
     * @return the stored form
     */
    public static PluginNode from(PluginManifest.ManifestNode node) {
        return new PluginNode(node.nodeType(), node.label(), node.description(),
                node.category() == null ? "Other" : node.category(),
                node.icon(), node.configurationSchema(), node.inputPorts(), node.outputPorts());
    }
}
