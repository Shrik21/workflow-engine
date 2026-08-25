package com.orchpilot.workflow.controller;

import com.orchpilot.workflow.dto.NodeCatalogEntry;
import com.orchpilot.workflow.service.NodeCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * The node catalogue.
 *
 * <p>This is the endpoint that decouples a front end from the plugin release cycle. Built-in and plugin-contributed
 * node types come back in one uniform shape, each with the configuration schema its provider published, so a
 * designer can render a palette entry and a property panel for a node type uploaded minutes ago without a front-end
 * release.
 */
@RestController
@RequestMapping("/api/nodes")
@Tag(name = "Nodes", description = "Discover the node types this engine can execute right now")
public class NodeController {

    private final NodeCatalogService catalogService;

    public NodeController(NodeCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    @Operation(summary = "List every executable node type",
            description = "Returns the four built-in types plus one entry per node type contributed by a loaded "
                    + "plugin. A type that disappears from this list has had its plugin deactivated or unloaded.")
    public List<NodeCatalogEntry> list(
            @Parameter(description = "Filter by palette category, e.g. Communication")
            @RequestParam(required = false) String category) {
        return catalogService.catalogue(category);
    }

    @GetMapping("/categories")
    @Operation(summary = "List the palette categories currently present")
    public Set<String> categories() {
        return catalogService.categories();
    }

    /**
     * The icons loaded plugins ship, keyed by plugin id.
     *
     * <h2>Why a map here rather than a field on each catalogue entry</h2>
     *
     * An icon belongs to a plugin, not to a node type, and a plugin contributes many node types — the GCP
     * Network plugin alone has thirty-two. Inlining the same data URL on every entry would repeat a couple of
     * kilobytes thirty-two times for one plugin's artwork.
     *
     * <h2>Why alongside the catalogue rather than under /api/plugins</h2>
     *
     * Everyone who opens the designer needs these, and the plugin endpoints require {@code PLUGIN_VIEW},
     * which a workflow author need not hold. Access here matches the catalogue's, which is the thing the
     * icons decorate.
     *
     * <p>The values are {@code data:} URLs for an {@code <img src>}. They are not fetched individually
     * because the console authenticates with a bearer token an {@code <img>} cannot send.
     *
     * @return plugin id to data URL, omitting plugins that ship no icon
     */
    @GetMapping("/icons")
    @Operation(summary = "Icons published by loaded plugins, as data URLs keyed by plugin id",
            description = "SVG content is sanitised when the plugin is installed. Intended for an <img src>; "
                    + "an SVG loaded as an image cannot execute script.")
    public java.util.Map<String, String> icons() {
        return catalogService.pluginIcons();
    }

    @GetMapping("/{nodeType}")
    @Operation(summary = "Describe one node type, including its configuration schema")
    public ResponseEntity<NodeCatalogEntry> describe(@PathVariable String nodeType) {
        return catalogService.describe(nodeType)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
