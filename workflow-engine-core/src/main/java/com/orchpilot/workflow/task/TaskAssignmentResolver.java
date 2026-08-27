package com.orchpilot.workflow.task;

import com.orchpilot.workflow.access.Group;
import com.orchpilot.workflow.access.GroupRepository;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a form node's configuration into a resolved {@link TaskAssignment}.
 *
 * <h2>What a workflow author may write</h2>
 *
 * <pre>{@code
 * {
 *   "assignee":         "${manager.username}",   // a username, an id, or a placeholder for either
 *   "candidateGroups":  ["Finance approvers"],   // names or ids
 *   "candidateUsers":   ["vivek"],
 *   "priority":         "HIGH",
 *   "dueInSeconds":     86400,
 *   "expiresInSeconds": 604800
 * }
 * }</pre>
 *
 * <p>Placeholders are already substituted before this class sees the map: the engine resolves a node's
 * configuration against the execution's variables, so {@code ${manager.username}} arrives as a name. This class
 * only has to decide what that name refers to.
 *
 * <h2>Names or ids, and why both</h2>
 *
 * <p>An author writing a workflow by hand knows the group as "Finance approvers"; the designer, which has the
 * group list, sends an id. Accepting either costs one extra lookup and removes a class of workflow that is
 * correct on screen and broken in a JSON export.
 *
 * <h2>What happens when resolution fails</h2>
 *
 * <p>The task is still created, unassigned, and the failure is reported. The tempting alternative — falling back
 * to whoever started the execution — is worse than it looks: on an approval workflow it hands the requester
 * their own approval. Leaving it unassigned makes an administrator deal with it, which is the correct outcome
 * for a misconfigured workflow, and the problems list is what tells them why.
 */
@Component
public class TaskAssignmentResolver {

    private static final Logger log = LoggerFactory.getLogger(TaskAssignmentResolver.class);

    /** Configuration keys, accepted in both the terse and explicit spellings. */
    private static final List<String> ASSIGNEE_KEYS = List.of("assignee", "assigneeUserId", "assigneeUsername");
    private static final List<String> CANDIDATE_USER_KEYS = List.of("candidateUsers", "candidateUserIds");
    private static final List<String> CANDIDATE_GROUP_KEYS = List.of("candidateGroups", "candidateGroupIds");

    private final UserRepository users;
    private final GroupRepository groups;

    public TaskAssignmentResolver(UserRepository users, GroupRepository groups) {
        this.users = users;
        this.groups = groups;
    }

    /**
     * @param configuration the node's configuration, already resolved against the execution's variables
     * @return the assignment, never null; check {@link TaskAssignment#problems()} for what did not resolve
     */
    public TaskAssignment resolve(Map<String, Object> configuration) {
        Map<String, Object> config = configuration == null ? Map.of() : configuration;
        List<String> problems = new ArrayList<>();

        Optional<User> assignee = resolveAssignee(config, problems);
        List<String> candidateUserIds = resolveCandidateUsers(config, problems);
        List<String> candidateGroupIds = resolveCandidateGroups(config, problems);

        TaskPriority priority = TaskPriority.parse(text(config.get("priority"))).orElse(TaskPriority.NORMAL);
        Duration dueIn = seconds(config.get("dueInSeconds"), problems, "dueInSeconds");
        Duration expiresIn = seconds(config.get("expiresInSeconds"), problems, "expiresInSeconds");
        boolean external = isExternal(config);

        TaskAssignment assignment = new TaskAssignment(
                assignee.map(User::getId).orElse(null),
                assignee.map(User::getUsername).orElse(null),
                candidateUserIds, candidateGroupIds, priority, dueIn, expiresIn, problems, external);

        if (!assignment.isAddressed()) {
            // Not added to problems by the individual resolvers, because "nothing was configured" is a
            // different mistake from "what was configured does not exist" and deserves its own wording. An
            // external task is addressed (to the link holder), so it never reaches here.
            List<String> combined = new ArrayList<>(problems);
            combined.add("This task names no assignee and no candidate group, so nobody can see it in their "
                    + "inbox. Only an administrator will find it.");
            assignment = new TaskAssignment(null, null, candidateUserIds, candidateGroupIds, priority,
                    dueIn, expiresIn, combined, false);
        }
        return assignment;
    }

    /**
     * @return whether this form node is configured for an external customer
     *
     * <p>Recognised as {@code assignmentType: EXTERNAL} / {@code EXTERNAL_USER}, or an explicit
     * {@code externalAccess: true} — the shapes the designer may write. An external task carries no internal
     * assignee; it is completed through a secure form link instead.
     */
    private boolean isExternal(Map<String, Object> config) {
        String type = text(config.get("assignmentType"));
        if (type != null && (type.equalsIgnoreCase("EXTERNAL") || type.equalsIgnoreCase("EXTERNAL_USER"))) {
            return true;
        }
        Object flag = config.get("externalAccess");
        return flag instanceof Boolean bool ? bool : "true".equalsIgnoreCase(String.valueOf(flag));
    }

    // ---------------------------------------------------------------------- internals

    private Optional<User> resolveAssignee(Map<String, Object> config, List<String> problems) {
        for (String key : ASSIGNEE_KEYS) {
            String raw = text(config.get(key));
            if (raw == null) {
                continue;
            }
            if (isUnresolvedPlaceholder(raw)) {
                problems.add("The assignee is configured as '" + raw + "', but no such workflow variable "
                        + "exists at this point, so this task was left unassigned.");
                log.warn("Form node assignee placeholder '{}' resolved to nothing", raw);
                return Optional.empty();
            }
            Optional<User> found = findUser(raw);
            if (found.isPresent()) {
                return found;
            }
            problems.add("The assignee '" + raw + "' does not match any enabled account, so this task was "
                    + "left unassigned.");
            log.warn("Form node names assignee '{}', which resolves to no usable account", raw);
            return Optional.empty();
        }
        return Optional.empty();
    }

    private List<String> resolveCandidateUsers(Map<String, Object> config, List<String> problems) {
        Set<String> ids = new LinkedHashSet<>();
        for (String raw : stringList(config, CANDIDATE_USER_KEYS)) {
            if (isUnresolvedPlaceholder(raw)) {
                problems.add("Candidate user '" + raw + "' names a workflow variable that does not exist here "
                        + "and was ignored.");
                continue;
            }
            Optional<User> found = findUser(raw);
            if (found.isPresent()) {
                ids.add(found.get().getId());
            } else {
                problems.add("Candidate user '" + raw + "' does not match any enabled account and was ignored.");
            }
        }
        return List.copyOf(ids);
    }

    private List<String> resolveCandidateGroups(Map<String, Object> config, List<String> problems) {
        Set<String> ids = new LinkedHashSet<>();
        for (String raw : stringList(config, CANDIDATE_GROUP_KEYS)) {
            if (isUnresolvedPlaceholder(raw)) {
                problems.add("Candidate group '" + raw + "' names a workflow variable that does not exist here "
                        + "and was ignored.");
                continue;
            }
            Optional<Group> found = findGroup(raw);
            if (found.isEmpty()) {
                problems.add("Candidate group '" + raw + "' does not exist and was ignored.");
                continue;
            }
            Group group = found.get();
            if (!group.isEnabled()) {
                // A disabled group grants nothing anywhere else either, so offering a task to it would create
                // work nobody is able to claim.
                problems.add("Candidate group '" + group.getName() + "' is disabled, so it was ignored.");
                continue;
            }
            ids.add(group.getId());
        }
        return List.copyOf(ids);
    }

    /** By id first, then by username. An id is unambiguous; a username needs normalising. */
    private Optional<User> findUser(String raw) {
        Optional<User> byId = users.findById(raw).filter(User::isUsable);
        if (byId.isPresent()) {
            return byId;
        }
        return users.findByUsername(raw.trim().toLowerCase(Locale.ROOT)).filter(User::isUsable);
    }

    private Optional<Group> findGroup(String raw) {
        Optional<Group> byId = groups.findById(raw);
        return byId.isPresent() ? byId : groups.findByName(raw.trim());
    }

    /**
     * Reads a list-valued key, accepting either a JSON array or comma-separated text.
     *
     * <p>Both, because both are the natural thing to send. An API caller writes
     * {@code ["Finance approvers", "Ops"]}; the designer's property panel has no list control and writes
     * {@code "Finance approvers, Ops"}. Supporting one would make the other silently resolve to a single group
     * named "Finance approvers, Ops", which exists nowhere and produces a task addressed to no one.
     *
     * <p>A name containing a comma cannot be expressed in the text form. That is a real limitation and the
     * right one to accept: group names with commas are rare, and the array form still handles them.
     */
    private List<String> stringList(Map<String, Object> config, List<String> keys) {
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            Object value = config.get(key);
            if (value instanceof Collection<?> collection) {
                for (Object item : collection) {
                    addSplit(values, text(item));
                }
            } else {
                addSplit(values, text(value));
            }
        }
        return values;
    }

    /** Splits comma-separated text, leaving an unresolved placeholder whole so it can be reported as one. */
    private static void addSplit(List<String> target, String value) {
        if (value == null) {
            return;
        }
        if (isUnresolvedPlaceholder(value) || value.indexOf(',') < 0) {
            target.add(value);
            return;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    private Duration seconds(Object value, List<String> problems, String key) {
        if (value == null) {
            return null;
        }
        long parsed;
        if (value instanceof Number number) {
            parsed = number.longValue();
        } else {
            try {
                parsed = Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                problems.add("'" + key + "' is not a number of seconds and was ignored.");
                return null;
            }
        }
        if (parsed <= 0) {
            problems.add("'" + key + "' must be a positive number of seconds and was ignored.");
            return null;
        }
        return Duration.ofSeconds(parsed);
    }

    /** @return the trimmed text, or null when absent or blank */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() || "null".equals(string) ? null : string;
    }

    /**
     * Whether a value still looks like a placeholder after resolution.
     *
     * <p>Which means the variable it named does not exist at this point in the execution. Worth distinguishing,
     * because treating it as a literal username produces "the assignee '${manager.username}' does not match any
     * account" and sends the reader looking for an account instead of at the variable they misspelled.
     */
    private static boolean isUnresolvedPlaceholder(String value) {
        return value.startsWith("${") && value.endsWith("}");
    }
}
