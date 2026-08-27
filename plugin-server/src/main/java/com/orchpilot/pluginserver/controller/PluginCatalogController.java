package com.orchpilot.pluginserver.controller;

import com.orchpilot.pluginserver.dto.PluginCatalogEntry;
import com.orchpilot.pluginserver.service.PluginCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The catalogue a workflow service syncs from.
 *
 * <p>The most important endpoint in this service, and the narrowest: one verb, one payload, no parameters. A
 * client asks what exists and gets everything it needs to decide what to install, which version an existing
 * workflow's pin resolves to, and how to draw a node it has never seen.
 *
 * <p>Returns a bare array rather than an envelope, because that is the contract the platform's design settled on.
 * The metadata a client wants alongside it, when the snapshot was built and how many plugins are in it, is in
 * headers instead, where it does not change the shape of the thing being parsed.
 */
@RestController
@RequestMapping("/api/plugin-catalog")
@Tag(name = "Plugin catalogue", description = "What a workflow service syncs from")
public class PluginCatalogController {

    private final PluginCatalogService catalog;

    public PluginCatalogController(PluginCatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * Every available plugin, with its published versions and the node metadata of its latest.
     *
     * @param ifNoneMatch the client's last known ETag
     * @return the catalogue, or 304 when the client already has this snapshot
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PLUGIN_READ')")
    @Operation(summary = "The plugin catalogue",
            description = """
                    Every plugin that has at least one published version, with a compact row per version and the \
                    node metadata of the latest.

                    Send the previous ETag as If-None-Match. A steady state then costs a 304 rather than the whole \
                    catalogue, which matters because every workflow service polls this on an interval.

                    Versions listed here are ACTIVE or DEPRECATED. A deprecated version is still installable, \
                    because a workflow pinned to it has to keep working; it simply stops being what 'latest' \
                    resolves to. Revoked versions are absent and their download is refused.""")
    @ApiResponse(responseCode = "200", description = "The catalogue")
    @ApiResponse(responseCode = "304", description = "Unchanged since the supplied ETag")
    public ResponseEntity<List<PluginCatalogEntry>> catalog(
            @Parameter(description = "The ETag from a previous response")
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        PluginCatalogService.Catalog snapshot = catalog.catalog();

        if (snapshot.matches(ifNoneMatch)) {
            // The ETag is repeated on the 304, as the specification requires, so an intermediary can cache it.
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(snapshot.etag())
                    .header("X-Catalog-Generated-At", snapshot.generatedAt().toString())
                    .build();
        }

        return ResponseEntity.ok()
                .eTag(snapshot.etag())
                /*
                 * no-cache, not no-store: a client should keep the payload and revalidate rather than discard it.
                 * That is what makes the 304 path work, and what lets a workflow service serve its last known
                 * catalogue while this service is unreachable.
                 */
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header("X-Catalog-Generated-At", snapshot.generatedAt().toString())
                .header("X-Catalog-Plugin-Count", String.valueOf(snapshot.entries().size()))
                .body(snapshot.entries());
    }
}
