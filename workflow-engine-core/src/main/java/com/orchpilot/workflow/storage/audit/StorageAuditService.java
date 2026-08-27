package com.orchpilot.workflow.storage.audit;

import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.storage.model.WorkflowFileReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records what happened to files, on top of the platform's existing {@link AuditService}.
 *
 * <h2>A thin layer, not a second audit system</h2>
 *
 * The engine already has an audit trail with an actor, an action, an entity and a details map. This class only
 * decides <em>what to put in it</em> for storage operations, so the records land in the same collection and are
 * queryable alongside every other action. Building a parallel trail would mean two places to look during an
 * investigation, which in practice means one of them is missed.
 *
 * <h2>What is never recorded</h2>
 *
 * File contents, obviously — but also the resolved absolute path of any individual file. The relative path is
 * enough to identify what was touched, and it is the value that stays meaningful if storage is later moved. The
 * one exception is a settings change, where the absolute root <em>is</em> the thing being changed and recording
 * it is the entire point.
 */
@Service
public class StorageAuditService {

    private static final Logger log = LoggerFactory.getLogger(StorageAuditService.class);

    public static final String FILE_UPLOADED = "FILE_UPLOADED";
    public static final String FILE_DOWNLOADED = "FILE_DOWNLOADED";
    public static final String FILE_DELETED = "FILE_DELETED";
    public static final String STORAGE_SETTINGS_CHANGED = "STORAGE_SETTINGS_CHANGED";

    private static final String ENTITY_FILE = "WORKFLOW_FILE";
    private static final String ENTITY_SETTINGS = "STORAGE_SETTINGS";

    private static final String OUTCOME_SUCCESS = "SUCCESS";
    private static final String OUTCOME_FAILED = "FAILED";

    private final AuditService auditService;

    public StorageAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    public void uploaded(String actor, WorkflowFileReference reference) {
        record(actor, FILE_UPLOADED, reference, OUTCOME_SUCCESS, null);
    }

    public void downloaded(String actor, WorkflowFileReference reference) {
        record(actor, FILE_DOWNLOADED, reference, OUTCOME_SUCCESS, null);
    }

    public void deleted(String actor, WorkflowFileReference reference) {
        record(actor, FILE_DELETED, reference, OUTCOME_SUCCESS, null);
    }

    /**
     * Records a refused or failed operation.
     *
     * <p>Failures matter more than successes here: a run of rejected uploads against one workflow is what a
     * probing attempt looks like, and it is invisible if only successes are written.
     */
    public void failed(String actor, String action, String workflowId, Integer workflowVersion,
                       String fileId, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowId", workflowId);
        details.put("workflowVersion", workflowVersion);
        details.put("fileId", fileId);
        details.put("reason", reason);
        safely(() -> auditService.record(actor, action, ENTITY_FILE,
                fileId == null ? workflowId : fileId, OUTCOME_FAILED, details));
    }

    public void settingsChanged(String actor, Map<String, Object> details) {
        safely(() -> auditService.record(actor, STORAGE_SETTINGS_CHANGED, ENTITY_SETTINGS,
                "workflowStorageSettings", OUTCOME_SUCCESS, details));
    }

    private void record(String actor, String action, WorkflowFileReference reference, String outcome,
                        String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tenantId", reference.getTenantId());
        details.put("workflowId", reference.getWorkflowId());
        details.put("workflowVersion", reference.getWorkflowVersion());
        details.put("fileId", reference.getFileId());
        details.put("fileName", reference.getOriginalFileName());
        details.put("size", reference.getSize());
        details.put("checksum", reference.getChecksum());
        details.put("storageType", reference.getStorageType());
        // Relative, never absolute: identifies the object without describing the host's filesystem.
        details.put("relativePath", reference.getRelativePath());
        if (reason != null) {
            details.put("reason", reason);
        }
        safely(() -> auditService.record(actor, action, ENTITY_FILE, reference.getFileId(), outcome, details));
    }

    /**
     * Runs an audit write without letting it break the operation being audited.
     *
     * <p>A download that succeeds and then 500s because the audit collection was briefly unavailable would be a
     * worse outcome than a gap in the trail. The gap is logged so it is not silent.
     */
    private void safely(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException ex) {
            log.warn("Could not write a storage audit record: {}", ex.toString());
        }
    }
}
