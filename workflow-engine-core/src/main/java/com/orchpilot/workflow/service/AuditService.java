package com.orchpilot.workflow.service;

import com.orchpilot.workflow.model.AuditRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;

/**
 * Records administrative actions, and reads them back.
 *
 * <p>For a platform that loads third-party code, the audit trail is a functional requirement rather
 * than compliance decoration: when a plugin misbehaves, the first questions are who installed which
 * version, when, with what permissions, and which secrets it read. The same trail answers, for a
 * workflow, who created it, who has changed it since, and when.
 */
public interface AuditService {

    /**
     * Records one action. Must never throw: failing to audit may not fail the operation, but it must be
     * visible in the application log.
     *
     * @param actor      who performed it
     * @param action     what was done, upper-snake-case, e.g. {@code PLUGIN_ACTIVATED}
     * @param entityType kind of thing acted on, e.g. {@code PLUGIN}
     * @param entityId   identifier of the thing acted on
     * @param outcome    {@code OK}, {@code DENIED} or {@code FAILED}
     * @param details    structured context; must not contain secrets
     */
    void record(String actor, String action, String entityType, String entityId, String outcome,
                Map<String, Object> details);

    /**
     * The audit history of one thing, most recent first.
     *
     * @param entityType kind of thing, e.g. {@code WORKFLOW}
     * @param entityId   its identifier
     * @param pageable   page and size; the order is always {@code at} descending regardless
     * @return the records, newest first
     */
    Page<AuditRecord> history(String entityType, String entityId, Pageable pageable);
}
