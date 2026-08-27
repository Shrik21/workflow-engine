package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.config.EngineInstance;
import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.repository.WorkflowExecutionRepository;
import com.orchpilot.workflow.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Resumes executions abandoned by a crashed instance.
 *
 * <p>An execution left {@code RUNNING} with a stale heartbeat is one whose owner died mid-flight. Because state is
 * persisted at every node boundary, resuming it re-enters the loop at the last completed node rather than the
 * start, and the plugin idempotency guard prevents a node that had already produced its side effect from producing
 * it twice.
 *
 * <p>Two safeguards make this safe to run in a cluster:
 *
 * <ul>
 *   <li>Ownership is taken with a conditional write that checks both the previous owner and the stale heartbeat, so
 *       two instances sweeping simultaneously cannot both resume the same execution.</li>
 *   <li>The sweep runs on a delay after startup and repeats on a slow interval, so a rolling deployment does not
 *       have several instances fighting over executions that are merely mid-restart.</li>
 * </ul>
 *
 * <p>Executions that are {@code WAITING} are never touched: parked is a healthy state, not an abandoned one.
 */
@Component
@ConditionalOnProperty(prefix = "workflow.engine.execution", name = "recovery-enabled", matchIfMissing = true)
public class ExecutionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryService.class);

    private static final int BATCH_SIZE = 25;

    private final WorkflowExecutionRepository executionRepository;
    private final ExecutionStateStore stateStore;
    private final ExecutionService executionService;
    private final EngineInstance engineInstance;
    private final WorkflowEngineProperties properties;

    public ExecutionRecoveryService(WorkflowExecutionRepository executionRepository,
                                    ExecutionStateStore stateStore, ExecutionService executionService,
                                    EngineInstance engineInstance, WorkflowEngineProperties properties) {
        this.executionRepository = executionRepository;
        this.stateStore = stateStore;
        this.executionService = executionService;
        this.engineInstance = engineInstance;
        this.properties = properties;
    }

    /**
     * Sweeps for abandoned executions and resumes the ones this instance can claim.
     */
    @Scheduled(initialDelayString = "${workflow.engine.execution.recovery-initial-delay-millis:30000}",
            fixedDelayString = "${workflow.engine.execution.stale-after-millis:120000}")
    public void recoverAbandonedExecutions() {
        long staleAfter = properties.getExecution().getStaleAfterMillis();
        Instant cutoff = Instant.now().minusMillis(staleAfter);
        List<WorkflowExecution> candidates;
        try {
            candidates = executionRepository.findByStatusAndHeartbeatAtBefore(ExecutionStatus.RUNNING, cutoff,
                    PageRequest.of(0, BATCH_SIZE));
        } catch (RuntimeException ex) {
            log.warn("Could not query abandoned executions: {}", ex.getMessage());
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }
        log.info("Found {} execution(s) with no heartbeat since {}", candidates.size(), cutoff);
        for (WorkflowExecution execution : candidates) {
            try {
                claimAndResume(execution, cutoff);
            } catch (RuntimeException ex) {
                log.error("Could not recover execution {}: {}", execution.getId(), ex.getMessage());
            }
        }
    }

    private void claimAndResume(WorkflowExecution execution, Instant cutoff) {
        if (engineInstance.id().equals(execution.getOwnerInstance())) {
            // Our own execution with a stale heartbeat means the heartbeat task is behind, not that the run died.
            log.debug("Skipping execution {}: this instance already owns it", execution.getId());
            return;
        }
        boolean claimed = stateStore.claimForRecovery(execution.getId(), engineInstance.id(),
                execution.getOwnerInstance(), cutoff);
        if (!claimed) {
            log.debug("Execution {} was claimed by another instance or is no longer stale", execution.getId());
            return;
        }
        log.info("Resuming execution {} of workflow {} abandoned by instance {} at node {}", execution.getId(),
                execution.getWorkflowId(), execution.getOwnerInstance(), execution.getCurrentNodeId());
        executionService.resume(execution.getId(), true, "recovery:" + engineInstance.id());
    }
}
