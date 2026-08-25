package com.orchpilot.workflow.access;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One user's membership of one group.
 *
 * <p>The compound index is unique, so adding the same user twice is refused by the database rather than by a
 * check that two concurrent requests could both pass. It is also the index the authorization path uses on
 * every request: {@code userId} is its prefix, so "which groups is this user in" is a single indexed lookup.
 */
@Document(collection = "group_members")
@CompoundIndex(name = "uk_group_member", def = "{'userId': 1, 'groupId': 1}", unique = true)
public class GroupMembership {

    @Id
    private String id;

    @Indexed
    private String groupId;

    @Indexed
    private String userId;

    private Instant createdAt;

    /** Who added this member, so the audit trail can attribute the grant. */
    private String createdBy;

    public GroupMembership() {
    }

    public GroupMembership(String groupId, String userId, String createdBy) {
        this.groupId = groupId;
        this.userId = userId;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
