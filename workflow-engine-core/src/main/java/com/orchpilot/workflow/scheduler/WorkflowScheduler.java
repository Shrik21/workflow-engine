package com.orchpilot.workflow.scheduler;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.model.ExecutionMode;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.model.WorkflowSchedule;
import com.orchpilot.workflow.service.ExecutionService;
import com.orchpilot.workflow.service.StartExecutionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fires cron-triggered workflows.
 *
 * <p>A poller with an atomic claim rather than an in-memory scheduler. The reason is clustering: Spring's
 * {@code TaskScheduler} would fire the same cron once per instance, so three replicas would send three copies of a
 * nightly report. Here, every instance polls, but the claim is a conditional update on {@code nextRunAt}, so
 * exactly one wins and the rest move on. It also means schedules survive a restart with no registration step, and
 * a schedule created on one instance is honoured by all of them within one poll interval.
 *
 * <p>Missed fire times are skipped rather than fired late. If the cluster was down for six hours, an hourly
 * workflow should not suddenly run six times; the misfire threshold makes that explicit and configurable.
 *
 * <p>Executions are always started asynchronously. A poll must not block behind a workflow that takes minutes.
 */
@Component
@ConditionalOnProperty(prefix = "workflow.engine.scheduler", name = "enabled", matchIfMissing = true)
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final WorkflowScheduleService scheduleService;
    private final ExecutionService executionService;
    private final WorkflowEngineProperties properties;

    public WorkflowScheduler(WorkflowScheduleService scheduleService, ExecutionService executionService,
                             WorkflowEngineProperties properties) {
        this.scheduleService = scheduleService;
        this.executionService = executionService;
        this.properties = properties;
        log.info("Cron scheduler enabled, polling every {} ms",
                properties.getScheduler().getPollIntervalMillis());
    }

    /**
     * Claims and fires every due schedule, up to the configured batch size.
     */
    @Scheduled(fixedDelayString = "${workflow.engine.scheduler.poll-interval-millis:10000}")
    public void poll() {
        WorkflowEngineProperties.Scheduler config = properties.getScheduler();
        Instant now = Instant.now();
        List<WorkflowSchedule> due;
        try {
            due = scheduleService.findDue(now, config.getBatchSize());
        } catch (RuntimeException ex) {
            log.warn("Could not query due schedules: {}", ex.getMessage());
            return;
        }
        if (due.isEmpty()) {
            return;
        }
        for (WorkflowSchedule schedule : due) {
            try {
                fire(schedule, now, config.getMisfireThresholdMillis());
            } catch (RuntimeException ex) {
                log.error("Schedule {} failed to fire: {}", schedule.getId(), ex.getMessage());
                scheduleService.recordOutcome(schedule.getId(), null, ex.getMessage());
            }
        }
    }

    private void fire(WorkflowSchedule schedule, Instant now, long misfireThresholdMillis) {
        Optional<Instant> claimed = scheduleService.claim(schedule);
        if (claimed.isEmpty()) {
            log.debug("Schedule {} was claimed by another instance", schedule.getId());
            return;
        }
        Instant scheduledFor = claimed.get();
        long lateness = Duration.between(scheduledFor, now).toMillis();
        if (misfireThresholdMillis > 0 && lateness > misfireThresholdMillis) {
            // Skipping is the safer default: firing a backlog of missed runs at once is rarely what an operator
            // wants, and the claim has already advanced nextRunAt so the next occurrence is unaffected.
            log.warn("Skipping schedule {}: its fire time {} is {} ms late, beyond the {} ms misfire threshold",
                    schedule.getId(), scheduledFor, lateness, misfireThresholdMillis);
            scheduleService.recordOutcome(schedule.getId(), null,
                    "skipped: " + lateness + " ms late");
            return;
        }

        Map<String, Object> input = new LinkedHashMap<>(schedule.getDefaultInput());
        input.put("scheduledFor", scheduledFor.toString());
        input.put("triggerId", schedule.getTriggerId());

        StartExecutionCommand command = new StartExecutionCommand(schedule.getWorkflowId(), null, input, null,
                "schedule:" + schedule.getId(), null, ExecutionMode.SCHEDULED,
                "scheduler", schedule.getTriggerId(), true);
        WorkflowExecution execution = executionService.start(command);
        scheduleService.recordOutcome(schedule.getId(), execution.getId(), null);
        log.info("Schedule {} fired for {} as execution {}", schedule.getId(), scheduledFor,
                execution.getId());
    }
}
