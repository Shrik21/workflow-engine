package com.orchpilot.workflow.storage.service;

import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.storage.audit.StorageAuditService;
import com.orchpilot.workflow.storage.dto.PathProbeResult;
import com.orchpilot.workflow.storage.dto.StorageSettingsDtos.SettingsResponse;
import com.orchpilot.workflow.storage.dto.StorageSettingsDtos.TestRequest;
import com.orchpilot.workflow.storage.dto.StorageSettingsDtos.UpdateRequest;
import com.orchpilot.workflow.storage.exception.FileStorageException;
import com.orchpilot.workflow.storage.model.RetentionPolicy;
import com.orchpilot.workflow.storage.model.StorageStatus;
import com.orchpilot.workflow.storage.model.StorageType;
import com.orchpilot.workflow.storage.model.WorkflowStorageSettings;
import com.orchpilot.workflow.storage.provider.FileStorageProviderRegistry;
import com.orchpilot.workflow.storage.repository.WorkflowStorageSettingsRepository;
import com.orchpilot.workflow.storage.validation.StoragePathValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes the storage configuration.
 *
 * <h2>Nothing is saved that has not been proven to work</h2>
 *
 * {@link #update} probes before it persists, and refuses to store a path that failed. The alternative — save
 * whatever was typed and discover the problem at the first upload — turns an administrator's typo into a user's
 * failed workflow, hours later and with no obvious connection between the two.
 *
 * <h2>Status is measured, not remembered</h2>
 *
 * {@link #current} re-probes on every read rather than returning a status stored at save time. A volume that
 * failed to mount after a restart, or a directory somebody removed, is exactly what an administrator opens this
 * screen to find out about, and a cached {@code CONNECTED} would hide it.
 */
@Service
public class WorkflowStorageSettingsService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStorageSettingsService.class);

    private final WorkflowStorageSettingsRepository repository;
    private final StoragePathValidator validator;
    private final FileStorageProviderRegistry providers;
    private final StorageAuditService audit;

    public WorkflowStorageSettingsService(WorkflowStorageSettingsRepository repository,
                                          StoragePathValidator validator,
                                          FileStorageProviderRegistry providers,
                                          StorageAuditService audit) {
        this.repository = repository;
        this.validator = validator;
        this.providers = providers;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    /**
     * @return the settings document for the caller's tenant, or empty when none exists
     */
    public Optional<WorkflowStorageSettings> find() {
        String tenantId = currentTenant();
        return tenantId == null ? repository.findDefault() : repository.findByTenantId(tenantId);
    }

    /**
     * Resolves the settings that must be present for a file operation.
     *
     * @throws FileStorageException when storage is unconfigured or disabled
     */
    public WorkflowStorageSettings require() {
        WorkflowStorageSettings settings = find().orElseThrow(FileStorageException::notConfigured);
        if (!settings.isEnabled()) {
            throw FileStorageException.notConfigured();
        }
        if (settings.getBasePath() == null || settings.getBasePath().isBlank()) {
            throw FileStorageException.notConfigured();
        }
        return settings;
    }

    /** The settings screen's view, with a live probe. */
    public SettingsResponse current() {
        Optional<WorkflowStorageSettings> found = find();
        if (found.isEmpty()) {
            return SettingsResponse.notConfigured(providers.available(), allTypes());
        }
        WorkflowStorageSettings settings = found.get();

        // Do not create anything while merely reading the configuration: a read must not have a side effect on
        // the filesystem, and a missing directory is information rather than something to quietly fix.
        PathProbeResult probe = validator.probe(settings.getBasePath(), false);
        StorageStatus status = probe.valid() ? StorageStatus.CONNECTED : StorageStatus.INVALID;

        return new SettingsResponse(settings.getStorageType(), settings.getBasePath(), settings.isEnabled(),
                status, settings.getRetentionPolicy(), settings.getRetentionDays(),
                providers.available(), allTypes(), probe, settings.getUpdatedAt(), settings.getUpdatedBy());
    }

    // ------------------------------------------------------------------ writes

    /** Tests a candidate path without saving it. */
    public PathProbeResult test(TestRequest request) {
        requireImplemented(request.storageType());
        PathProbeResult result = validator.probe(request.basePath(), request.createIfMissing());
        log.info("Storage path test by {}: valid={}, writable={}",
                CurrentUser.userId().orElse("system"), result.valid(), result.writable());
        return result;
    }

    /**
     * Validates and saves.
     *
     * @throws FileStorageException when the path did not pass its probe
     */
    public SettingsResponse update(UpdateRequest request) {
        requireImplemented(request.storageType());

        PathProbeResult probe = validator.probe(request.basePath(), request.createIfMissing());
        if (!probe.valid()) {
            throw FileStorageException.rejected(
                    "The storage path was not saved: " + String.join(" ", probe.problems()));
        }

        WorkflowStorageSettings settings = find().orElseGet(WorkflowStorageSettings::new);
        String previousPath = settings.getBasePath();
        boolean creating = settings.getId() == null;

        String actor = CurrentUser.userId().orElse("system");
        Instant now = Instant.now();
        if (creating) {
            settings.setTenantId(currentTenant());
            settings.setCreatedAt(now);
            settings.setCreatedBy(actor);
        }
        settings.setStorageType(request.storageType());
        // The canonical path, not the raw input: every containment check compares against this value.
        settings.setBasePath(probe.canonicalPath());
        settings.setEnabled(request.enabled() == null || request.enabled());
        settings.setRetentionPolicy(request.retentionPolicy() == null
                ? RetentionPolicy.NEVER : request.retentionPolicy());
        settings.setRetentionDays(request.retentionDays());
        settings.setUpdatedAt(now);
        settings.setUpdatedBy(actor);

        WorkflowStorageSettings saved = repository.save(settings);

        // The path is recorded in the audit trail on purpose. It is the one place an absolute path belongs:
        // "who pointed storage where, and when" is unanswerable without it, and the trail is admin-only.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("storageType", saved.getStorageType().name());
        details.put("basePath", saved.getBasePath());
        details.put("previousBasePath", previousPath);
        details.put("enabled", saved.isEnabled());
        details.put("retentionPolicy", saved.getRetentionPolicy().name());
        audit.settingsChanged(actor, details);

        if (previousPath != null && !previousPath.equals(saved.getBasePath())) {
            // Existing references are relative, so they now resolve under the new root. If the files were not
            // moved with it, downloads will report FILE_NOT_FOUND_IN_STORAGE — worth saying out loud.
            log.warn("Storage root changed by {}. Existing file references now resolve under the new root; "
                    + "move the existing directory tree or run the consistency check.", actor);
        }
        return current();
    }

    /**
     * Clears the configuration.
     *
     * <p>Deliberately does <strong>not</strong> touch the filesystem. Removing a pointer must never remove the
     * data it pointed at — an administrator resetting a mistyped path would otherwise destroy every stored file.
     */
    public void reset() {
        find().ifPresent(settings -> {
            repository.delete(settings);
            String actor = CurrentUser.userId().orElse("system");
            audit.settingsChanged(actor, Map.of("action", "RESET", "previousBasePath",
                    String.valueOf(settings.getBasePath())));
            log.warn("Storage configuration reset by {}. Stored files were left untouched on disk.", actor);
        });
    }

    // ------------------------------------------------------------------ helpers

    /** @return the caller's tenant, or null in a single-tenant deployment */
    public String currentTenant() {
        return CurrentUser.principal().map(principal -> principal.getTenantId()).orElse(null);
    }

    private void requireImplemented(StorageType storageType) {
        if (storageType == null || !storageType.isImplemented()) {
            throw FileStorageException.unsupportedProvider(String.valueOf(storageType));
        }
        // Belt and braces: the enum says it ships, the context confirms a bean actually exists.
        providers.require(storageType);
    }

    private static List<StorageType> allTypes() {
        return Arrays.asList(StorageType.values());
    }
}
