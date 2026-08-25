package com.orchpilot.pluginserver.controller;

import com.orchpilot.pluginserver.model.PluginVersion;
import com.orchpilot.pluginserver.service.PluginVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handing out archive bytes.
 *
 * <h2>Streamed, not buffered</h2>
 *
 * <p>The body is a {@link Resource} over the GridFS stream, so Spring copies it to the response in chunks and the
 * archive never exists as a byte array in this service's heap. With a 50MB plugin and a dozen workflow services
 * installing at once, the buffered version of this endpoint needs most of a gigabyte to do the same work.
 *
 * <h2>The ETag is the checksum</h2>
 *
 * <p>Which is exactly what a caching client should key on, and what the caller is about to verify anyway. A
 * conditional request that comes back 304 means the archive the client already has is the archive the registry
 * holds, with no bytes transferred and no second checksum computation.
 */
@RestController
@RequestMapping("/api/plugins/{pluginId}/versions/{version}")
@Tag(name = "Plugin download", description = "Archive distribution")
public class PluginDownloadController {

    private final PluginVersionService versions;

    public PluginDownloadController(PluginVersionService versions) {
        this.versions = versions;
    }

    /**
     * Streams a version's archive.
     *
     * @param pluginId the plugin
     * @param version  the version
     * @return the archive, as {@code application/java-archive}
     */
    @GetMapping("/download")
    @PreAuthorize("hasAuthority('PLUGIN_DOWNLOAD')")
    @Operation(summary = "Download a version's archive",
            description = """
                    Streams the bytes. The ETag is the archive's SHA-256, which is also the value the caller must \
                    reproduce before loading it: a download whose digest does not match this must not reach a \
                    class loader.

                    Refused with 409 for a revoked version, and for a draft that has not been published.""")
    @ApiResponse(responseCode = "200", description = "The archive")
    @ApiResponse(responseCode = "409", description = "Revoked, or not published")
    @ApiResponse(responseCode = "410", description = "The record exists but its archive is missing")
    public ResponseEntity<Resource> download(@PathVariable String pluginId, @PathVariable String version) {
        PluginVersion record = versions.require(pluginId, version);
        var resource = versions.openArchive(pluginId, version);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/java-archive"))
                .contentLength(record.getFileSize())
                // Quoted per the HTTP specification, and strong: these bytes are immutable, so a match is
                // exact rather than merely equivalent.
                .eTag("\"" + record.getChecksum() + "\"")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                // Named from the record rather than from whatever the uploader called the file, so the name a
                // client writes to disk is one the registry chose.
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(pluginId + "-" + version + ".jar").build().toString())
                // The checksum again, in a header a client can read without parsing the body it is streaming.
                .header("X-Plugin-Checksum-SHA256", record.getChecksum())
                .header("X-Plugin-Coordinate", record.coordinate())
                // GridFsResource is already a Resource over the open stream; wrapping it would add a copy.
                .body(resource);
    }
}
