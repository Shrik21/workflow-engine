package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.plugins.mongodb.support.TestExecution;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Describing a deployment without writing its credentials down.
 *
 * <p>The refusals are the important part. Pasting a working connection string is the most natural thing an
 * operator can do, and a working connection string usually contains a password.
 */
class MongoConnectionSettingsTest {

    private static Map<String, Object> valid() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("host", "mongo.internal");
        values.put("port", 27017);
        values.put("database", "customers");
        values.put("username", "workflow");
        values.put("passwordSecret", "mongodb.password");
        return values;
    }

    private static MongoConnectionSettings read(Map<String, Object> values) {
        return read(values, Map.of("mongodb.password", "a-password"), Map.of());
    }

    private static MongoConnectionSettings read(Map<String, Object> values, Map<String, String> secrets,
                                                Map<String, String> variables) {
        TestExecution execution = TestExecution.with(values)
                .secrets(secrets)
                .variables(variables)
                .build();
        return MongoConnectionSettings.from(execution.configuration(), execution::resolve,
                execution.secrets());
    }

    @Nested
    @DisplayName("Credentials")
    class Credentials {

        @Test
        @DisplayName("a password named as a secret is fetched from the secret store")
        void fromSecret() {
            assertTrue(read(valid()).authenticated());
            assertTrue(read(valid()).validate().isEmpty());
        }

        @Test
        @DisplayName("a literal password in the workflow is refused")
        void refusesLiteralPassword() {
            Map<String, Object> values = valid();
            values.remove("passwordSecret");
            values.put("password", "hunter2");

            PluginConfigurationException refusal = assertThrows(PluginConfigurationException.class,
                    () -> read(values));

            assertTrue(refusal.getMessage().contains("must not be written into the workflow"));
        }

        @Test
        @DisplayName("a connection string containing credentials is refused, with the fix in the message")
        void refusesCredentialsInUri() {
            Map<String, Object> values = valid();
            values.put("connectionUri", "mongodb://admin:s3cret@mongo.internal:27017/customers");

            PluginConfigurationException refusal = assertThrows(PluginConfigurationException.class,
                    () -> read(values));

            assertTrue(refusal.getMessage().contains("contains credentials"));
            // The operator's next move is to split it, so the message has to say how.
            assertTrue(refusal.getMessage().contains("passwordSecret"));
        }

        @Test
        @DisplayName("the same connection string without credentials is accepted")
        void acceptsUriWithoutCredentials() {
            Map<String, Object> values = valid();
            values.remove("host");
            values.put("connectionUri", "mongodb+srv://cluster0.mongodb.net/customers?retryWrites=true");

            assertTrue(read(values).validate().isEmpty());
            assertEquals("customers", read(values).database());
        }

        @Test
        @DisplayName("a credential id supplies both halves")
        void credentialId() {
            Map<String, Object> values = valid();
            values.remove("passwordSecret");
            values.remove("username");
            values.put("credentialId", "prod-mongo");

            MongoConnectionSettings settings = read(values,
                    Map.of("prod-mongo.username", "workflow", "prod-mongo.password", "from-the-store"),
                    Map.of());

            assertTrue(settings.authenticated());
            assertTrue(settings.validate().isEmpty());
        }

        @Test
        @DisplayName("a named secret that does not exist fails with the name")
        void missingSecret() {
            Map<String, Object> values = valid();
            values.put("passwordSecret", "mongodb.absent");

            PluginConfigurationException failure = assertThrows(PluginConfigurationException.class,
                    () -> read(values, Map.of(), Map.of()));

            assertTrue(failure.getMessage().contains("mongodb.absent"));
        }

        @Test
        @DisplayName("an unauthenticated deployment needs no credentials")
        void unauthenticated() {
            Map<String, Object> values = valid();
            values.remove("username");
            values.remove("passwordSecret");

            MongoConnectionSettings settings = read(values, Map.of(), Map.of());

            assertFalse(settings.authenticated());
            assertTrue(settings.validate().isEmpty());
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("a deployment described by neither a URI nor a host is refused")
        void needsAnAddress() {
            Map<String, Object> values = valid();
            values.remove("host");
            values.remove("database");

            List<String> problems = read(values).validate();

            assertEquals(2, problems.size());
        }

        @Test
        @DisplayName("an unparseable connection string is reported as such")
        void malformedUri() {
            Map<String, Object> values = valid();
            values.put("connectionUri", "not-a-mongodb-uri");

            assertTrue(read(values).validate().stream()
                    .anyMatch(problem -> problem.contains("could not be parsed")));
        }

        @Test
        @DisplayName("the database may come from the connection string")
        void databaseFromUri() {
            Map<String, Object> values = valid();
            values.remove("database");
            values.put("connectionUri", "mongodb://mongo.internal:27017/orders");

            assertEquals("orders", read(values).database());
            assertTrue(read(values).validate().isEmpty());
        }

        @Test
        @DisplayName("a pool minimum above its maximum is refused")
        void poolSizes() {
            Map<String, Object> values = valid();
            values.put("maxPoolSize", 5);
            values.put("minPoolSize", 10);

            assertFalse(read(values).validate().isEmpty());
        }
    }

    @Nested
    @DisplayName("The cache key")
    class CacheKey {

        @Test
        @DisplayName("the same connection produces the same key")
        void stable() {
            assertEquals(read(valid()).cacheKey(), read(valid()).cacheKey());
        }

        @Test
        @DisplayName("a rotated password produces a different key")
        void passwordChangesIt() {
            // Otherwise a revoked credential keeps working from a pooled client until the plugin reloads.
            String before = read(valid(), Map.of("mongodb.password", "old"), Map.of()).cacheKey();
            String after = read(valid(), Map.of("mongodb.password", "new"), Map.of()).cacheKey();

            assertNotEquals(before, after);
        }

        @Test
        @DisplayName("a different pool size produces a different key")
        void poolSizeChangesIt() {
            Map<String, Object> larger = valid();
            larger.put("maxPoolSize", 50);

            assertNotEquals(read(valid()).cacheKey(), read(larger).cacheKey());
        }

        @Test
        @DisplayName("the key never contains the password")
        void neverCarriesTheSecret() {
            // It is held in a map for the life of the plugin and appears in debug logging.
            assertFalse(read(valid()).cacheKey().contains("a-password"));
        }
    }

    @Test
    @DisplayName("variables are resolved in every connection field")
    void resolvesVariables() {
        Map<String, Object> values = valid();
        values.put("host", "${mongo.host}");
        values.put("database", "${mongo.database}");
        values.put("username", "${mongo.username}");

        MongoConnectionSettings settings = read(values, Map.of("mongodb.password", "a-password"),
                Map.of("mongo.host", "mongo-eu.internal", "mongo.database", "customers",
                        "mongo.username", "workflow"));

        assertEquals("customers", settings.database());
        assertTrue(settings.toString().contains("mongo-eu.internal"));
    }

    @Test
    @DisplayName("the description names the deployment and never the credentials")
    void safeToString() {
        String described = read(valid()).toString();

        assertTrue(described.contains("mongo.internal:27017/customers"));
        assertTrue(described.contains("authenticated"));
        assertFalse(described.contains("a-password"));
        assertFalse(described.contains("workflow"));
    }

    @Test
    @DisplayName("a connection string is described without its query options")
    void redactsUri() {
        Map<String, Object> values = valid();
        values.remove("host");
        values.put("connectionUri", "mongodb+srv://cluster0.mongodb.net/customers?authSource=admin");

        String described = read(values).toString();

        assertTrue(described.contains("cluster0.mongodb.net"));
        assertFalse(described.contains("authSource"));
    }
}
