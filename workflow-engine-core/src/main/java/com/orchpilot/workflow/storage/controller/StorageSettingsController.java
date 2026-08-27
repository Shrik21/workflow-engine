package com.orchpilot.workflow.storage.controller;

import com.orchpilot.workflow.storage.dto.PathProbeResult;
import com.orchpilot.workflow.storage.dto.StorageSettingsDtos.HealthResponse;
import com.orchpilot.workflow.storage.dto.StorageSettingsDtos.SettingsResponse;
import com.orchpilot.workflow.storage.dto.StorageSettingsDtos.TestRequest;
import com.orchpilot.workflow.storage.dto.StorageSettingsDtos.UpdateRequest;
import com.orchpilot.workflow.storage.health.StorageHealthService;
import com.orchpilot.workflow.storage.service.WorkflowStorageSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings → File Storage.
 *
 * <h2>Authorisation</h2>
 *
 * Guarded by two dedicated permissions rather than by a role, in keeping with the rest of the platform: viewing
 * needs {@code WORKFLOW_STORAGE_SETTINGS_VIEW}, changing needs {@code WORKFLOW_STORAGE_SETTINGS_EDIT}. Because
 * {@code Role.ADMIN} is defined as every permission, an administrator holds both automatically, and no other role
 * lists them — so an ordinary workflow user cannot see or move the storage root, which is the requirement.
 *
 * <p>Splitting view from edit is what lets an auditor confirm where files are kept without being able to
 * redirect them, and a single combined permission could not express that.
 */
@RestController
@RequestMapping("/api/settings/storage")
@Tag(name = "Storage settings", description = "Configure where workflow files are physically stored")
public class StorageSettingsController {

    private final WorkflowStorageSettingsService settingsService;
    private final StorageHealthService healthService;

    public StorageSettingsController(WorkflowStorageSettingsService settingsService,
                                     StorageHealthService healthService) {
        this.settingsService = settingsService;
        this.healthService = healthService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('WORKFLOW_STORAGE_SETTINGS_VIEW')")
    @Operation(summary = "Read the storage configuration",
            description = "Re-probes the configured path, so the status reflects the location's condition now "
                    + "rather than when it was saved.")
    public SettingsResponse get() {
        return settingsService.current();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('WORKFLOW_STORAGE_SETTINGS_EDIT')")
    @Operation(summary = "Save the storage configuration",
            description = "Validates the path first and refuses to save one that failed. The stored value is the "
                    + "canonical path, with symbolic links resolved.")
    public SettingsResponse update(@Valid @RequestBody UpdateRequest request) {
        return settingsService.update(request);
    }

    @PostMapping("/test")
    @PreAuthorize("hasAuthority('WORKFLOW_STORAGE_SETTINGS_EDIT')")
    @Operation(summary = "Test a candidate path without saving",
            description = "Creates, writes, reads back and deletes a temporary file, because permission bits "
                    + "alone are unreliable on Windows and on network shares.")
    public PathProbeResult test(@Valid @RequestBody TestRequest request) {
        return settingsService.test(request);
    }

    @GetMapping("/health")
    @PreAuthorize("hasAuthority('WORKFLOW_STORAGE_SETTINGS_VIEW')")
    @Operation(summary = "Report whether storage works right now",
            description = "Includes free space and the number of stored files.")
    public HealthResponse health() {
        return healthService.check();
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('WORKFLOW_STORAGE_SETTINGS_EDIT')")
    @Operation(summary = "Clear the storage configuration",
            description = "Removes the setting only. No file is deleted — resetting a pointer must never destroy "
                    + "what it pointed at.")
    public ResponseEntity<Void> reset() {
        settingsService.reset();
        return ResponseEntity.noContent().build();
    }
}
