package com.orchpilot.workflow.ai.memory;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for {@link AIAgentMemory}, keyed by execution id + memory key. */
public interface AIAgentMemoryRepository extends MongoRepository<AIAgentMemory, String> {

    void deleteByWorkflowExecutionId(String workflowExecutionId);
}
