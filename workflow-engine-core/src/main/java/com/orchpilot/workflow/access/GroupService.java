package com.orchpilot.workflow.access;

import com.orchpilot.workflow.access.dto.GroupDtos;
import com.orchpilot.workflow.audit.SecurityAuditEvent;
import com.orchpilot.workflow.audit.SecurityAuditService;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.auth.service.OperationNotAllowedException;
import com.orchpilot.workflow.exception.WorkflowNotFoundException;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.repository.WorkflowRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Group administration and workflow sharing.
 *
 * <p>Every mutation here changes who can reach what, so every one of them is audited with the actor, the
 * subject and the before-and-after state. A permission change with no record of who made it is the kind of
 * thing that is only noticed when someone asks how an account got access.
 */
@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private final GroupRepository groups;
    private final GroupMembershipRepository memberships;
    private final UserRepository users;
    private final WorkflowRepository workflows;
    private final WorkflowAuthorizationService authorization;
    private final SecurityAuditService audit;

    public GroupService(GroupRepository groups, GroupMembershipRepository memberships, UserRepository users,
                        WorkflowRepository workflows, WorkflowAuthorizationService authorization,
                        SecurityAuditService audit) {
        this.groups = groups;
        this.memberships = memberships;
        this.users = users;
        this.workflows = workflows;
        this.authorization = authorization;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ groups

    public List<GroupDtos.GroupResponse> list(String search) {
        List<Group> found = search == null || search.isBlank()
                ? groups.findAll()
                : groups.findAll().stream()
                        .filter(group -> matches(group, search.trim()))
                        .toList();
        return found.stream()
                .sorted(Comparator.comparing(Group::getName, String.CASE_INSENSITIVE_ORDER))
                .map(group -> GroupDtos.GroupResponse.from(group, memberships.countByGroupId(group.getId())))
                .toList();
    }

    /** The picker feed: enabled groups only, minimal fields. */
    public List<GroupDtos.GroupSummary> available() {
        return groups.findByEnabledTrueOrderByNameAsc().stream()
                .map(GroupDtos.GroupSummary::from)
                .toList();
    }

    public GroupDtos.GroupResponse get(String groupId) {
        Group group = require(groupId);
        return GroupDtos.GroupResponse.from(group, memberships.countByGroupId(groupId));
    }

    public GroupDtos.GroupResponse create(GroupDtos.CreateGroup request, HttpServletRequest httpRequest) {
        String name = request.name().trim();
        if (groups.existsByName(name)) {
            throw OperationNotAllowedException.conflict("A group named '" + name + "' already exists");
        }

        Group group = new Group();
        group.setName(name);
        group.setDescription(trimToNull(request.description()));
        group.setPermissions(request.permissions() == null ? Set.of() : request.permissions());
        group.setEnabled(request.enabled() == null || request.enabled());
        group.setCreatedBy(CurrentUser.userId().orElse("system"));
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        CurrentUser.principal().ifPresent(principal -> group.setTenantId(principal.getTenantId()));

        Group saved = save(group);
        record(SecurityAuditEvent.GROUP_CREATED, saved, httpRequest,
                Map.of("name", saved.getName(), "permissions", names(saved.permissionSet())));
        log.info("Created group '{}' with {} permission(s)", saved.getName(), saved.getPermissions().size());
        return GroupDtos.GroupResponse.from(saved, 0);
    }

    public GroupDtos.GroupResponse update(String groupId, GroupDtos.UpdateGroup request,
                                          HttpServletRequest httpRequest) {
        Group group = require(groupId);

        if (request.name() != null && !request.name().isBlank()) {
            String name = request.name().trim();
            if (!name.equals(group.getName()) && groups.existsByName(name)) {
                throw OperationNotAllowedException.conflict("A group named '" + name + "' already exists");
            }
            group.setName(name);
        }
        if (request.description() != null) {
            group.setDescription(trimToNull(request.description()));
        }
        if (request.enabled() != null) {
            group.setEnabled(request.enabled());
        }
        group.setUpdatedBy(CurrentUser.userId().orElse("system"));
        group.setUpdatedAt(Instant.now());

        Group saved = save(group);
        record(SecurityAuditEvent.GROUP_UPDATED, saved, httpRequest,
                Map.of("name", saved.getName(), "enabled", saved.isEnabled()));
        return GroupDtos.GroupResponse.from(saved, memberships.countByGroupId(groupId));
    }

    /**
     * Replaces a group's permissions.
     *
     * <p>Takes effect immediately for every member, on every workflow the group is attached to, because
     * nothing is cached and nothing is in the token. Removing execute here means the next execute attempt is
     * refused, not the one after the token expires.
     */
    public GroupDtos.GroupResponse updatePermissions(String groupId, Set<WorkflowPermission> permissions,
                                                     HttpServletRequest httpRequest) {
        Group group = require(groupId);
        Set<WorkflowPermission> previous = group.permissionSet();

        group.setPermissions(permissions);
        group.setUpdatedBy(CurrentUser.userId().orElse("system"));
        group.setUpdatedAt(Instant.now());
        Group saved = save(group);

        record(SecurityAuditEvent.GROUP_PERMISSION_UPDATED, saved, httpRequest,
                Map.of("from", names(previous), "to", names(saved.permissionSet()),
                        "members", memberships.countByGroupId(groupId)));
        log.info("Group '{}' permissions changed from {} to {}", saved.getName(), previous, saved.getPermissions());
        return GroupDtos.GroupResponse.from(saved, memberships.countByGroupId(groupId));
    }

    /**
     * Deletes a group, its memberships and its attachments.
     *
     * <p>The attachments matter: leaving a deleted group's id on workflows would be invisible dead data that
     * quietly reappears if the id were ever reused.
     */
    public void delete(String groupId, HttpServletRequest httpRequest) {
        Group group = require(groupId);

        List<Workflow> attached = workflows.findByAccessGroupsContaining(groupId);
        for (Workflow workflow : attached) {
            List<String> remaining = new ArrayList<>(workflow.getAccessGroups());
            remaining.remove(groupId);
            workflow.setAccessGroups(remaining);
            workflows.save(workflow);
        }

        long memberCount = memberships.countByGroupId(groupId);
        memberships.deleteByGroupId(groupId);
        groups.delete(group);

        record(SecurityAuditEvent.GROUP_DELETED, group, httpRequest,
                Map.of("name", group.getName(), "membersRemoved", memberCount,
                        "workflowsDetached", attached.size()));
        log.info("Deleted group '{}': removed {} membership(s), detached from {} workflow(s)",
                group.getName(), memberCount, attached.size());
    }

    // -------------------------------------------------------------- membership

    public List<GroupDtos.GroupMember> members(String groupId) {
        require(groupId);
        List<GroupDtos.GroupMember> result = new ArrayList<>();
        for (GroupMembership membership : memberships.findByGroupId(groupId)) {
            users.findById(membership.getUserId()).ifPresent(user -> result.add(new GroupDtos.GroupMember(
                    user.getId(), user.getUsername(), user.getEmail(), displayName(user), user.isEnabled(),
                    membership.getCreatedAt(), membership.getCreatedBy())));
        }
        result.sort(Comparator.comparing(GroupDtos.GroupMember::username, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public void addMember(String groupId, String userId, HttpServletRequest httpRequest) {
        Group group = require(groupId);
        User user = users.findById(userId)
                .orElseThrow(() -> new WorkflowNotFoundException("No user with id '" + userId + "'"));

        if (memberships.existsByUserIdAndGroupId(userId, groupId)) {
            // Idempotent: adding an existing member is not an error, it is a no-op with the intended result.
            return;
        }
        memberships.save(new GroupMembership(groupId, userId, CurrentUser.userId().orElse("system")));

        record(SecurityAuditEvent.GROUP_MEMBER_ADDED, group, httpRequest,
                Map.of("group", group.getName(), "member", user.getUsername(),
                        "grants", names(group.permissionSet())));
        log.info("Added {} to group '{}'", user.getUsername(), group.getName());
    }

    public void removeMember(String groupId, String userId, HttpServletRequest httpRequest) {
        Group group = require(groupId);
        if (!memberships.existsByUserIdAndGroupId(userId, groupId)) {
            return;
        }
        memberships.deleteByUserIdAndGroupId(userId, groupId);

        String username = users.findById(userId).map(User::getUsername).orElse(userId);
        record(SecurityAuditEvent.GROUP_MEMBER_REMOVED, group, httpRequest,
                Map.of("group", group.getName(), "member", username));
        log.info("Removed {} from group '{}'", username, group.getName());
    }

    /** The groups a user belongs to, for the user administration screen. */
    public List<GroupDtos.GroupSummary> groupsOf(String userId) {
        Set<String> ids = new LinkedHashSet<>();
        memberships.findByUserId(userId).forEach(membership -> ids.add(membership.getGroupId()));
        return ids.isEmpty() ? List.of()
                : groups.findAllById(ids).stream()
                        .sorted(Comparator.comparing(Group::getName, String.CASE_INSENSITIVE_ORDER))
                        .map(GroupDtos.GroupSummary::from)
                        .toList();
    }

    // --------------------------------------------------------- workflow access

    public GroupDtos.WorkflowAccessResponse accessOf(String workflowId) {
        Workflow workflow = requireWorkflow(workflowId);
        List<String> attached = workflow.getAccessGroups();

        List<GroupDtos.GroupSummary> resolved = attached.isEmpty() ? List.of()
                : groups.findAllById(attached).stream().map(GroupDtos.GroupSummary::from).toList();

        // Ids on the workflow that no longer resolve, surfaced rather than silently dropped so that
        // "why can this team not see it" has a visible answer.
        Set<String> resolvedIds = new LinkedHashSet<>();
        resolved.forEach(summary -> resolvedIds.add(summary.id()));
        List<String> unresolved = attached.stream().filter(id -> !resolvedIds.contains(id)).toList();

        return new GroupDtos.WorkflowAccessResponse(workflowId, resolved, unresolved, workflow.getOwnerId());
    }

    /**
     * Sets which groups a workflow is shared with.
     *
     * <p>Every id is validated to exist. Accepting an unknown id would store access that silently grants
     * nothing, which looks identical to a permission bug.
     */
    public GroupDtos.WorkflowAccessResponse updateAccess(String workflowId, List<String> groupIds,
                                                         HttpServletRequest httpRequest) {
        Workflow workflow = requireWorkflow(workflowId);

        List<String> requested = groupIds == null ? List.of() : groupIds.stream().distinct().toList();
        List<Group> resolved = requested.isEmpty() ? List.of() : groups.findAllById(requested);
        if (resolved.size() != requested.size()) {
            Set<String> known = new LinkedHashSet<>();
            resolved.forEach(group -> known.add(group.getId()));
            List<String> unknown = requested.stream().filter(id -> !known.contains(id)).toList();
            throw new WorkflowNotFoundException("No group with id " + unknown);
        }

        List<String> previous = new ArrayList<>(workflow.getAccessGroups());
        workflow.setAccessGroups(requested);
        workflow.setUpdatedAt(Instant.now());
        CurrentUser.username().ifPresent(workflow::setUpdatedBy);
        workflows.save(workflow);

        List<String> added = requested.stream().filter(id -> !previous.contains(id)).toList();
        List<String> removed = previous.stream().filter(id -> !requested.contains(id)).toList();

        AuthPrincipal actor = CurrentUser.principal().orElse(null);
        if (!added.isEmpty()) {
            audit.administrative(SecurityAuditEvent.WORKFLOW_GROUP_ATTACHED,
                    actor == null ? null : actor.getUserId(), actor == null ? "system" : actor.getUsername(),
                    workflowId, workflow.getName(), httpRequest, Map.of("groups", added));
        }
        if (!removed.isEmpty()) {
            audit.administrative(SecurityAuditEvent.WORKFLOW_GROUP_REMOVED,
                    actor == null ? null : actor.getUserId(), actor == null ? "system" : actor.getUsername(),
                    workflowId, workflow.getName(), httpRequest, Map.of("groups", removed));
        }
        log.info("Workflow {} access groups: {} attached, {} removed", workflowId, added.size(), removed.size());
        return accessOf(workflowId);
    }

    /** What the calling user may do to a workflow, for the console to gate its controls. */
    public GroupDtos.MyPermissions myPermissions(String workflowId) {
        Workflow workflow = requireWorkflow(workflowId);
        String userId = CurrentUser.userId().orElse(null);

        Set<WorkflowPermission> effective = authorization.effectivePermissions(userId, workflowId);

        // Only the groups that actually granted something, which is what makes this useful for answering
        // "why do I have this permission".
        Set<String> attached = new LinkedHashSet<>(workflow.getAccessGroups());
        List<GroupDtos.GroupSummary> via = new ArrayList<>();
        if (userId != null && !attached.isEmpty()) {
            for (GroupMembership membership : memberships.findByUserId(userId)) {
                if (attached.contains(membership.getGroupId())) {
                    groups.findById(membership.getGroupId())
                            .filter(Group::isEnabled)
                            .ifPresent(group -> via.add(GroupDtos.GroupSummary.from(group)));
                }
            }
        }

        return new GroupDtos.MyPermissions(workflowId, effective, via,
                userId != null && userId.equals(workflow.getOwnerId()), CurrentUser.isAdmin());
    }

    // ------------------------------------------------------------------ helpers

    private Group require(String groupId) {
        return groups.findById(groupId)
                .orElseThrow(() -> new WorkflowNotFoundException("No group with id '" + groupId + "'"));
    }

    private Workflow requireWorkflow(String workflowId) {
        return workflows.findById(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException("No workflow with id '" + workflowId + "'"));
    }

    private Group save(Group group) {
        try {
            return groups.save(group);
        } catch (DuplicateKeyException ex) {
            // The unique index is the real guarantee; this turns its error into the same message the
            // pre-check would have produced when two administrators race.
            throw OperationNotAllowedException.conflict("A group named '" + group.getName() + "' already exists");
        }
    }

    private void record(SecurityAuditEvent event, Group group, HttpServletRequest request,
                        Map<String, Object> details) {
        AuthPrincipal actor = CurrentUser.principal().orElse(null);
        audit.administrative(event,
                actor == null ? null : actor.getUserId(),
                actor == null ? "system" : actor.getUsername(),
                group.getId(), group.getName(), request, details);
    }

    private static boolean matches(Group group, String term) {
        String needle = term.toLowerCase(java.util.Locale.ROOT);
        return group.getName().toLowerCase(java.util.Locale.ROOT).contains(needle)
                || (group.getDescription() != null
                        && group.getDescription().toLowerCase(java.util.Locale.ROOT).contains(needle));
    }

    private static List<String> names(Set<WorkflowPermission> permissions) {
        return permissions.stream().map(Enum::name).sorted().toList();
    }

    private static String displayName(User user) {
        String full = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
