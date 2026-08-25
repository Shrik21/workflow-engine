package com.orchpilot.workflow.ai.execution;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** Persistence for {@link AIAgentExecution}. */
public interface AIAgentExecutionRepository extends MongoRepository<AIAgentExecution, String> {

    List<AIAgentExecution> findByWorkflowExecutionIdOrderByStartedAtDesc(String workflowExecutionId);

    /** The most recent runs, for the execution-history view. */
    List<AIAgentExecution> findTop200ByOrderByStartedAtDesc();
}
