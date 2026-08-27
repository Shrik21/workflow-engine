package com.orchpilot.workflow.task;

import com.orchpilot.workflow.access.Group;
import com.orchpilot.workflow.access.GroupMembership;
import com.orchpilot.workflow.access.GroupMembershipRepository;
import com.orchpilot.workflow.access.GroupRepository;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.service.OperationNotAllowedException;
import com.orchpilot.workflow.dto.FormSubmissionRequest;
import com.orchpilot.workflow.exception.FormSubmissionInvalidException;
import com.orchpilot.workflow.exception.WorkflowNotFoundException;
import com.orchpilot.workflow.forms.DefaultFormVariableMapper;
import com.orchpilot.workflow.forms.FormDefinitionService;
import com.orchpilot.workflow.forms.FormField;
import com.orchpilot.workflow.forms.FormFieldType;
import com.orchpilot.workflow.forms.FormNodeBinding;
import com.orchpilot.workflow.forms.FormValidationService;
import com.orchpilot.workflow.forms.FormVersion;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.ExecutionService;
import com.orchpilot.workflow.task.dto.TaskResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Human task behaviour: assignment, the claim race, who may submit, and what a submission does.
 *
 * <p>Repositories are mocked over a {@code HashMap} rather than replaced by a real MongoDB. What is under test
 * here is decision logic — who may do what, in which order, and what is written — and that is exactly what a
 * container adds nothing to. The queries themselves are exercised against a real database by the integration
 * tests.
 */
class HumanTaskEngineTest {

    private static final String FINANCE = "group-finance";

    private final Map<String, HumanTask> stored = new LinkedHashMap<>();
    private final List<TaskHistoryEntry> written = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    private HumanTaskRepository taskRepository;
    private TaskHistoryRepository historyRepository;
    private GroupMembershipRepository memberships;
    private UserRepository users;
    private TaskAuthorizationService authorization;
    private HumanTaskService service;
    private ExecutionService executions;
    private FormNodeBinding binding;
    private TaskCompletionService completion;

    private User approver;
    private User bystander;
    private User administrator;

    @BeforeEach
    void setUp() {
        taskRepository = mock(HumanTaskRepository.class);
        historyRepository = mock(TaskHistoryRepository.class);
        memberships = mock(GroupMembershipRepository.class);
        users = mock(UserRepository.class);
        executions = mock(ExecutionService.class);
        binding = mock(FormNodeBinding.class);

        when(taskRepository.save(any())).thenAnswer(call -> {
            HumanTask task = call.getArgument(0);
            if (task.getId() == null) {
                task.setId("task-" + ids.incrementAndGet());
            }
            stored.put(task.getId(), task);
            return task;
        });
        when(taskRepository.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0, String.class))));
        when(taskRepository.findByWorkflowExecutionIdAndNodeIdOrderByAttemptDesc(anyString(), anyString()))
                .thenAnswer(call -> stored.values().stream()
                        .filter(task -> task.getWorkflowExecutionId().equals(call.getArgument(0))
                                && task.getNodeId().equals(call.getArgument(1)))
                        .sorted((left, right) -> right.getAttempt() - left.getAttempt())
                        .toList());
        when(taskRepository.findByWorkflowExecutionIdAndStatusIn(anyString(), any()))
                .thenAnswer(call -> stored.values().stream()
                        .filter(task -> task.getWorkflowExecutionId().equals(call.getArgument(0)))
                        .filter(task -> call.getArgument(1, java.util.Collection.class)
                                .contains(task.getStatus()))
                        .toList());
        when(historyRepository.save(any())).thenAnswer(call -> {
            written.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(historyRepository.findByTaskIdOrderByAtAsc(anyString()))
                .thenAnswer(call -> written.stream()
                        .filter(entry -> call.getArgument(0).equals(entry.getTaskId()))
                        .toList());

        approver = user("user-approver", "approver", Role.USER);
        bystander = user("user-bystander", "bystander", Role.USER);
        administrator = user("user-admin", "admin", Role.ADMIN);
        when(users.findById("user-approver")).thenReturn(Optional.of(approver));
        when(users.findById("user-bystander")).thenReturn(Optional.of(bystander));
        when(users.findByUsername("approver")).thenReturn(Optional.of(approver));
        when(users.findByUsername("bystander")).thenReturn(Optional.of(bystander));
        when(users.findById(anyString())).thenAnswer(call -> switch (call.getArgument(0, String.class)) {
            case "user-approver" -> Optional.of(approver);
            case "user-bystander" -> Optional.of(bystander);
            case "user-admin" -> Optional.of(administrator);
            default -> Optional.empty();
        });

        // Only the approver is in Finance. The bystander is a signed-in user with no claim on the task.
        when(memberships.findByUserId("user-approver")).thenReturn(List.of(membership("user-approver", FINANCE)));
        when(memberships.findByUserId("user-bystander")).thenReturn(List.of());
        when(memberships.findByUserId("user-admin")).thenReturn(List.of());

        authorization = new TaskAuthorizationService(memberships);
        service = new HumanTaskService(taskRepository, historyRepository, authorization, users,
                new LoggingTaskNotifier(), mock(AuditService.class), mock(ApplicationEventPublisher.class));
        completion = new TaskCompletionService(service, taskRepository, authorization, binding, executions,
                new LoggingTaskNotifier(), mock(AuditService.class));

        WorkflowExecution waiting = new WorkflowExecution();
        waiting.setId("exec-1");
        waiting.setStatus(ExecutionStatus.WAITING);
        when(executions.get("exec-1")).thenReturn(waiting);
    }

    // ------------------------------------------------------------------------ creation

    @Nested
    @DisplayName("Raising a task")
    class Creation {

        @Test
        @DisplayName("re-entering the same node returns the same task instead of raising another")
        void createIsIdempotent() {
            HumanTask first = service.createOrReuse(request(offeredToFinance()));
            HumanTask second = service.createOrReuse(request(offeredToFinance()));

            assertSame(first, second, "a retry or a resume must not put a second copy in somebody's inbox");
            assertEquals(1, stored.size());
        }

        @Test
        @DisplayName("a task offered to a group starts OPEN, one addressed to a person starts ASSIGNED")
        void initialStatusFollowsAssignment() {
            HumanTask offered = service.createOrReuse(request(offeredToFinance()));
            assertEquals(TaskStatus.OPEN, offered.getStatus());
            assertNull(offered.getAssigneeUserId());

            stored.clear();
            HumanTask addressed = service.createOrReuse(request(assignedToApprover()));
            assertEquals(TaskStatus.ASSIGNED, addressed.getStatus());
            assertEquals("user-approver", addressed.getAssigneeUserId());
            assertEquals(addressed.getCreatedAt(), addressed.getClaimedAt(),
                    "a directly assigned task is held from the moment it exists");
        }

        @Test
        @DisplayName("a loop back to a finished node raises a second task rather than colliding with the first")
        void loopingRaisesANewAttempt() {
            HumanTask first = service.createOrReuse(request(offeredToFinance()));
            first.setStatus(TaskStatus.COMPLETED);

            HumanTask second = service.createOrReuse(request(offeredToFinance()));

            assertEquals(1, first.getAttempt());
            assertEquals(2, second.getAttempt(), "the unique index is on (execution, node, attempt)");
            assertEquals(2, stored.size());
        }

        @Test
        @DisplayName("deadlines are stored as instants, not as durations")
        void deadlinesAreResolvedAtCreation() {
            TaskAssignment withDeadlines = new TaskAssignment("user-approver", "approver", List.of(),
                    List.of(), TaskPriority.HIGH, Duration.ofHours(4), Duration.ofDays(2), List.of(), false);

            HumanTask task = service.createOrReuse(request(withDeadlines));

            assertTrue(task.getDueAt().isAfter(Instant.now()));
            assertTrue(task.getExpiresAt().isAfter(task.getDueAt()));
            assertEquals(TaskPriority.HIGH.weight(), task.getPriorityWeight(),
                    "the numeric weight is what MongoDB sorts on");
        }
    }

    // -------------------------------------------------------------------- the claim race

    @Nested
    @DisplayName("Claiming")
    class Claiming {

        @Test
        @DisplayName("a candidate can claim an open task")
        void candidateClaims() {
            HumanTask task = service.createOrReuse(request(offeredToFinance()));

            HumanTask claimed = service.claim(task.getId(), principal(approver));

            assertEquals(TaskStatus.ASSIGNED, claimed.getStatus());
            assertEquals("approver", claimed.getAssigneeUsername());
            assertTrue(written.stream().anyMatch(entry -> entry.getAction() == TaskAction.CLAIMED));
        }

        @Test
        @DisplayName("somebody outside the candidate groups cannot even see it")
        void bystanderCannotSeeIt() {
            HumanTask task = service.createOrReuse(request(offeredToFinance()));

            // 404 rather than 403 on purpose: answering "forbidden" would confirm the id names a real task.
            assertThrows(WorkflowNotFoundException.class,
                    () -> service.claim(task.getId(), principal(bystander)));
            assertEquals(TaskStatus.OPEN, stored.get(task.getId()).getStatus());
        }

        @Test
        @DisplayName("the second claim is refused with a message that says what happened")
        void secondClaimIsRefused() {
            HumanTask task = service.createOrReuse(request(offeredToFinance()));
            service.claim(task.getId(), principal(approver));

            OperationNotAllowedException refusal = assertThrows(OperationNotAllowedException.class,
                    () -> service.claim(task.getId(), principal(administrator)));

            assertTrue(refusal.getMessage().contains("claimed this task first"), refusal.getMessage());
            assertEquals("user-approver", stored.get(task.getId()).getAssigneeUserId(),
                    "the loser of the race must not overwrite the winner");
        }

        @Test
        @DisplayName("releasing a task discards the previous holder's draft")
        void releaseDiscardsTheDraft() {
            HumanTask task = service.createOrReuse(request(offeredToFinance()));
            service.claim(task.getId(), principal(approver));
            service.saveDraft(task.getId(), Map.of("comments", "half written"), principal(approver));

            HumanTask released = service.release(task.getId(), principal(approver));

            assertEquals(TaskStatus.OPEN, released.getStatus());
            assertNull(released.getAssigneeUserId());
            assertTrue(released.getDraftData().isEmpty(),
                    "a half-filled form is working notes, and handing it on would leak them");
        }
    }

    // ---------------------------------------------------------------------- completion

    @Nested
    @DisplayName("Completing")
    class Completing {

        @BeforeEach
        void publishTheForm() {
            when(binding.resolve(eq("form-approval"), any())).thenReturn(Optional.of(approvalForm()));
            // The real binding delegates to FormValidationService; wiring the real one keeps the rules honest.
            FormValidationService validation = new FormValidationService();
            when(binding.resolve(anyString(), any())).thenAnswer(call ->
                    "form-approval".equals(call.getArgument(0))
                            ? Optional.of(approvalForm()) : Optional.empty());
            org.mockito.Mockito.doAnswer(call -> {
                Map<String, List<String>> problems = validation.validate(call.getArgument(0),
                        call.getArgument(1));
                if (!problems.isEmpty()) {
                    throw new FormSubmissionInvalidException(problems);
                }
                return null;
            }).when(binding).validateOrThrow(any(FormVersion.class), any());
        }

        @Test
        @DisplayName("the assignee submits, the task closes, and the execution is resumed")
        void assigneeCompletes() {
            HumanTask task = claimedTask();

            HumanTask completed = completion.complete(task.getId(),
                    Map.of("approved", true, "comments", "fine"), principal(approver));

            assertEquals(TaskStatus.COMPLETED, completed.getStatus());
            assertEquals("approver", completed.getCompletedByUsername());
            verify(executions).submitSignal(eq("exec-1"), any(FormSubmissionRequest.class), eq("approver"));
        }

        @Test
        @DisplayName("an invalid submission is refused and the task stays open")
        void invalidSubmissionIsRefused() {
            HumanTask task = claimedTask();

            // 'approved' is required by the form and absent from the payload.
            assertThrows(FormSubmissionInvalidException.class, () ->
                    completion.complete(task.getId(), Map.of("comments", "fine"), principal(approver)));

            assertEquals(TaskStatus.ASSIGNED, stored.get(task.getId()).getStatus());
            verify(executions, never()).submitSignal(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("an administrator cannot submit somebody else's task")
        void administratorCannotSubmitForSomebodyElse() {
            HumanTask task = claimedTask();

            /*
             * OperationNotAllowedException rather than AccessDeniedException, and that is the assertion: the
             * global handler flattens AccessDeniedException to a generic message, which would hide the one
             * sentence the administrator needs.
             */
            OperationNotAllowedException refusal = assertThrows(OperationNotAllowedException.class,
                    () -> completion.complete(task.getId(), Map.of("approved", true),
                            principal(administrator)));

            assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, refusal.getStatus());
            assertTrue(refusal.getMessage().contains("Reassign it to yourself"),
                    "the refusal has to say what to do instead: " + refusal.getMessage());
            verify(executions, never()).submitSignal(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("an unclaimed task must be claimed before it can be submitted")
        void openTaskCannotBeSubmitted() {
            HumanTask task = service.createOrReuse(request(offeredToFinance()));

            OperationNotAllowedException refusal = assertThrows(OperationNotAllowedException.class,
                    () -> completion.complete(task.getId(), Map.of("approved", true), principal(approver)));

            assertTrue(refusal.getMessage().contains("Claim this task"), refusal.getMessage());
        }

        @Test
        @DisplayName("a task cannot be submitted twice")
        void completingTwiceIsRefused() {
            HumanTask task = claimedTask();
            completion.complete(task.getId(), Map.of("approved", true), principal(approver));

            assertThrows(OperationNotAllowedException.class, () ->
                    completion.complete(task.getId(), Map.of("approved", false), principal(approver)));
        }

        @Test
        @DisplayName("the submitted values are recorded, and the history records only field names")
        void historyDoesNotCarryValues() {
            HumanTask task = claimedTask();

            completion.complete(task.getId(), Map.of("approved", true, "comments", "confidential"),
                    principal(approver));

            assertEquals(true, stored.get(task.getId()).getSubmittedData().get("approved"));
            TaskHistoryEntry entry = written.stream()
                    .filter(item -> item.getAction() == TaskAction.COMPLETED)
                    .findFirst()
                    .orElseThrow();
            assertFalse(entry.getDetails().toString().contains("confidential"),
                    "an approval trail is read by more people than the task is");
            assertTrue(entry.getDetails().get("fields").toString().contains("comments"));
        }

        private HumanTask claimedTask() {
            HumanTask task = service.createOrReuse(request(offeredToFinance()));
            return service.claim(task.getId(), principal(approver));
        }
    }

    // -------------------------------------------------------------------- expiry and cancel

    @Nested
    @DisplayName("Deadlines and withdrawal")
    class Deadlines {

        @Test
        @DisplayName("an expired task is closed and its execution cancelled")
        void expiryCancelsTheExecution() {
            HumanTask task = service.createOrReuse(request(assignedToApprover()));
            task.setExpiresAt(Instant.now().minusSeconds(60));

            completion.expire(task);

            assertEquals(TaskStatus.EXPIRED, stored.get(task.getId()).getStatus());
            verify(executions).cancel("exec-1", "system");
        }

        @Test
        @DisplayName("a submission after expiry is refused")
        void expiredTaskCannotBeSubmitted() {
            HumanTask task = service.createOrReuse(request(assignedToApprover()));
            task.setExpiresAt(Instant.now().minusSeconds(60));

            assertThrows(OperationNotAllowedException.class, () ->
                    completion.complete(task.getId(), Map.of("approved", true), principal(approver)));
        }

        @Test
        @DisplayName("cancelling a task cancels the execution, because nobody will decide the step")
        void cancellingCancelsTheExecution() {
            HumanTask task = service.createOrReuse(request(assignedToApprover()));

            completion.cancel(task.getId(), "Requester withdrew it", principal(administrator));

            assertEquals(TaskStatus.CANCELLED, stored.get(task.getId()).getStatus());
            assertEquals("Requester withdrew it", stored.get(task.getId()).getCancelReason());
            verify(executions).cancel("exec-1", "system");
        }

        @Test
        @DisplayName("an ordinary user cannot cancel a task")
        void userCannotCancel() {
            HumanTask task = service.createOrReuse(request(assignedToApprover()));

            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> completion.cancel(task.getId(), "no", principal(approver)));
        }

        @Test
        @DisplayName("an execution that ends closes the tasks it left behind")
        void endingAnExecutionClosesItsTasks() {
            HumanTask task = service.createOrReuse(request(assignedToApprover()));

            int closed = completion.cancelTasksFor("exec-1", "The execution was cancelled");

            assertEquals(1, closed);
            assertEquals(TaskStatus.CANCELLED, stored.get(task.getId()).getStatus());
        }
    }

    // ------------------------------------------------------------------------ prefill

    @Nested
    @DisplayName("What the form opens with")
    class InitialData {

        @Test
        @DisplayName("a saved draft overrides the prefill key by key, not wholesale")
        void draftOverlaysPrefill() {
            HumanTask task = new HumanTask();
            task.setPrefill(Map.of("employeeName", "Vivek", "salary", 120000));
            task.setDraftData(Map.of("salary", 130000));

            Map<String, Object> initial = TaskResponses.initialDataFor(task);

            assertEquals("Vivek", initial.get("employeeName"),
                    "a field the user never touched keeps the workflow's current answer");
            assertEquals(130000, initial.get("salary"));
        }
    }

    // ------------------------------------------------------------------- reassignment

    @Nested
    @DisplayName("Reassignment")
    class Reassignment {

        @Test
        @DisplayName("an administrator can move a task, and the history says from whom to whom")
        void administratorReassigns() {
            HumanTask task = service.createOrReuse(request(assignedToApprover()));

            HumanTask moved = service.reassign(task.getId(), "bystander", "on leave",
                    principal(administrator));

            assertEquals("user-bystander", moved.getAssigneeUserId());
            TaskHistoryEntry entry = written.stream()
                    .filter(item -> item.getAction() == TaskAction.REASSIGNED)
                    .findFirst()
                    .orElseThrow();
            assertEquals("approver", entry.getDetails().get("from"));
            assertEquals("bystander", entry.getDetails().get("to"));
            assertEquals("on leave", entry.getComment());
        }

        @Test
        @DisplayName("somebody with no claim on the task cannot move it")
        void bystanderCannotReassign() {
            HumanTask task = service.createOrReuse(request(assignedToApprover()));

            assertThrows(WorkflowNotFoundException.class,
                    () -> service.reassign(task.getId(), "bystander", null, principal(bystander)));
        }

        @Test
        @DisplayName("an unknown assignee is refused rather than silently leaving the task where it was")
        void unknownAssigneeIsRefused() {
            HumanTask task = service.createOrReuse(request(assignedToApprover()));

            assertThrows(OperationNotAllowedException.class,
                    () -> service.reassign(task.getId(), "nobody", null, principal(administrator)));
            assertEquals("user-approver", stored.get(task.getId()).getAssigneeUserId());
        }
    }

    // ------------------------------------------------------------- assignment resolution

    @Nested
    @DisplayName("Resolving a node's assignment configuration")
    class AssignmentResolution {

        private TaskAssignmentResolver resolver;

        @BeforeEach
        void setUpResolver() {
            GroupRepository groups = mock(GroupRepository.class);
            Group finance = new Group();
            finance.setId(FINANCE);
            finance.setName("Finance approvers");
            finance.setEnabled(true);
            Group retired = new Group();
            retired.setId("group-retired");
            retired.setName("Retired");
            retired.setEnabled(false);

            when(groups.findById(FINANCE)).thenReturn(Optional.of(finance));
            when(groups.findByName("Finance approvers")).thenReturn(Optional.of(finance));
            when(groups.findByName("Retired")).thenReturn(Optional.of(retired));
            when(groups.findById(anyString())).thenAnswer(call ->
                    FINANCE.equals(call.getArgument(0)) ? Optional.of(finance)
                            : "group-retired".equals(call.getArgument(0)) ? Optional.of(retired)
                            : Optional.empty());
            when(groups.findByName(anyString())).thenAnswer(call -> switch (
                    call.getArgument(0, String.class)) {
                case "Finance approvers" -> Optional.of(finance);
                case "Retired" -> Optional.of(retired);
                default -> Optional.empty();
            });

            resolver = new TaskAssignmentResolver(users, groups);
        }

        @Test
        @DisplayName("a group can be named or identified, and either resolves to its id")
        void groupsResolveByNameOrId() {
            assertEquals(List.of(FINANCE),
                    resolver.resolve(Map.of("candidateGroups", List.of("Finance approvers")))
                            .candidateGroupIds());
            assertEquals(List.of(FINANCE),
                    resolver.resolve(Map.of("candidateGroupIds", List.of(FINANCE))).candidateGroupIds());
        }

        @Test
        @DisplayName("an assignee can be a username or a user id")
        void assigneeResolvesByNameOrId() {
            assertEquals("user-approver",
                    resolver.resolve(Map.of("assignee", "approver")).assigneeUserId());
            assertEquals("user-approver",
                    resolver.resolve(Map.of("assignee", "user-approver")).assigneeUserId());
        }

        @Test
        @DisplayName("a placeholder that resolved to nothing is reported as a variable problem, not as a "
                + "missing account")
        void unresolvedPlaceholderIsReportedAsSuch() {
            TaskAssignment assignment = resolver.resolve(Map.of("assignee", "${manager.username}"));

            assertNull(assignment.assigneeUserId());
            assertTrue(assignment.problems().stream()
                            .anyMatch(problem -> problem.contains("no such workflow variable")),
                    "pointing at the account list would send the reader to the wrong place: "
                            + assignment.problems());
        }

        @Test
        @DisplayName("a disabled group is ignored, because nobody could claim through it")
        void disabledGroupIsIgnored() {
            TaskAssignment assignment = resolver.resolve(Map.of("candidateGroups", List.of("Retired")));

            assertTrue(assignment.candidateGroupIds().isEmpty());
            assertTrue(assignment.problems().stream().anyMatch(problem -> problem.contains("is disabled")));
        }

        @Test
        @DisplayName("a task addressed to nobody says so, because only an administrator will find it")
        void unaddressedTaskIsFlagged() {
            TaskAssignment assignment = resolver.resolve(Map.of());

            assertFalse(assignment.isAddressed());
            assertTrue(assignment.problems().stream()
                    .anyMatch(problem -> problem.contains("names no assignee")));
        }

        @Test
        @DisplayName("a single string is accepted where a list was meant")
        void singleStringIsAcceptedAsAList() {
            assertEquals(List.of(FINANCE),
                    resolver.resolve(Map.of("candidateGroups", "Finance approvers")).candidateGroupIds());
        }

        @Test
        @DisplayName("comma-separated text is split, because that is what the property panel writes")
        void commaSeparatedTextIsSplit() {
            TaskAssignment assignment =
                    resolver.resolve(Map.of("candidateGroups", "Finance approvers, Retired"));

            // Both were looked up: the enabled one resolved, the disabled one was reported. Treating the whole
            // string as one name would instead have produced a task addressed to a group that does not exist.
            assertEquals(List.of(FINANCE), assignment.candidateGroupIds());
            assertTrue(assignment.problems().stream().anyMatch(problem -> problem.contains("is disabled")));
        }

        @Test
        @DisplayName("a nonsensical deadline is dropped with a problem rather than failing the node")
        void badDeadlineIsDropped() {
            TaskAssignment assignment = resolver.resolve(Map.of("dueInSeconds", "soon"));

            assertNull(assignment.dueIn());
            assertTrue(assignment.problems().stream()
                    .anyMatch(problem -> problem.contains("not a number of seconds")));
        }
    }

    // -------------------------------------------------------------------- field mapping

    @Nested
    @DisplayName("Mapping fields to variables")
    class Mapping {

        private final DefaultFormVariableMapper mapper = new DefaultFormVariableMapper();

        @Test
        @DisplayName("dotted paths are returned flat so a caller writes one variable at a time")
        void pathsAreFlat() {
            Map<String, Object> paths = mapper.mapFormDataToVariablePaths(approvalForm(),
                    Map.of("approved", "true", "comments", "fine"));

            assertEquals(Boolean.TRUE, paths.get("approval.approved"));
            assertEquals("fine", paths.get("approval.comments"));
        }

        @Test
        @DisplayName("the nested and flat forms agree")
        void nestedAgreesWithFlat() {
            Map<String, Object> nested = mapper.mapFormDataToVariables(approvalForm(),
                    Map.of("approved", "true"));

            @SuppressWarnings("unchecked")
            Map<String, Object> approval = (Map<String, Object>) nested.get("approval");
            assertEquals(Boolean.TRUE, approval.get("approved"));
        }

        @Test
        @DisplayName("a key matching no field reaches no variable")
        void unknownKeysAreIgnored() {
            Map<String, Object> paths = mapper.mapFormDataToVariablePaths(approvalForm(),
                    Map.of("isAdmin", true));

            assertTrue(paths.isEmpty(), "a crafted payload must not be able to nominate a variable");
        }
    }

    // ------------------------------------------------------------------------- fixtures

    private TaskCreationRequest request(TaskAssignment assignment) {
        return new TaskCreationRequest("exec-1", "wf-1", 3, "Employee approval", "form-node", "Approve",
                "form-approval", 2, "Approve Vivek", "Check the numbers",
                Map.of("employeeName", "Vivek"), assignment, "requester", "corr-1");
    }

    private TaskAssignment offeredToFinance() {
        return new TaskAssignment(null, null, List.of(), List.of(FINANCE), TaskPriority.NORMAL, null, null,
                List.of(), false);
    }

    private TaskAssignment assignedToApprover() {
        return new TaskAssignment("user-approver", "approver", List.of(), List.of(FINANCE),
                TaskPriority.NORMAL, null, null, List.of(), false);
    }

    private static AuthPrincipal principal(User user) {
        return AuthPrincipal.of(user);
    }

    private static User user(String id, String username, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setRoles(java.util.Set.of(role));
        user.setEnabled(true);
        return user;
    }

    private static GroupMembership membership(String userId, String groupId) {
        GroupMembership membership = new GroupMembership();
        membership.setUserId(userId);
        membership.setGroupId(groupId);
        return membership;
    }

    /** A two-field approval form: a required checkbox and an optional comment. */
    private static FormVersion approvalForm() {
        FormVersion version = new FormVersion();
        version.setId("form-version-2");
        version.setFormDefinitionId("form-approval");
        version.setVersion(2);
        version.setName("Approval");
        version.setTitle("Approve this request");

        FormField approved = new FormField();
        approved.setId("f1");
        approved.setName("approved");
        approved.setLabel("Approved");
        approved.setType(FormFieldType.CHECKBOX);
        approved.setVariable("approval.approved");
        approved.setVariableType(FormFieldType.DataType.BOOLEAN);
        approved.getValidation().setRequired(true);

        FormField comments = new FormField();
        comments.setId("f2");
        comments.setName("comments");
        comments.setLabel("Comments");
        comments.setType(FormFieldType.TEXTAREA);
        comments.setVariable("approval.comments");
        comments.setVariableType(FormFieldType.DataType.STRING);

        version.setFields(List.of(approved, comments));
        return version;
    }
}
