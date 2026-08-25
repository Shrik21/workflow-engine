package com.orchpilot.workflow.storage.dto;

import com.orchpilot.workflow.storage.model.RetentionPolicy;
import com.orchpilot.workflow.storage.model.StorageStatus;
import com.orchpilot.workflow.storage.model.StorageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Request and response shapes for {@code /api/settings/storage}.
 *
 * <p>Grouped in one file because they are read together and none is meaningful alone; splitting five records
 * across five files would make the contract harder to see, not easier.
 */
public final class StorageSettingsDtos {

    private StorageSettingsDtos() {
    }

    /**
     * What an administrator is asking to save.
     *
     * @param storageType     which provider; only implemented types are accepted
     * @param basePath        absolute root directory
     * @param createIfMissing create the directory when it does not exist
     * @param enabled         whether uploads are permitted
     * @param retentionPolicy how long files survive an archived version; stored, not yet enforced
     * @param retentionDays   only used with {@link RetentionPolicy#CUSTOM}
     */
    public record UpdateRequest(@NotNull StorageType storageType,
                                @NotBlank String basePath,
                                boolean createIfMissing,
                                Boolean enabled,
                                RetentionPolicy retentionPolicy,
                                Integer retentionDays) {
    }

    /** A path to test without saving anything. */
    public record TestRequest(@NotNull StorageType storageType,
                              @NotBlank String basePath,
                              boolean createIfMissing) {
    }

    /**
     * The current configuration as the settings screen shows it.
     *
     * <p>{@code basePath} is returned, unlike a secret: an administrator cannot confirm a mount is right without
     * seeing it, and the endpoint already requires the storage-settings permission. It is never returned by any
     * file endpoint, which is where an ordinary user could reach it.
     *
     * @param status         computed by re-probing, not by trusting what was true at save time
     * @param availableTypes storage types with a working provider in this build
     * @param probe          the live probe result, or null when nothing is configured
     */
    public record SettingsResponse(StorageType storageType,
                                   String basePath,
                                   boolean enabled,
                                   StorageStatus status,
                                   RetentionPolicy retentionPolicy,
                                   Integer retentionDays,
                                   Set<StorageType> availableTypes,
                                   List<StorageType> allTypes,
                                   PathProbeResult probe,
                                   Instant updatedAt,
                                   String updatedBy) {

        /** The response when no settings document exists yet. */
        public static SettingsResponse notConfigured(Set<StorageType> available, List<StorageType> all) {
            return new SettingsResponse(StorageType.LOCAL, null, false, StorageStatus.NOT_CONFIGURED,
                    RetentionPolicy.NEVER, null, available, all, null, null, null);
        }
    }

    /**
     * The health view.
     *
     * @param freeSpaceBytes usable space, or -1 when unknown
     * @param fileCount      active references, so a mismatch with what is on disk is visible
     */
    public record HealthResponse(StorageStatus status,
                                 boolean pathConfigured,
                                 boolean enabled,
                                 boolean readable,
                                 boolean writable,
                                 long freeSpaceBytes,
                                 long fileCount,
                                 List<String> problems) {
    }
}
