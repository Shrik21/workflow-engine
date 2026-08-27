package com.orchpilot.workflow.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Enforces the two kinds of task deadline.
 *
 * <ul>
 *   <li><b>Expiry</b> is enforced: a task past {@code expiresAt} is marked EXPIRED and its execution is
 *       cancelled. That is a state change, so it must happen whether or not anybody is looking.</li>
 *   <li><b>Due</b> is only reported: a task past {@code dueAt} produces one reminder and stays completable.
 *       Nothing about being late makes an approval invalid.</li>
 * </ul>
 *
 * <h2>Reminders are sent once per process, not once per task</h2>
 *
 * <p>The ids already reminded about are held in memory. A restart therefore re-reminds, which is the right way
 * round to be wrong: a duplicate reminder is noise, whereas persisting a "reminded" flag means a task that was
 * never reminded about because of one failed write is never reminded about again. Making this exactly-once would
 * mean a reminder schedule on the task document, which is a feature nobody asked for.
 *
 * <h2>Every instance runs this</h2>
 *
 * <p>And that is safe rather than lucky. {@link TaskCompletionService#expire} returns immediately for a task that
 * is no longer actionable, so the second instance to arrive does nothing. Reminders can double up across
 * instances, which is the same acceptable noise as after a restart.
 */
@Component
public class TaskDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskDeadlineScheduler.class);

    /** Caps the memory held by the reminder ledger; a busy platform simply reminds again after a wrap. */
    private static final int MAX_REMEMBERED_REMINDERS = 10_000;

    private final HumanTaskRepository tasks;
    private final TaskCompletionService completion;
    private final TaskNotifier notifier;

    private final Set<String> reminded = new HashSet<>();

    public TaskDeadlineScheduler(HumanTaskRepository tasks, TaskCompletionService completion,
                                TaskNotifier notifier) {
        this.tasks = tasks;
        this.completion = completion;
        this.notifier = notifier;
    }

    /**
     * Expires overdue tasks and reminds about late ones.
     *
     * <p>A minute is frequent enough for a deadline measured in hours or days and cheap: both queries are indexed
     * and return nothing in the ordinary case.
     */
    @Scheduled(initialDelayString = "${workflow.engine.tasks.deadline-initial-delay-millis:45000}",
            fixedDelayString = "${workflow.engine.tasks.deadline-interval-millis:60000}")
    public void enforceDeadlines() {
        expireLapsedTasks();
        remindAboutOverdueTasks();
    }

    private void expireLapsedTasks() {
        List<HumanTask> lapsed;
        try {
            lapsed = tasks.findByStatusInAndExpiresAtBefore(HumanTaskService.ACTIONABLE, Instant.now());
        } catch (RuntimeException ex) {
            log.warn("Could not query for expired tasks: {}", ex.getMessage());
            return;
        }
        for (HumanTask task : lapsed) {
            try {
                completion.expire(task);
            } catch (RuntimeException ex) {
                // One task that cannot be expired must not stop the rest, and must not stop the reminders.
                log.error("Could not expire task {}: {}", task.getId(), ex.getMessage());
            }
        }
        if (!lapsed.isEmpty()) {
            log.info("Expired {} task(s) past their deadline", lapsed.size());
        }
    }

    private void remindAboutOverdueTasks() {
        List<HumanTask> overdue;
        try {
            overdue = tasks.findByStatusInAndDueAtBefore(HumanTaskService.ACTIONABLE, Instant.now());
        } catch (RuntimeException ex) {
            log.warn("Could not query for overdue tasks: {}", ex.getMessage());
            return;
        }
        if (reminded.size() > MAX_REMEMBERED_REMINDERS) {
            reminded.clear();
        }
        for (HumanTask task : overdue) {
            if (reminded.add(task.getId())) {
                notifier.notifyOverdue(task);
            }
        }
    }
}
