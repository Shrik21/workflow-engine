package com.orchpilot.workflow.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes engine events onto Spring's in-process event bus.
 *
 * <p>Every publication is wrapped: a misbehaving listener must not fail the workflow that happened to
 * trigger it. Failures are logged and the caller continues.
 */
@Component
public class SpringWorkflowEventPublisher implements WorkflowEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SpringWorkflowEventPublisher.class);

    private final ApplicationEventPublisher delegate;

    public SpringWorkflowEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publishBusinessEvent(WorkflowEvent event) {
        publish(event, "business event " + (event == null ? "null" : event.name()));
    }

    @Override
    public void publishExecutionEvent(ExecutionLifecycleEvent event) {
        publish(event, "execution event " + (event == null ? "null" : event.status()));
    }

    @Override
    public void publishPluginEvent(PluginLifecycleEvent event) {
        publish(event, "plugin event " + (event == null ? "null" : event.action()));
    }

    private void publish(Object event, String description) {
        if (event == null) {
            return;
        }
        try {
            delegate.publishEvent(event);
        } catch (RuntimeException ex) {
            log.warn("A listener failed handling {}: {}", description, ex.getMessage(), ex);
        }
    }
}
