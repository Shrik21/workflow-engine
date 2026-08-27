package com.orchpilot.workflow.access;

import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.repository.WorkflowExecutionRepository;
import com.orchpilot.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The intersection-then-union calculation at the centre of group-based access.
 *
 * <p>Built around the worked example from the specification, because that example encodes the properties that
 * matter: a group the user is in but the workflow is not shared with must contribute nothing, and a group the
 * workflow is shared with but the user is not in must contribute nothing either. Getting either direction
 * wrong produces a system that looks correct in a demo and grants too much in practice.
 */
class GroupAuthorizationTest {

    private WorkflowRepository workflows;
    private GroupRepository groups;
    private GroupMembershipRepository memberships;
    private UserRepository users;
    private WorkflowExecutionRepository executions;
    private DefaultWorkflowAuthorizationService authorization;

    private final List<Group> allGroups = new ArrayList<>();
    private final List<GroupMembership> allMemberships = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workflows = mock(WorkflowRepository.class);
        groups = mock(GroupRepository.class);
        memberships = mock(GroupMembershipRepository.class);
        users = mock(UserRepository.class);
        executions = mock(WorkflowExecutionRepository.class);
        authorization = new DefaultWorkflowAuthorizationService(workflows, groups, memberships, users, executions);

        when(memberships.findByUserId(anyString())).thenAnswer((Answer<List<GroupMembership>>) call ->
                allMemberships.stream()
                        .filter(m -> m.getUserId().equals(call.getArgument(0)))
                        .toList());

        when(groups.findByIdInAndEnabledTrue(any())).thenAnswer((Answer<List<Group>>) call -> {
            java.util.Collection<String> ids = call.getArgument(0);
            return allGroups.stream()
                    .filter(group -> ids.contains(group.getId()) && group.isEnabled())
                    .toList();
        });
    }

    private Group givenGroup(String id, boolean enabled, WorkflowPermission... permissions) {
        Group group = new Group();
        group.setId(id);
        group.setName(id);
        group.setEnabled(enabled);
        group.setPermissions(Set.copyOf(Arrays.asList(permissions)));
        allGroups.add(group);
        return group;
    }

    private void givenMember(String userId, String groupId) {
        allMemberships.add(new GroupMembership(groupId, userId, "admin"));
    }

    private void givenUser(String id, Role... roles) {
        User user = new User();
        user.setId(id);
        user.setUsername(id);
        user.setEnabled(true);
        user.setRoles(Set.copyOf(Arrays.asList(roles)));
        when(users.findById(id)).thenReturn(Optional.of(user));
    }

    private Workflow givenWorkflow(String id, String ownerId, String... accessGroups) {
        Workflow workflow = new Workflow();
        workflow.setId(id);
        workflow.setName(id);
        workflow.setOwnerId(ownerId);
        workflow.setAccessGroups(List.of(accessGroups));
        when(workflows.findById(id)).thenReturn(Optional.of(workflow));
        return workflow;
    }

    @Nested
    @DisplayName("The worked example")
    class WorkedExample {

        @BeforeEach
        void seed() {
            givenGroup("developers", true, WorkflowPermission.WORKFLOW_VIEW, WorkflowPermission.WORKFLOW_EDIT,
                    WorkflowPermission.WORKFLOW_EXECUTE, WorkflowPermission.EXECUTION_VIEW);
            givenGroup("finance", true, WorkflowPermission.WORKFLOW_VIEW, WorkflowPermission.WORKFLOW_EXECUTE,
                    WorkflowPermission.EXECUTION_VIEW);
            givenGroup("operators", true, WorkflowPermission.WORKFLOW_VIEW, WorkflowPermission.WORKFLOW_EXECUTE,
                    WorkflowPermission.EXECUTION_VIEW, WorkflowPermission.EXECUTION_CANCEL,
                    WorkflowPermission.EXECUTION_RETRY);

            givenUser("vivek", Role.USER);
            givenUser("rahul", Role.USER);
            givenUser("amit", Role.USER);

            givenMember("vivek", "developers");
            givenMember("rahul", "developers");
            givenMember("rahul", "finance");
            givenMember("amit", "finance");
            givenMember("amit", "operators");

            // Shared with developers and finance. Operators is deliberately not attached.
            givenWorkflow("employee-approval", "someone-else", "developers", "finance");
        }

        @Test
        @DisplayName("gives Vivek the developer permissions")
        void vivek() {
            assertThat(authorization.effectivePermissions("vivek", "employee-approval"))
                    .containsExactlyInAnyOrder(
                            WorkflowPermission.WORKFLOW_VIEW, WorkflowPermission.WORKFLOW_EDIT,
                            WorkflowPermission.WORKFLOW_EXECUTE, WorkflowPermission.EXECUTION_VIEW);
        }

        @Test
        @DisplayName("unions both of Rahul's groups")
        void rahul() {
            assertThat(authorization.effectivePermissions("rahul", "employee-approval"))
                    .containsExactlyInAnyOrder(
                            WorkflowPermission.WORKFLOW_VIEW, WorkflowPermission.WORKFLOW_EDIT,
                            WorkflowPermission.WORKFLOW_EXECUTE, WorkflowPermission.EXECUTION_VIEW);
        }

        @Test
        @DisplayName("ignores Amit's operator group because the workflow is not shared with it")
        void amit() {
            // The property that matters most: membership alone grants nothing. Amit is an operator, and
            // operators may cancel and retry, but this workflow was never shared with that group.
            assertThat(authorization.effectivePermissions("amit", "employee-approval"))
                    .containsExactlyInAnyOrder(
                            WorkflowPermission.WORKFLOW_VIEW, WorkflowPermission.WORKFLOW_EXECUTE,
                            WorkflowPermission.EXECUTION_VIEW);

            assertThat(authorization.canCancelExecution("amit", "employee-approval")).isFalse();
            assertThat(authorization.canRetryExecution("amit", "employee-approval")).isFalse();
        }

        @Test
        @DisplayName("denies edit, delete and publish to Amit")
        void amitCannotEdit() {
            assertThat(authorization.canEdit("amit", "employee-approval")).isFalse();
            assertThat(authorization.canDelete("amit", "employee-approval")).isFalse();
            assertThat(authorization.canPublish("amit", "employee-approval")).isFalse();
            assertThat(authorization.canExecute("amit", "employee-approval")).isTrue();
        }

        @Test
        @DisplayName("denies everything to a user in no group")
        void strangerIsDenied() {
            givenUser("stranger", Role.USER);
            assertThat(authorization.effectivePermissions("stranger", "employee-approval")).isEmpty();
            assertThat(authorization.canView("stranger", "employee-approval")).isFalse();
        }
    }

    @Nested
    @DisplayName("Overrides and edge cases")
    class Overrides {

        @Test
        @DisplayName("gives an administrator everything without any group membership")
        void adminOverride() {
            givenUser("root", Role.ADMIN);
            givenWorkflow("private", "someone-else");

            assertThat(authorization.effectivePermissions("root", "private"))
                    .containsExactlyInAnyOrder(WorkflowPermission.values());
            assertThat(authorization.canDelete("root", "private")).isTrue();
            assertThat(authorization.canManageAccess("root", "private")).isTrue();
            assertThat(authorization.visibleWorkflowScope("root").unrestricted()).isTrue();
        }

        @Test
        @DisplayName("a disabled administrator gets no override")
        void disabledAdminHasNoOverride() {
            User disabled = new User();
            disabled.setId("ex-admin");
            disabled.setEnabled(false);
            disabled.setRoles(Set.of(Role.ADMIN));
            when(users.findById("ex-admin")).thenReturn(Optional.of(disabled));
            givenWorkflow("private", "someone-else");

            assertThat(authorization.canView("ex-admin", "private")).isFalse();
        }

        @Test
        @DisplayName("gives the owner their default permissions with no group at all")
        void ownerDefaults() {
            givenUser("owner", Role.USER);
            givenWorkflow("mine", "owner");

            assertThat(authorization.canView("owner", "mine")).isTrue();
            assertThat(authorization.canEdit("owner", "mine")).isTrue();
            assertThat(authorization.canPublish("owner", "mine")).isTrue();
            assertThat(authorization.canDelete("owner", "mine")).isTrue();
            assertThat(authorization.canManageAccess("owner", "mine")).isTrue();

            // Operational duties are not an ownership right; they come from a group like anything else.
            assertThat(authorization.canCancelExecution("owner", "mine")).isFalse();
        }

        @Test
        @DisplayName("leaves an unshared workflow reachable only by its owner and administrators")
        void unsharedWorkflowIsPrivate() {
            givenUser("owner", Role.USER);
            givenUser("other", Role.USER);
            givenGroup("everyone", true, WorkflowPermission.WORKFLOW_VIEW);
            givenMember("other", "everyone");
            givenWorkflow("unshared", "owner");

            // Failing closed. Defaulting an unshared workflow to "any user may view" would have exposed
            // every workflow that existed before groups were introduced.
            assertThat(authorization.canView("other", "unshared")).isFalse();
            assertThat(authorization.canView("owner", "unshared")).isTrue();
        }

        @Test
        @DisplayName("stops granting anything the moment a group is disabled")
        void disabledGroupGrantsNothing() {
            givenUser("member", Role.USER);
            Group group = givenGroup("temporary", true, WorkflowPermission.WORKFLOW_VIEW,
                    WorkflowPermission.WORKFLOW_EXECUTE);
            givenMember("member", "temporary");
            givenWorkflow("shared", "someone-else", "temporary");

            assertThat(authorization.canExecute("member", "shared")).isTrue();

            // The kill switch: revokes access everywhere without deleting the group or its membership.
            group.setEnabled(false);
            assertThat(authorization.canExecute("member", "shared")).isFalse();
            assertThat(authorization.effectivePermissions("member", "shared")).isEmpty();
        }

        @Test
        @DisplayName("unions ownership with group grants rather than replacing them")
        void ownerAlsoInAGroup() {
            givenUser("owner", Role.USER);
            givenGroup("ops", true, WorkflowPermission.EXECUTION_CANCEL, WorkflowPermission.EXECUTION_RETRY);
            givenMember("owner", "ops");
            givenWorkflow("mine", "owner", "ops");

            // Owner defaults plus what the group adds, which is how an owner gains operational rights.
            assertThat(authorization.canEdit("owner", "mine")).isTrue();
            assertThat(authorization.canCancelExecution("owner", "mine")).isTrue();
        }

        @Test
        @DisplayName("refuses sharing changes to a non-owner even when they can edit")
        void editorCannotWidenAccess() {
            givenUser("editor", Role.USER);
            givenGroup("devs", true, WorkflowPermission.WORKFLOW_VIEW, WorkflowPermission.WORKFLOW_EDIT);
            givenMember("editor", "devs");
            givenWorkflow("shared", "someone-else", "devs");

            assertThat(authorization.canEdit("editor", "shared")).isTrue();
            // Otherwise an editor could attach their own groups and widen their own access indefinitely.
            assertThat(authorization.canManageAccess("editor", "shared")).isFalse();
        }

        @Test
        @DisplayName("denies an unknown user, an unknown workflow and a null argument")
        void failsClosed() {
            givenUser("known", Role.USER);
            givenWorkflow("known-workflow", "owner");
            when(workflows.findById("missing")).thenReturn(Optional.empty());
            when(users.findById("ghost")).thenReturn(Optional.empty());

            assertThat(authorization.canView("ghost", "known-workflow")).isFalse();
            assertThat(authorization.canView("known", "missing")).isFalse();
            // Cast needed: canView is overloaded for a user id and for an Authentication, and a bare null
            // matches both. The same ambiguity in production code would be a compile error, which is the
            // right outcome.
            assertThat(authorization.canView((String) null, "known-workflow")).isFalse();
            assertThat(authorization.canView("known", null)).isFalse();
            assertThat(authorization.hasPermission("known", "known-workflow", null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Execution access")
    class Executions {

        @Test
        @DisplayName("makes an execution exactly as private as the workflow that produced it")
        void executionInheritsWorkflowAccess() {
            givenUser("member", Role.USER);
            givenUser("stranger", Role.USER);
            givenGroup("viewers", true, WorkflowPermission.WORKFLOW_VIEW, WorkflowPermission.EXECUTION_VIEW);
            givenMember("member", "viewers");
            givenWorkflow("shared", "someone-else", "viewers");

            WorkflowExecution execution = new WorkflowExecution();
            execution.setId("exec-1");
            execution.setWorkflowId("shared");
            when(executions.findById("exec-1")).thenReturn(Optional.of(execution));

            assertThat(authorization.canViewExecution("member", "shared")).isTrue();
            // Knowing the execution id must not be enough on its own.
            assertThat(authorization.canViewExecution("stranger", "shared")).isFalse();
        }
    }

    @Nested
    @DisplayName("Visible workflow scope")
    class Scope {

        @Test
        @DisplayName("includes only groups that actually grant view")
        void scopeExcludesNonViewingGroups() {
            givenUser("member", Role.USER);
            givenGroup("viewers", true, WorkflowPermission.WORKFLOW_VIEW);
            // Grants execute but not view, so it must not widen what appears in the list.
            givenGroup("runners", true, WorkflowPermission.WORKFLOW_EXECUTE);
            givenMember("member", "viewers");
            givenMember("member", "runners");

            var scope = authorization.visibleWorkflowScope("member");
            assertThat(scope.unrestricted()).isFalse();
            assertThat(scope.ownerId()).isEqualTo("member");
            assertThat(scope.groupIds()).containsExactly("viewers");
        }

        @Test
        @DisplayName("reports an empty scope for a user with no groups, so the query is skipped")
        void emptyScopeStillIncludesOwnedWorkflows() {
            givenUser("loner", Role.USER);
            var scope = authorization.visibleWorkflowScope("loner");

            // Not empty: they still own things. isEmpty() is reserved for "cannot see anything at all".
            assertThat(scope.groupIds()).isEmpty();
            assertThat(scope.isEmpty()).isFalse();
            assertThat(authorization.visibleWorkflowScope(null).isEmpty()).isTrue();
        }
    }
}
