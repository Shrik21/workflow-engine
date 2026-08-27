package com.orchpilot.workflow.ai.memory;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A running conversation an AI Agent can carry across nodes — scoped to a single workflow execution.
 *
 * <h2>Scoped to the execution, and only the execution</h2>
 *
 * The document id is the execution id and the memory key, so a memory belongs to exactly one workflow run and
 * cannot be read by another: there is no cross-execution or cross-tenant path to it, because the execution id is
 * itself the tenant-scoped handle every other record keys off. This is deliberately not long-term memory — it is
 * the short-term context that lets two AI Agent nodes in the same run share a thread. It holds user and assistant
 * turns only (never system instructions, never tool traffic), and is bounded so a long run cannot grow it without
 * limit.
 */
@Document(collection = "aiAgentMemory")
public class AIAgentMemory {

    /** One remembered turn: who said it and what was said. */
    public static class Turn {
        private String role;
        private String content;

        public Turn() {
        }

        public Turn(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    @Id
    private String id;

    @Indexed
    private String workflowExecutionId;
    private String memoryKey;
    private List<Turn> turns = new ArrayList<>();
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowExecutionId() {
        return workflowExecutionId;
    }

    public void setWorkflowExecutionId(String workflowExecutionId) {
        this.workflowExecutionId = workflowExecutionId;
    }

    public String getMemoryKey() {
        return memoryKey;
    }

    public void setMemoryKey(String memoryKey) {
        this.memoryKey = memoryKey;
    }

    public List<Turn> getTurns() {
        return turns;
    }

    public void setTurns(List<Turn> turns) {
        this.turns = turns == null ? new ArrayList<>() : turns;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
