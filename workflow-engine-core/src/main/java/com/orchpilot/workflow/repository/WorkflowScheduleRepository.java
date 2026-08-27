package com.orchpilot.workflow.repository;

import com.orchpilot.workflow.model.WorkflowSchedule;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Access to materialised cron triggers.
 *
 * <p>Claiming a due schedule is deliberately not here: it needs an atomic find-and-modify, which the
 * scheduler performs through {@code MongoTemplate}. Expressing it as a derived query would make it
 * look safe when it is not.
 */
@Repository
public interface WorkflowScheduleRepository extends MongoRepository<WorkflowSchedule, String> {

    List<WorkflowSchedule> findByWorkflowId(String workflowId);

    void deleteByWorkflowId(String workflowId);
}
