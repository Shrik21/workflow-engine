package com.orchpilot.workflow.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes notifications to the application log.
 *
 * <p>Registered by {@link TaskConfig} rather than by {@code @Component}, so that a real notifier — one that sends
 * mail, or posts to Slack through the plugin platform — replaces it by simply existing. Annotating this class
 * {@code @Component @ConditionalOnMissingBean(TaskNotifier.class)} looks equivalent and is not: that condition is
 * only honoured on a {@code @Bean} method, and on a scanned component it is evaluated against a registry that
 * already contains this very class, so the bean excludes itself and the context fails to start.
 *
 * <p>Logs identifiers and never the task's contents. A log line is the least access-controlled artefact a server
 * produces, and a form's values are whatever the author asked for, which in an HR workflow is a salary.
 */
public class LoggingTaskNotifier implements TaskNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingTaskNotifier.class);

    @Override
    public void notifyAssigned(HumanTask task, String why) {
        if (task.getAssigneeUserId() != null) {
            log.info("Task {} ({}) is assigned to {}: {}", task.getId(), task.getTaskName(),
                    task.getAssigneeUsername(), why);
        } else if (task.hasCandidates()) {
            log.info("Task {} ({}) is offered to {} group(s) and {} named user(s): {}", task.getId(),
                    task.getTaskName(), task.getCandidateGroupIds().size(),
                    task.getCandidateUserIds().size(), why);
        } else {
            log.warn("Task {} ({}) on execution {} is addressed to nobody and will only be visible to an "
                            + "administrator", task.getId(), task.getTaskName(),
                    task.getWorkflowExecutionId());
        }
    }

    @Override
    public void notifyOverdue(HumanTask task) {
        log.warn("Task {} ({}) was due at {} and is still {}", task.getId(), task.getTaskName(),
                task.getDueAt(), task.getStatus());
    }

    @Override
    public void notifyExpired(HumanTask task) {
        log.warn("Task {} ({}) expired at {} without being submitted; execution {} has been failed",
                task.getId(), task.getTaskName(), task.getExpiresAt(), task.getWorkflowExecutionId());
    }
}
