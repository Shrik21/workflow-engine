package com.orchpilot.workflow.event;

import com.orchpilot.workflow.model.ExecutionMode;
import com.orchpilot.workflow.model.TriggerType;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowStatus;
import com.orchpilot.workflow.model.WorkflowTrigger;
import com.orchpilot.workflow.repository.WorkflowRepository;
import com.orchpilot.workflow.service.ExecutionService;
import com.orchpilot.workflow.service.StartExecutionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts workflows in response to emitted events.
 *
 * <p>Fan-out is deliberate: one event starts every published workflow subscribed to it. That is what lets a new
 * workflow subscribe to {@code ORDER_CREATED} without anything that emits the event being changed or redeployed.
 *
 * <p>Each start is independent. One workflow failing to start must not stop the others, so failures are logged
 * per subscriber rather than propagated to the emitter, which may be a plugin in the middle of a node.
 *
 * <p>Every event-started execution is asynchronous. The emitter, whether an HTTP caller or a trigger plugin's
 * thread, must not wait for arbitrary workflows to finish.
 */
@Component
public class EventTriggerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventTriggerDispatcher.class);

    private final WorkflowRepository workflowRepository;
    private final ExecutionService executionService;

    public EventTriggerDispatcher(WorkflowRepository workflowRepository, ExecutionService executionService) {
        this.workflowRepository = workflowRepository;
        this.executionService = executionService;
    }

    /**
     * @param event the emitted business event
     */
    @EventListener
    public void onWorkflowEvent(WorkflowEvent event) {
        if (event == null || event.name() == null || event.name().isBlank()) {
            return;
        }
        List<Workflow> candidates;
        try {
            candidates = workflowRepository.findByStatusAndTriggersType(WorkflowStatus.PUBLISHED,
                    TriggerType.EVENT);
        } catch (RuntimeException ex) {
            log.error("Could not look up event subscribers for '{}': {}", event.name(), ex.getMessage());
            return;
        }

        int started = 0;
        for (Workflow workflow : candidates) {
            for (WorkflowTrigger trigger : workflow.getTriggers()) {
                if (!matches(trigger, event.name())) {
                    continue;
                }
                try {
                    startFor(workflow, trigger, event);
                    started++;
                } catch (RuntimeException ex) {
                    log.error("Event '{}' could not start workflow {} via trigger {}: {}", event.name(),
                            workflow.getId(), trigger.getId(), ex.getMessage());
                }
            }
        }
        if (started > 0) {
            log.info("Event '{}' started {} execution(s)", event.name(), started);
        } else {
            log.debug("Event '{}' matched no published workflow trigger", event.name());
        }
    }

    private static boolean matches(WorkflowTrigger trigger, String eventName) {
        return trigger.getType() == TriggerType.EVENT
                && trigger.isEnabled()
                && eventName.equals(trigger.getEventName());
    }

    private void startFor(Workflow workflow, WorkflowTrigger trigger, WorkflowEvent event) {
        Map<String, Object> input = new LinkedHashMap<>(trigger.getDefaultInput());
        input.putAll(event.payload());
        input.put("eventName", event.name());
        input.put("eventSource", event.source());

        StartExecutionCommand command = new StartExecutionCommand(workflow.getId(), null, input, null,
                event.correlationId(), null, ExecutionMode.EVENT, "event:" + event.name(), trigger.getId(),
                true);
        var execution = executionService.start(command);
        log.debug("Event '{}' started execution {} of workflow {}", event.name(), execution.getId(),
                workflow.getId());
    }
}
