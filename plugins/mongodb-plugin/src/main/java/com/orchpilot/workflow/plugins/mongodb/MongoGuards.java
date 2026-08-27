package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.sdk.context.PluginSettings;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.WorkflowUser;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What has to be true before an operation runs.
 *
 * <p>Three checks, in the order in which they can fail cheaply: is the operation switched on at all, is the
 * acting user in a role that carries its permission, and — for anything that touches more than one document —
 * did the operator say so deliberately.
 */
final class MongoGuards {

    /** The result of checking. A refusal carries the sentence the execution record will show. */
    record Decision(boolean allowed, String code, String message) {

        static Decision allow() {
            return new Decision(true, null, null);
        }

        static Decision refuse(String code, String message) {
            return new Decision(false, code, message);
        }
    }

    private MongoGuards() {
    }

    /**
     * Whether this operation may run.
     *
     * @param operation     what is about to happen
     * @param configuration the node, consulted for the confirmation flag
     * @param settings      the plugin's installation settings, holding the role mapping and the kill switches
     * @param user          the acting user, absent for a scheduled or event-triggered execution
     * @return the decision
     */
    static Decision check(MongoOperation operation, NodeConfiguration configuration, PluginSettings settings,
                          Optional<WorkflowUser> user) {

        if (!enabled(operation, settings)) {
            return Decision.refuse(MongoErrors.PERMISSION_DENIED,
                    "The operation " + operation.label() + " is switched off for this installation of the "
                            + "MongoDB plugin. An administrator enables it with the setting '"
                            + enabledKey(operation) + "'.");
        }

        Decision role = permitted(operation, settings, user);
        if (!role.allowed()) {
            return role;
        }

        if (operation.requiresConfirmation() && !configuration.getBoolean("confirmed", false)) {
            return Decision.refuse(MongoErrors.CONFIRMATION_REQUIRED, confirmationMessage(operation));
        }

        return Decision.allow();
    }

    /**
     * A refusal an operator can act on.
     *
     * <p>Naming what the operation will do, rather than saying "confirmation required": somebody who ticks a
     * box without knowing that Update Many rewrites every matching document has not really confirmed anything.
     */
    private static String confirmationMessage(MongoOperation operation) {
        String what = switch (operation) {
            case DELETE_MANY -> "delete every document matching the filter, with no way to undo it";
            case UPDATE_MANY -> "modify every document matching the filter";
            case REPLACE_ONE -> "overwrite the matched document entirely, dropping every field the "
                    + "replacement does not contain";
            case BULK_WRITE -> "run a batch of writes that may include deletes";
            case DROP_COLLECTION -> "delete the collection and everything in it, indexes included";
            case RENAME_COLLECTION -> "rename the collection, which breaks anything reading it by its old name";
            case DROP_INDEX -> "drop the index, after which queries relying on it fall back to a collection scan";
            case EXECUTE_COMMAND -> "run an arbitrary database command, which reaches beyond what the other "
                    + "nodes here can do";
            default -> "affect more than the single document a filter identifies";
        };
        return operation.label() + " will " + what + ". Set 'confirmed' to true on this node to allow it.";
    }

    /** An operation is on unless an administrator turned it off; Execute Command is off unless turned on. */
    private static boolean enabled(MongoOperation operation, PluginSettings settings) {
        boolean defaultValue = operation != MongoOperation.EXECUTE_COMMAND;
        return settings.getBoolean(enabledKey(operation), defaultValue);
    }

    private static String enabledKey(MongoOperation operation) {
        return "operation." + operation.name().toLowerCase(Locale.ROOT) + ".enabled";
    }

    /**
     * Whether the acting user's roles carry the operation's permission.
     *
     * <p>An unmapped permission is open: see {@link MongoPermission} for why, and for what is enforcing the
     * request in front of this.
     *
     * <p>An execution with no user is a scheduled or event-triggered one. Those are refused for anything
     * mapped, because a mapping exists precisely to say "a person in this role, and nobody else" — and
     * "nobody" includes a timer. An installation that wants unattended writes leaves the permission unmapped
     * or names a role its service account holds.
     */
    private static Decision permitted(MongoOperation operation, PluginSettings settings,
                                      Optional<WorkflowUser> user) {
        Set<String> roles = mappedRoles(operation.permission(), settings);
        if (roles.isEmpty()) {
            return Decision.allow();
        }

        if (user.isEmpty()) {
            return Decision.refuse(MongoErrors.PERMISSION_DENIED,
                    operation.label() + " requires " + operation.permission().authority()
                            + ", which is mapped to " + String.join(", ", roles) + ". This execution has no "
                            + "user — it was started by a schedule or an event — so no role can be checked.");
        }

        WorkflowUser acting = user.get();
        boolean held = roles.stream().anyMatch(acting::hasRole);
        if (held) {
            return Decision.allow();
        }
        return Decision.refuse(MongoErrors.PERMISSION_DENIED,
                operation.label() + " requires " + operation.permission().authority()
                        + ", which is mapped to " + String.join(", ", roles) + ".");
    }

    /** @return the roles an administrator mapped to this permission, in the order they wrote them */
    private static Set<String> mappedRoles(MongoPermission permission, PluginSettings settings) {
        String configured = settings.getString(permission.settingKey(), "").trim();
        if (configured.isEmpty()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        Arrays.stream(configured.split("[,;]"))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .forEach(roles::add);
        return roles;
    }

    /**
     * Refuses a filter that matches everything, where the operation would act on all of it.
     *
     * <p>An empty filter is legitimate — {@code countDocuments({})} is the ordinary way to count a
     * collection — so this applies only where emptiness is destructive. The usual cause is a filter built
     * from a variable that resolved to nothing, which produces {@code {}} rather than an error, and then
     * deletes the collection's contents. Requiring the flag makes the difference between "delete these" and
     * "delete everything" something the operator states rather than something they discover.
     *
     * @param operation what is about to run
     * @param filter    the resolved filter
     * @param configuration the node
     * @return the decision
     */
    static Decision checkFilter(MongoOperation operation, Document filter, NodeConfiguration configuration) {
        boolean unbounded = filter == null || filter.isEmpty();
        boolean actsOnEverything = operation == MongoOperation.DELETE_MANY
                || operation == MongoOperation.UPDATE_MANY;

        if (!unbounded || !actsOnEverything) {
            return Decision.allow();
        }
        if (configuration.getBoolean("allowEmptyFilter", false)) {
            return Decision.allow();
        }
        return Decision.refuse(MongoErrors.CONFIRMATION_REQUIRED,
                operation.label() + " was given an empty filter, which matches every document in the "
                        + "collection. If a variable in the filter resolved to nothing, that is the bug. If "
                        + "the whole collection really is the target, set 'allowEmptyFilter' to true.");
    }

    /** @return every permission name, for the plugin manifest and the documentation */
    static List<String> declaredPermissions() {
        List<String> names = new ArrayList<>();
        for (MongoPermission permission : MongoPermission.values()) {
            names.add(permission.authority());
        }
        return names;
    }
}
