package com.orchpilot.workflow.task;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** The append-only trail of what happened to each task. */
public interface TaskHistoryRepository extends MongoRepository<TaskHistoryEntry, String> {

    List<TaskHistoryEntry> findByTaskIdOrderByAtAsc(String taskId);

    List<TaskHistoryEntry> findByWorkflowExecutionIdOrderByAtAsc(String executionId);

    void deleteByTaskId(String taskId);
}
