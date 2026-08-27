package com.orchpilot.workflow.auth.repository;

import com.orchpilot.workflow.auth.model.LoginAttemptCounter;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** Failed-login counters for the brute-force throttle. */
public interface LoginAttemptRepository extends MongoRepository<LoginAttemptCounter, String> {

    Optional<LoginAttemptCounter> findByIdentifier(String identifier);

    void deleteByIdentifier(String identifier);
}
