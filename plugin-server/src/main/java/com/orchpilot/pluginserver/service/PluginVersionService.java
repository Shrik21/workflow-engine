package com.orchpilot.pluginserver.service;

import com.orchpilot.pluginserver.config.PluginServerProperties;
import com.orchpilot.pluginserver.exception.PluginServerException;
import com.orchpilot.pluginserver.model.Plugin;
import com.orchpilot.pluginserver.model.PluginAuditEvent;
import com.orchpilot.pluginserver.model.PluginDependency;
import com.orchpilot.pluginserver.model.PluginNode;
import com.orchpilot.pluginserver.model.PluginStatus;
import com.orchpilot.pluginserver.model.PluginVersion;
import com.orchpilot.pluginserver.repository.PluginVersionRepository;
import com.orchpilot.pluginserver.storage.GridFsPluginStorage;
import com.orchpilot.workflow.sdk.manifest.PluginManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versions: publishing them, changing their state, and handing out their bytes.
 *
 * <h2>Order of writes on upload</h2>
 *
 * <p>Validate, then store bytes, then insert the record. The record is last because it is the thing that makes a
 * version exist: a crash before it leaves an orphaned GridFS file, which is identifiable from its metadata and
 * costs disk, while a crash after storing the record would leave a version the catalogue advertises and no
 * workflow service can download. Wasted bytes beat a broken promise.
 *
 * <p>The duplicate check is the insert itself. {@code _id} is {@code pluginId:version}, so two administrators
 * uploading the same version concurrently produce one success and one 409, without this class holding a lock.
 */
@Service
public class PluginVersionService {

    private static final Logger log = LoggerFactory.getLogger(PluginVersionService.class);

    /** States whose bytes may still be handed out. */
    private static final EnumSet<PluginStatus> DOWNLOADABLE =
            EnumSet.of(PluginStatus.ACTIVE, PluginStatus.INACTIVE, PluginStatus.DEPRECATED);

    private final PluginVersionRepository versions;
    private final PluginService pluginService;
    private final PluginValidationService validation;
    private final GridFsPluginStorage storage;
    private final PluginAuditService audit;
    private final PluginServerProperties properties;

    public PluginVersionService(PluginVersionRepository versions, PluginService pluginService,
                               PluginValidationService validation, GridFsPluginStorage storage,
                               PluginAuditService audit, PluginServerProperties properties) {
        this.versions = versions;
        this.pluginService = pluginService;
        this.validation = validation;
        this.storage = storage;
        this.audit = audit;
        this.properties = properties;
    }

    // ----------------------------------------------------------------------- upload

    /**
     * Validates and publishes an archive.
     *
     * @param fileName the uploaded file name
     * @param content  the archive bytes
     * @return the stored version
     * @throws PluginServerException when the archive is rejected or the version already exists
     */
    public PluginVersion upload(String fileName, byte[] content) {
        String actor = PluginAuditService.currentActor();

        PluginValidationService.Inspection inspection;
        try {
            inspection = validation.inspect(fileName, content);
        } catch (PluginServerException rejection) {
            // Recorded, because a stream of rejected uploads is worth being able to see, and because the
            // rejection reason is the most useful thing an author can be shown afterwards.
            audit.record("unknown", null, PluginAuditEvent.Action.UPLOAD_REJECTED, "FAILED",
                    Map.of("fileName", String.valueOf(fileName), "reason", rejection.getMessage(),
                            "problems", rejection.getDetails()));
            throw rejection;
        }

        PluginManifest manifest = inspection.manifest();
        String pluginId = manifest.pluginId();
        String version = manifest.version();

        /*
         * Checked before storing bytes as well as being enforced by the insert. This is not belt and braces for
         * its own sake: without it, a repeated upload of an existing version writes tens of megabytes into GridFS
         * and then fails, leaving an orphan every time somebody clicks twice.
         */
        if (versions.existsByPluginIdAndVersion(pluginId, version)) {
            audit.record(pluginId, version, PluginAuditEvent.Action.UPLOAD_REJECTED, "FAILED",
                    Map.of("reason", "version already exists"));
            throw PluginServerException.versionAlreadyExists(pluginId, version);
        }

        // The same bytes under a second version number. Allowed, and worth knowing about: it usually means a
        // build was re-tagged rather than rebuilt.
        List<PluginVersion> sameBytes = versions.findByChecksum(inspection.checksum());
        if (!sameBytes.isEmpty()) {
            log.warn("Archive for {}:{} is byte-identical to {}", pluginId, version,
                    sameBytes.stream().map(PluginVersion::coordinate).toList());
        }

        Plugin plugin = pluginService.upsertFromManifest(manifest, actor);

        String fileId = storage.store(pluginId, version, fileName, inspection.checksum(),
                new ByteArrayInputStream(content));

        PluginVersion record = toVersion(manifest, inspection, fileName, fileId, actor);
        try {
            record = versions.insert(record);
        } catch (DuplicateKeyException ex) {
            // Lost the race with a concurrent upload of the same coordinate. The winner's bytes are already
            // stored and identical in checksum; ours are removed so no orphan is left behind.
            storage.delete(fileId);
            audit.record(pluginId, version, PluginAuditEvent.Action.UPLOAD_REJECTED, "FAILED",
                    Map.of("reason", "lost a concurrent upload race"));
            throw PluginServerException.versionAlreadyExists(pluginId, version);
        }

        pluginService.recomputeLatestVersion(pluginId);

        audit.record(pluginId, version, PluginAuditEvent.Action.UPLOADED, "OK", uploadDetails(inspection));
        log.info("Published {} ({}) with {} node type(s), status {}", record.coordinate(), plugin.getName(),
                record.getNodes().size(), record.getStatus());
        return record;
    }

    private Map<String, Object> uploadDetails(PluginValidationService.Inspection inspection) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("checksum", inspection.checksum());
        details.put("sizeBytes", inspection.sizeBytes());
        details.put("entries", inspection.entryCount());
        details.put("signed", inspection.signed());
        details.put("nodeTypes", inspection.manifest().nodes().stream()
                .map(PluginManifest.ManifestNode::nodeType).toList());
        return details;
    }

    private PluginVersion toVersion(PluginManifest manifest, PluginValidationService.Inspection inspection,
                                    String fileName, String fileId, String actor) {
        PluginVersion record = new PluginVersion();
        record.setId(PluginVersion.idOf(manifest.pluginId(), manifest.version()));
        record.setPluginId(manifest.pluginId());
        record.setVersion(manifest.version());
        record.setName(manifest.name());
        record.setDescription(manifest.description());
        record.setVendor(manifest.vendor());
        record.setPluginType(manifest.pluginType().name());
        record.setMainClass(manifest.mainClass());
        record.setSdkVersion(manifest.sdkVersion());
        record.setJavaVersion(manifest.javaVersion());
        record.setEngineCompatibility(manifest.engineCompatibility());
        record.setChecksum(inspection.checksum());
        record.setFileId(fileId);
        record.setFileName(fileName == null || fileName.isBlank()
                ? manifest.pluginId() + "-" + manifest.version() + ".jar" : fileName);
        record.setFileSize(inspection.sizeBytes());
        record.setSigned(inspection.signed());
        record.setNodes(manifest.nodes().stream().map(PluginNode::from).toList());
        record.setDependencies(manifest.dependencies().stream().map(PluginDependency::from).toList());
        record.setRequestedPermissions(manifest.requestedPermissions());
        record.setUploadedAt(Instant.now());
        record.setUploadedBy(actor);

        if (properties.getRegistry().isPublishOnUpload()) {
            record.setStatus(PluginStatus.ACTIVE);
            record.setPublishedAt(Instant.now());
            record.setPublishedBy(actor);
        } else {
            record.setStatus(PluginStatus.DRAFT);
        }
        return record;
    }

    // ---------------------------------------------------------------------- reading

    public List<PluginVersion> versionsOf(String pluginId) {
        pluginService.require(pluginId);
        return versions
                .findByPluginIdOrderByOrderMajorDescOrderMinorDescOrderPatchDescOrderReleaseRankDesc(pluginId);
    }

    public PluginVersion require(String pluginId, String version) {
        return versions.findByPluginIdAndVersion(pluginId, version)
                .orElseThrow(() -> PluginServerException.versionNotFound(pluginId, version));
    }

    /**
     * The version an unpinned install resolves to.
     *
     * @param pluginId the plugin
     * @return the newest ACTIVE release
     * @throws PluginServerException when the plugin has no installable version
     */
    public PluginVersion requireLatest(String pluginId) {
        Plugin plugin = pluginService.require(pluginId);
        if (plugin.getLatestVersion() == null) {
            throw PluginServerException.badRequest("PLUGIN_NO_ACTIVE_VERSION",
                    "Plugin '" + pluginId + "' has no active release. Publish a version, or install a "
                            + "specific one by naming it.");
        }
        return require(pluginId, plugin.getLatestVersion());
    }

    /** Published versions of a plugin, for the catalogue. */
    public List<PluginVersion> publishedVersions(String pluginId) {
        return versions.findByPluginIdAndStatusIn(pluginId,
                EnumSet.of(PluginStatus.ACTIVE, PluginStatus.DEPRECATED));
    }

    // -------------------------------------------------------------------- lifecycle

    public PluginVersion publish(String pluginId, String version) {
        return transition(pluginId, version, PluginStatus.ACTIVE, PluginAuditEvent.Action.PUBLISHED, null);
    }

    public PluginVersion deactivate(String pluginId, String version) {
        return transition(pluginId, version, PluginStatus.INACTIVE, PluginAuditEvent.Action.DEACTIVATED, null);
    }

    public PluginVersion deprecate(String pluginId, String version) {
        return transition(pluginId, version, PluginStatus.DEPRECATED, PluginAuditEvent.Action.DEPRECATED, null);
    }

    /**
     * Withdraws a version for cause. Downloads are refused afterwards, and the state is final.
     *
     * @param pluginId the plugin
     * @param version  the version
     * @param reason   why, which is shown to anybody who tries to download it
     * @return the version
     */
    public PluginVersion revoke(String pluginId, String version, String reason) {
        return transition(pluginId, version, PluginStatus.REVOKED, PluginAuditEvent.Action.REVOKED, reason);
    }

    private PluginVersion transition(String pluginId, String version, PluginStatus to,
                                     PluginAuditEvent.Action action, String reason) {
        PluginVersion record = require(pluginId, version);
        PluginStatus from = record.getStatus();
        if (!from.canTransitionTo(to)) {
            throw PluginServerException.illegalTransition(record.coordinate(), from.name(), to.name());
        }

        record.setStatus(to);
        Instant now = Instant.now();
        switch (to) {
            case ACTIVE -> {
                record.setPublishedAt(now);
                record.setPublishedBy(PluginAuditService.currentActor());
            }
            case DEPRECATED -> record.setDeprecatedAt(now);
            case REVOKED -> {
                record.setRevokedAt(now);
                record.setRevocationReason(reason);
            }
            default -> {
                // INACTIVE keeps its publication timestamps: it was published, and may be again.
            }
        }
        PluginVersion saved = versions.save(record);

        // Latest may have moved in either direction: publishing can raise it, deprecating or revoking the
        // current latest must lower it to the next active release.
        pluginService.recomputeLatestVersion(pluginId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", from.name());
        details.put("to", to.name());
        if (reason != null && !reason.isBlank()) {
            details.put("reason", reason);
        }
        audit.record(pluginId, version, action, "OK", details);
        log.info("{} is now {} (was {})", saved.coordinate(), to, from);
        return saved;
    }

    // --------------------------------------------------------------------- download

    /**
     * Opens a version's archive for streaming.
     *
     * @param pluginId the plugin
     * @param version  the version
     * @return the resource, to be streamed straight to the response
     * @throws PluginServerException when the version is revoked, or its bytes are missing
     */
    public GridFsResource openArchive(String pluginId, String version) {
        PluginVersion record = require(pluginId, version);

        if (record.getStatus() == PluginStatus.REVOKED) {
            audit.record(pluginId, version, PluginAuditEvent.Action.DOWNLOADED, "DENIED",
                    Map.of("reason", "revoked"));
            throw PluginServerException.revoked(pluginId, version, record.getRevocationReason());
        }
        if (!DOWNLOADABLE.contains(record.getStatus())) {
            // A draft has not been published, so nothing should be running it yet.
            throw PluginServerException.illegalTransition(record.coordinate(),
                    record.getStatus().name(), "DOWNLOADED");
        }

        GridFsResource resource = storage.require(pluginId, version, record.getFileId());
        audit.record(pluginId, version, PluginAuditEvent.Action.DOWNLOADED, "OK",
                Map.of("checksum", record.getChecksum()));
        return resource;
    }

    /**
     * Removes one version and its bytes.
     *
     * @param pluginId the plugin
     * @param version  the version
     */
    public void delete(String pluginId, String version) {
        PluginVersion record = require(pluginId, version);
        removeQuietly(record);
        pluginService.recomputeLatestVersion(pluginId);
        audit.record(pluginId, version, PluginAuditEvent.Action.DELETED, "OK",
                Map.of("checksum", String.valueOf(record.getChecksum())));
        log.warn("Deleted {} and its archive", record.coordinate());
    }

    /**
     * Removes a plugin, every version of it, and every archive.
     *
     * <p>Lives here rather than in {@link PluginService} because it needs the storage layer, and the dependency
     * runs this way round. The controller calls one method instead of handing a callback across the boundary,
     * which is how it ended up needing access to a package-private one.
     *
     * @param pluginId the plugin
     * @return how many versions were removed
     */
    public int deletePlugin(String pluginId) {
        return pluginService.delete(pluginId, this::removeQuietly);
    }

    /** Removes a version's bytes and record, for the plugin-level delete. */
    private void removeQuietly(PluginVersion record) {
        storage.delete(record.getFileId());
        versions.delete(record);
    }
}
