package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.plugins.mongodb.support.TestExecution;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What has to be true before a database operation runs.
 *
 * <p>These are the checks that stand between a workflow with a mistake in it and a collection with nothing
 * left in it.
 */
class MongoGuardsTest {

    private static MongoGuards.Decision check(MongoOperation operation, Map<String, Object> configuration,
                                              Map<String, Object> settings, String... roles) {
        TestExecution.Builder builder = TestExecution.with(configuration).settings(settings);
        if (roles.length > 0) {
            builder.user("operator", roles);
        }
        TestExecution execution = builder.build();
        return MongoGuards.check(operation, execution.configuration(), execution.settings(),
                execution.currentUser());
    }

    @Nested
    @DisplayName("Confirmation")
    class Confirmation {

        @Test
        @DisplayName("an ordinary operation needs none")
        void safeOperations() {
            assertTrue(check(MongoOperation.FIND_MANY, Map.of(), Map.of()).allowed());
            assertTrue(check(MongoOperation.INSERT_ONE, Map.of(), Map.of()).allowed());
            assertTrue(check(MongoOperation.DELETE_ONE, Map.of(), Map.of()).allowed());
        }

        @Test
        @DisplayName("delete many is refused without it, and the message says what it would do")
        void deleteMany() {
            MongoGuards.Decision decision = check(MongoOperation.DELETE_MANY, Map.of(), Map.of());

            assertFalse(decision.allowed());
            assertEquals(MongoErrors.CONFIRMATION_REQUIRED, decision.code());
            // Ticking a box without knowing what it permits is not confirmation.
            assertTrue(decision.message().contains("no way to undo"));
        }

        @Test
        @DisplayName("confirming it allows it")
        void confirmed() {
            assertTrue(check(MongoOperation.DELETE_MANY, Map.of("confirmed", true), Map.of()).allowed());
        }

        @Test
        @DisplayName("replace one is confirmed too, because it drops every field it does not mention")
        void replaceOne() {
            MongoGuards.Decision decision = check(MongoOperation.REPLACE_ONE, Map.of(), Map.of());

            assertFalse(decision.allowed());
            assertTrue(decision.message().contains("dropping every field"));
        }

        @Test
        @DisplayName("dropping a collection is confirmed")
        void dropCollection() {
            assertFalse(check(MongoOperation.DROP_COLLECTION, Map.of(), Map.of()).allowed());
            assertTrue(check(MongoOperation.DROP_COLLECTION, Map.of("confirmed", true), Map.of()).allowed());
        }
    }

    @Nested
    @DisplayName("Switches")
    class Switches {

        @Test
        @DisplayName("running arbitrary commands is off unless switched on")
        void commandsOffByDefault() {
            MongoGuards.Decision decision = check(MongoOperation.EXECUTE_COMMAND,
                    Map.of("confirmed", true), Map.of());

            assertFalse(decision.allowed());
            assertEquals(MongoErrors.PERMISSION_DENIED, decision.code());
            assertTrue(decision.message().contains("operation.execute_command.enabled"));
        }

        @Test
        @DisplayName("an administrator can switch it on")
        void commandsCanBeEnabled() {
            assertTrue(check(MongoOperation.EXECUTE_COMMAND, Map.of("confirmed", true),
                    Map.of("operation.execute_command.enabled", true)).allowed());
        }

        @Test
        @DisplayName("an administrator can switch an ordinary operation off")
        void operationsCanBeDisabled() {
            assertFalse(check(MongoOperation.DELETE_ONE, Map.of(),
                    Map.of("operation.delete_one.enabled", false)).allowed());
        }
    }

    @Nested
    @DisplayName("Permissions")
    class Permissions {

        @Test
        @DisplayName("an unmapped permission is open, because the engine already gated the execution")
        void unmappedIsOpen() {
            assertTrue(check(MongoOperation.DELETE_ONE, Map.of(), Map.of(), "USER").allowed());
        }

        @Test
        @DisplayName("a mapped permission admits the roles named and refuses the rest")
        void mappedRoles() {
            Map<String, Object> settings = Map.of("permission.mongodb_delete", "ADMIN, DATA_STEWARD");

            assertTrue(check(MongoOperation.DELETE_ONE, Map.of(), settings, "DATA_STEWARD").allowed());

            MongoGuards.Decision refused = check(MongoOperation.DELETE_ONE, Map.of(), settings, "USER");
            assertFalse(refused.allowed());
            assertTrue(refused.message().contains("MONGODB_DELETE"));
        }

        @Test
        @DisplayName("a mapped permission refuses an execution with no user at all")
        void scheduledExecution() {
            // A schedule holds no role. "Only this role" has to include the timer, or the mapping means
            // nothing for the executions nobody is watching.
            MongoGuards.Decision decision = check(MongoOperation.DELETE_ONE, Map.of(),
                    Map.of("permission.mongodb_delete", "ADMIN"));

            assertFalse(decision.allowed());
            assertTrue(decision.message().contains("no user"));
        }

        @Test
        @DisplayName("each operation asks for its own permission, not one for the whole node")
        void perOperation() {
            Map<String, Object> settings = Map.of("permission.mongodb_delete", "ADMIN");

            // An operator granted inserts cannot delete through the same write node.
            assertTrue(check(MongoOperation.INSERT_ONE, Map.of(), settings, "USER").allowed());
            assertFalse(check(MongoOperation.DELETE_ONE, Map.of(), settings, "USER").allowed());
        }

        @Test
        @DisplayName("every declared permission is named MONGODB_*")
        void declared() {
            assertTrue(MongoGuards.declaredPermissions().contains("MONGODB_COMMAND_EXECUTE"));
            assertEquals(MongoPermission.values().length, MongoGuards.declaredPermissions().size());
        }
    }

    @Nested
    @DisplayName("Empty filters")
    class EmptyFilters {

        private MongoGuards.Decision checkFilter(MongoOperation operation, Document filter,
                                                 Map<String, Object> configuration) {
            return MongoGuards.checkFilter(operation, filter,
                    TestExecution.of(configuration).configuration());
        }

        @Test
        @DisplayName("a bulk delete with no filter is refused")
        void emptyFilterOnDeleteMany() {
            // The usual cause is a variable that resolved to nothing, which produces {} rather than an error.
            MongoGuards.Decision decision = checkFilter(MongoOperation.DELETE_MANY, new Document(), Map.of());

            assertFalse(decision.allowed());
            assertTrue(decision.message().contains("resolved to nothing"));
        }

        @Test
        @DisplayName("saying so explicitly allows it")
        void allowEmptyFilter() {
            assertTrue(checkFilter(MongoOperation.DELETE_MANY, new Document(),
                    Map.of("allowEmptyFilter", true)).allowed());
        }

        @Test
        @DisplayName("a filter that matches something is fine")
        void filtered() {
            assertTrue(checkFilter(MongoOperation.DELETE_MANY, new Document("status", "DELETED"), Map.of())
                    .allowed());
        }

        @Test
        @DisplayName("counting everything is ordinary and not refused")
        void countIsUnaffected() {
            assertTrue(checkFilter(MongoOperation.COUNT, new Document(), Map.of()).allowed());
            assertTrue(checkFilter(MongoOperation.FIND_MANY, new Document(), Map.of()).allowed());
        }
    }
}
