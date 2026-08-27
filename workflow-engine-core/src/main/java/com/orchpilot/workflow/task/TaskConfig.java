package com.orchpilot.workflow.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the pieces of the task engine that are meant to be replaceable.
 *
 * <p>Only the notifier so far. It is here rather than annotated {@code @Component} because
 * {@code @ConditionalOnMissingBean} is only honoured on a {@code @Bean} method: on a scanned component the
 * condition is evaluated against a registry that already holds the class itself, so the bean excludes itself and
 * the application fails to start with "required a bean of type TaskNotifier that could not be found". That is a
 * failure no unit test sees, because nothing but a real context start evaluates the condition at all.
 */
@Configuration
public class TaskConfig {

    /**
     * The default notifier: writes to the log.
     *
     * <p>Declaring any other {@link TaskNotifier} bean — a mail sender, or one that dispatches through the
     * plugin platform — replaces this without touching either class.
     *
     * @return the fallback notifier
     */
    @Bean
    @ConditionalOnMissingBean(TaskNotifier.class)
    public TaskNotifier loggingTaskNotifier() {
        return new LoggingTaskNotifier();
    }
}
