package com.orchpilot.pluginserver.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;

/** The security trail. A top-level interface, as every repository here is. */
public interface SecurityAuditRepository extends MongoRepository<SecurityAuditLog, String> {

    Page<SecurityAuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    Page<SecurityAuditLog> findByUsernameIgnoreCaseOrderByTimestampDesc(String username, Pageable pageable);

    Page<SecurityAuditLog> findByActionOrderByTimestampDesc(SecurityAuditLog.Action action, Pageable pageable);

    Page<SecurityAuditLog> findBySuccessOrderByTimestampDesc(boolean success, Pageable pageable);

    /**
     * Recent failures for one account, which is how a lockout decision is made without a counter that a
     * restart would lose.
     */
    @Query("{ 'username': { $regex: '^?0$', $options: 'i' }, 'action': 'LOGIN_FAILURE', "
            + "'timestamp': { $gte: ?1 } }")
    long countRecentFailures(String username, Instant since);
}
