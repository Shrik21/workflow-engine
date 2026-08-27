package com.orchpilot.workflow.controller;

import com.orchpilot.workflow.dto.EmitEventRequest;
import com.orchpilot.workflow.event.WorkflowEvent;
import com.orchpilot.workflow.event.WorkflowEventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Event ingress for event-driven workflows.
 *
 * <p>Returns 202 rather than a list of started executions. Fan-out is asynchronous by design: the emitter should not
 * wait for arbitrary workflows to run, and telling it which ones started would couple it to whichever workflows
 * happen to subscribe today.
 */
@RestController
@RequestMapping("/api/events")
@Tag(name = "Events", description = "Emit business events that start event-triggered workflows")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final WorkflowEventPublisher eventPublisher;

    public EventController(WorkflowEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @PostMapping
    @Operation(summary = "Emit an event",
            description = "Starts every published workflow with an enabled EVENT trigger for this name. The payload "
                    + "becomes each execution's input, readable as ${input.*}.")
    public ResponseEntity<AcceptedEvent> emit(
            @Valid @RequestBody EmitEventRequest request,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        String actor = ActorResolver.resolve(actorHeader);
        WorkflowEvent event = new WorkflowEvent(request.name().trim(),
                request.payload() == null ? Map.of() : Map.copyOf(request.payload()),
                "api:" + actor, request.correlationId(), Instant.now());
        eventPublisher.publishBusinessEvent(event);
        log.info("Event '{}' emitted by {}", event.name(), actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new AcceptedEvent(event.name(), event.correlationId(), event.at()));
    }

    /**
     * Acknowledgement of an emitted event.
     *
     * @param name          event name
     * @param correlationId caller-supplied correlation id
     * @param at            when the engine accepted it
     */
    public record AcceptedEvent(String name, String correlationId, Instant at) {
    }
}
