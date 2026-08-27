package com.orchpilot.workflow.ai.memory;

import com.orchpilot.workflow.ai.model.AIMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and appends an AI Agent's short-term memory for one workflow execution.
 *
 * <h2>Bounded, execution-scoped, best-effort</h2>
 *
 * Memory is addressed by the execution id and a memory key, so two AI Agent nodes that share a key in the same run
 * share a thread, and nothing outside that run can reach it. Only user and assistant turns are kept — system
 * instructions stay out (they define the agent and must not be replayable as data) and tool traffic stays out (it
 * belongs to the node's own loop). The thread is capped at {@link #MAX_TURNS} messages, oldest dropped first, so a
 * long-running workflow cannot grow it without limit. Persistence is best-effort: a memory failure logs and
 * degrades to no-memory rather than failing the node, because the agent must still answer.
 */
@Service
public class AIAgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AIAgentMemoryService.class);

    /** The most recent messages kept; older ones are dropped so memory stays bounded. */
    static final int MAX_TURNS = 20;

    private final AIAgentMemoryRepository repository;

    public AIAgentMemoryService(AIAgentMemoryRepository repository) {
        this.repository = repository;
    }

    /**
     * @return the remembered user/assistant turns for this execution and key, oldest first; empty when there is
     *         no memory yet or it could not be read
     */
    public List<AIMessage> load(String executionId, String key) {
        try {
            return repository.findById(id(executionId, key))
                    .map(AIAgentMemoryService::toMessages)
                    .orElseGet(List::of);
        } catch (RuntimeException ex) {
            log.warn("Could not load AI agent memory {}: {}", id(executionId, key), ex.getMessage());
            return List.of();
        }
    }

    /**
     * Appends one exchange — the user prompt and the assistant's answer — trimming to the cap.
     *
     * @param userContent      the user prompt as sent (without any transient tool context)
     * @param assistantContent the assistant's final answer text
     */
    public void append(String executionId, String key, String userContent, String assistantContent) {
        try {
            String documentId = id(executionId, key);
            AIAgentMemory memory = repository.findById(documentId).orElseGet(() -> {
                AIAgentMemory fresh = new AIAgentMemory();
                fresh.setId(documentId);
                fresh.setWorkflowExecutionId(executionId);
                fresh.setMemoryKey(key);
                return fresh;
            });
            List<AIAgentMemory.Turn> turns = new ArrayList<>(memory.getTurns());
            if (userContent != null) {
                turns.add(new AIAgentMemory.Turn(AIMessage.Role.USER.name(), userContent));
            }
            if (assistantContent != null) {
                turns.add(new AIAgentMemory.Turn(AIMessage.Role.ASSISTANT.name(), assistantContent));
            }
            while (turns.size() > MAX_TURNS) {
                turns.remove(0);
            }
            memory.setTurns(turns);
            memory.setUpdatedAt(Instant.now());
            repository.save(memory);
        } catch (RuntimeException ex) {
            log.warn("Could not append AI agent memory for {}: {}", executionId, ex.getMessage());
        }
    }

    /** Clears the memory for a whole execution — for a lifecycle hook that tidies up a finished run. */
    public void clear(String executionId) {
        try {
            repository.deleteByWorkflowExecutionId(executionId);
        } catch (RuntimeException ex) {
            log.warn("Could not clear AI agent memory for {}: {}", executionId, ex.getMessage());
        }
    }

    private static List<AIMessage> toMessages(AIAgentMemory memory) {
        List<AIMessage> messages = new ArrayList<>();
        for (AIAgentMemory.Turn turn : memory.getTurns()) {
            AIMessage.Role role = AIMessage.Role.ASSISTANT.name().equals(turn.getRole())
                    ? AIMessage.Role.ASSISTANT : AIMessage.Role.USER;
            messages.add(new AIMessage(role, turn.getContent()));
        }
        return messages;
    }

    private static String id(String executionId, String key) {
        return executionId + ":" + (key == null || key.isBlank() ? "default" : key);
    }
}
