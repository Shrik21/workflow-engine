package com.orchpilot.workflow.storage.health;

import com.orchpilot.workflow.storage.dto.PathProbeResult;
import com.orchpilot.workflow.storage.dto.StorageSettingsDtos.HealthResponse;
import com.orchpilot.workflow.storage.model.FileStatus;
import com.orchpilot.workflow.storage.model.StorageStatus;
import com.orchpilot.workflow.storage.model.WorkflowStorageSettings;
import com.orchpilot.workflow.storage.repository.WorkflowFileRepository;
import com.orchpilot.workflow.storage.service.WorkflowStorageSettingsService;
import com.orchpilot.workflow.storage.validation.StoragePathValidator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Answers "can this deployment store files right now".
 *
 * <h2>Separate from the settings screen on purpose</h2>
 *
 * The settings response is about what an administrator configured; this is about whether it currently works. They
 * diverge exactly when it matters — after a restart where a volume failed to mount, the configuration is
 * unchanged and perfectly valid, and storage is broken.
 *
 * <h2>Free space is reported, not judged</h2>
 *
 * No threshold is applied. What counts as "low" depends on what this deployment stores, and a health check that
 * turned red at an arbitrary percentage would either cry wolf or stay silent too long. The number is surfaced so
 * an operator's own monitoring can decide.
 */
@Service
public class StorageHealthService {

    private final WorkflowStorageSettingsService settingsService;
    private final StoragePathValidator validator;
    private final WorkflowFileRepository fileRepository;

    public StorageHealthService(WorkflowStorageSettingsService settingsService,
                                StoragePathValidator validator,
                                WorkflowFileRepository fileRepository) {
        this.settingsService = settingsService;
        this.validator = validator;
        this.fileRepository = fileRepository;
    }

    public HealthResponse check() {
        Optional<WorkflowStorageSettings> found = settingsService.find();
        if (found.isEmpty()) {
            return new HealthResponse(StorageStatus.NOT_CONFIGURED, false, false, false, false, -1, 0,
                    List.of("No storage location has been configured."));
        }

        WorkflowStorageSettings settings = found.get();
        // Never create anything from a health check: it must observe, not change what it is observing.
        PathProbeResult probe = validator.probe(settings.getBasePath(), false);

        List<String> problems = new ArrayList<>(probe.problems());
        if (!settings.isEnabled()) {
            problems.add("File storage is configured but switched off; uploads are refused.");
        }

        StorageStatus status = probe.valid() && settings.isEnabled()
                ? StorageStatus.CONNECTED : StorageStatus.INVALID;

        long fileCount = fileRepository.countByStatus(FileStatus.ACTIVE);

        return new HealthResponse(status, true, settings.isEnabled(), probe.readable(), probe.writable(),
                probe.freeSpaceBytes(), fileCount, problems);
    }
}
