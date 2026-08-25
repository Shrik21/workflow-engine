package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which executions this instance is actively running and keeps their heartbeats fresh.
 *
 * <p>Needed because the engine only writes the execution document at node boundaries. A node that takes
 * four minutes, which a large REST call or a slow database node easily can, would otherwise look
 * abandoned to the recovery sweep and be resumed elsewhere while it is still running. A separate
 * heartbeat decouples "is this instance alive and working on it" from "has it made progress".
 */
@Component
public class ExecutionHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionHeartbeatService.class);

    private final ExecutionStateStore stateStore;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public ExecutionHeartbeatService(ExecutionStateStore stateStore, WorkflowEngineProperties properties) {
        this.stateStore = stateStore;
        log.debug("Heartbeat interval configured at {} ms",
                properties.getExecution().getHeartbeatIntervalMillis());
    }

    /**
     * @param executionId execution this instance has started working on
     */
    public void track(String executionId) {
        if (executionId != null) {
            active.add(executionId);
        }
    }

    /**
     * @param executionId execution this instance has stopped working on
     */
    public void release(String executionId) {
        if (executionId != null) {
            active.remove(executionId);
        }
    }

    /**
     * @return number of executions currently running on this instance, exposed for diagnostics
     */
    public int activeCount() {
        return active.size();
    }

    /**
     * Refreshes every tracked execution. Runs on the engine's scheduler, not the execution pool, so a
     * saturated execution pool cannot starve heartbeats and cause a self-inflicted recovery storm.
     */
    @Scheduled(fixedDelayString = "${workflow.engine.execution.heartbeat-interval-millis:15000}")
    public void refresh() {
        if (active.isEmpty()) {
            return;
        }
        for (String executionId : active) {
            stateStore.heartbeat(executionId);
        }
        log.trace("Refreshed heartbeat for {} execution(s)", active.size());
    }
}
