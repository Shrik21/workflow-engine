package com.orchpilot.workflow.repository;

import com.orchpilot.workflow.model.WorkflowVersion;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Access to immutable published workflow snapshots.
 */
@Repository
public interface WorkflowVersionRepository extends MongoRepository<WorkflowVersion, String> {

    Optional<WorkflowVersion> findByWorkflowIdAndVersion(String workflowId, int version);

    List<WorkflowVersion> findByWorkflowIdOrderByVersionDesc(String workflowId);

    Optional<WorkflowVersion> findFirstByWorkflowIdOrderByVersionDesc(String workflowId);

    void deleteByWorkflowId(String workflowId);
}
