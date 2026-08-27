package com.orchpilot.workflow.forms;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** The immutable snapshots a workflow node references and a task renders. */
public interface FormVersionRepository extends MongoRepository<FormVersion, String> {

    Optional<FormVersion> findByFormDefinitionIdAndVersion(String formDefinitionId, int version);

    List<FormVersion> findByFormDefinitionIdOrderByVersionDesc(String formDefinitionId);

    /** Used to assign the next version number. */
    Optional<FormVersion> findFirstByFormDefinitionIdOrderByVersionDesc(String formDefinitionId);

    /** Used to detect an identical republish and reuse the existing version. */
    Optional<FormVersion> findByFormDefinitionIdAndDefinitionHash(String formDefinitionId, String hash);

    void deleteByFormDefinitionId(String formDefinitionId);
}
