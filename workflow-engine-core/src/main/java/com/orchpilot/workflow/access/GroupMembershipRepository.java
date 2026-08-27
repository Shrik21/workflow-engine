package com.orchpilot.workflow.access;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Group memberships, one document per user-and-group pair. */
public interface GroupMembershipRepository extends MongoRepository<GroupMembership, String> {

    /** The query the authorization path runs on every request. Covered by the compound index. */
    List<GroupMembership> findByUserId(String userId);

    List<GroupMembership> findByGroupId(String groupId);

    Optional<GroupMembership> findByUserIdAndGroupId(String userId, String groupId);

    boolean existsByUserIdAndGroupId(String userId, String groupId);

    long countByGroupId(String groupId);

    void deleteByUserIdAndGroupId(String userId, String groupId);

    /** Used when a group is deleted, so no membership is left pointing at nothing. */
    void deleteByGroupId(String groupId);

    /** Used when a user is deleted, for the same reason. */
    void deleteByUserId(String userId);
}
