package com.orchpilot.workflow.scheduler;

import com.orchpilot.workflow.model.TriggerType;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowSchedule;
import com.orchpilot.workflow.model.WorkflowStatus;
import com.orchpilot.workflow.model.WorkflowTrigger;
import com.orchpilot.workflow.repository.WorkflowScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Materialises a workflow's SCHEDULE triggers into rows the scheduler can claim.
 *
 * <p>Schedules are derived from the workflow definition rather than managed separately, so the definition stays
 * the single source of truth: publish reconciles them, archive and delete remove them. There is no way for a
 * schedule to survive the workflow it belongs to, and no second place to look when a cron does not fire.
 *
 * <p>Reconciliation preserves {@code nextRunAt} for an unchanged cron. Recomputing it on every publish would let
 * frequent publishing of a daily workflow silently skip a day, or fire it repeatedly.
 */
@Service
public class WorkflowScheduleService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduleService.class);

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");

    private final WorkflowScheduleRepository repository;
    private final MongoTemplate mongoTemplate;

    public WorkflowScheduleService(WorkflowScheduleRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Creates, updates and removes schedule rows to match a published workflow's triggers.
     *
     * @param workflow        the workflow being published
     * @param publishedVersion version the schedules should execute
     */
    public void reconcile(Workflow workflow, int publishedVersion) {
        if (workflow.getStatus() == WorkflowStatus.ARCHIVED) {
            removeSchedules(workflow.getId());
            return;
        }
        Set<String> keep = new HashSet<>();
        for (WorkflowTrigger trigger : workflow.getTriggers()) {
            if (trigger.getType() != TriggerType.SCHEDULE || trigger.getId() == null) {
                continue;
            }
            if (!trigger.isEnabled() || trigger.getCron() == null || trigger.getCron().isBlank()) {
                continue;
            }
            if (!CronExpression.isValidExpression(trigger.getCron())) {
                log.warn("Skipping schedule '{}' of workflow {}: invalid cron '{}'", trigger.getId(),
                        workflow.getId(), trigger.getCron());
                continue;
            }
            keep.add(trigger.getId());
            upsert(workflow, publishedVersion, trigger);
        }
        for (WorkflowSchedule existing : repository.findByWorkflowId(workflow.getId())) {
            if (!keep.contains(existing.getTriggerId())) {
                repository.delete(existing);
                log.info("Removed schedule {} because its trigger no longer exists", existing.getId());
            }
        }
    }

    /**
     * @param workflowId workflow whose schedules to remove
     */
    public void removeSchedules(String workflowId) {
        List<WorkflowSchedule> schedules = repository.findByWorkflowId(workflowId);
        if (schedules.isEmpty()) {
            return;
        }
        repository.deleteAll(schedules);
        log.info("Removed {} schedule(s) for workflow {}", schedules.size(), workflowId);
    }

    /**
     * @param workflowId workflow id
     * @return its schedules
     */
    public List<WorkflowSchedule> forWorkflow(String workflowId) {
        return repository.findByWorkflowId(workflowId);
    }

    /**
     * @return every schedule, for operational visibility
     */
    public List<WorkflowSchedule> all() {
        return repository.findAll();
    }

    /**
     * Finds schedules that are due.
     *
     * @param now   current time
     * @param limit maximum rows
     * @return due schedules, oldest first
     */
    public List<WorkflowSchedule> findDue(Instant now, int limit) {
        Query query = Query.query(Criteria.where("enabled").is(true).and("nextRunAt").lte(now))
                .with(org.springframework.data.domain.Sort.by("nextRunAt").ascending())
                .limit(Math.max(1, limit));
        return mongoTemplate.find(query, WorkflowSchedule.class);
    }

    /**
     * Atomically claims a due schedule by advancing its next fire time.
     *
     * <p>The expected {@code nextRunAt} is part of the update's filter, so exactly one instance in a cluster can
     * win. That single conditional write is the whole of the engine's distributed scheduling: no leader election,
     * no external scheduler, no duplicate cron fires.
     *
     * @param schedule the schedule as read
     * @return the time it was claimed for, or empty when another instance won the race
     */
    public Optional<Instant> claim(WorkflowSchedule schedule) {
        Instant expected = schedule.getNextRunAt();
        if (expected == null) {
            return Optional.empty();
        }
        Instant following = nextFireAfter(schedule.getCron(), schedule.getTimezone(), Instant.now());
        if (following == null) {
            log.warn("Disabling schedule {}: cron '{}' has no further fire times", schedule.getId(),
                    schedule.getCron());
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(schedule.getId())),
                    new Update().set("enabled", false), WorkflowSchedule.class);
            return Optional.empty();
        }
        Criteria criteria = Criteria.where("_id").is(schedule.getId()).and("nextRunAt").is(expected);
        Update update = new Update()
                .set("nextRunAt", following)
                .set("lastRunAt", Instant.now())
                .inc("fireCount", 1);
        long modified = mongoTemplate.updateFirst(Query.query(criteria), update, WorkflowSchedule.class)
                .getModifiedCount();
        return modified > 0 ? Optional.of(expected) : Optional.empty();
    }

    /**
     * Records the outcome of a fired schedule without touching {@code nextRunAt}, which the claim already
     * advanced.
     *
     * @param scheduleId  schedule id
     * @param executionId execution that was started, or {@code null}
     * @param error       failure message, or {@code null}
     */
    public void recordOutcome(String scheduleId, String executionId, String error) {
        Update update = new Update().set("lastExecutionId", executionId).set("lastError", error);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(scheduleId)), update,
                WorkflowSchedule.class);
    }

    /**
     * @param cron     cron expression
     * @param timezone zone name, {@code null} for UTC
     * @param after    reference time
     * @return the next fire time, or {@code null} when the expression has none
     */
    public Instant nextFireAfter(String cron, String timezone, Instant after) {
        if (cron == null || cron.isBlank()) {
            return null;
        }
        ZoneId zone = resolveZone(timezone);
        try {
            ZonedDateTime next = CronExpression.parse(cron).next(after.atZone(zone));
            return next == null ? null : next.toInstant();
        } catch (RuntimeException ex) {
            log.warn("Cannot compute the next fire time for cron '{}': {}", cron, ex.getMessage());
            return null;
        }
    }

    private void upsert(Workflow workflow, int publishedVersion, WorkflowTrigger trigger) {
        String id = WorkflowSchedule.idFor(workflow.getId(), trigger.getId());
        WorkflowSchedule schedule = repository.findById(id).orElseGet(WorkflowSchedule::new);
        boolean isNew = schedule.getId() == null;
        boolean cronChanged = !isNew && (!trigger.getCron().equals(schedule.getCron())
                || !java.util.Objects.equals(trigger.getTimezone(), schedule.getTimezone()));

        schedule.setId(id);
        schedule.setWorkflowId(workflow.getId());
        schedule.setTriggerId(trigger.getId());
        schedule.setWorkflowVersion(publishedVersion);
        schedule.setCron(trigger.getCron());
        schedule.setTimezone(trigger.getTimezone());
        schedule.setEnabled(true);
        schedule.setDefaultInput(new LinkedHashMap<>(trigger.getDefaultInput()));
        if (isNew || cronChanged || schedule.getNextRunAt() == null) {
            schedule.setNextRunAt(nextFireAfter(trigger.getCron(), trigger.getTimezone(), Instant.now()));
        }
        repository.save(schedule);
        log.info("Schedule {} {} for cron '{}' ({}), next run {}", id, isNew ? "created" : "updated",
                trigger.getCron(), resolveZone(trigger.getTimezone()), schedule.getNextRunAt());
    }

    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException ex) {
            log.warn("Unknown timezone '{}'; using UTC", timezone);
            return DEFAULT_ZONE;
        }
    }

    /**
     * @return schedule ids, for diagnostics
     */
    public List<String> scheduleIds() {
        List<String> ids = new ArrayList<>();
        repository.findAll().forEach(schedule -> ids.add(schedule.getId()));
        return ids;
    }
}
