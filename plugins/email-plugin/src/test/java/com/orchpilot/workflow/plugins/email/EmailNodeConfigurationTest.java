package com.orchpilot.workflow.plugins.email;

import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a node's settings.
 *
 * <p>The tests that matter most are the refusals. A literal password in a workflow would work — which is
 * precisely why it has to be refused, because it would then live in the definition, every export of it, and
 * its version history. The rest cover the mistakes that are easy to make in a form with eight required parts
 * and would otherwise be discovered one workflow run at a time.
 */
class EmailNodeConfigurationTest {

    /** Substitutes ${name} from a fixed map, standing in for the engine's resolver. */
    private static UnaryOperator<String> resolverOf(Map<String, String> variables) {
        return template -> {
            String result = template == null ? "" : template;
            for (Map.Entry<String, String> variable : variables.entrySet()) {
                result = result.replace("${" + variable.getKey() + "}", variable.getValue());
            }
            return result;
        };
    }

    private static final UnaryOperator<String> IDENTITY = value -> value == null ? "" : value;

    /** A secret store holding whatever a test puts in it. */
    private static SecretProvider secretsOf(Map<String, String> values) {
        return name -> Optional.ofNullable(values.get(name));
    }

    private static NodeConfiguration configOf(Map<String, Object> values) {
        return new NodeConfiguration() {
            @Override
            public Optional<Object> find(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public Map<String, Object> asMap() {
                return values;
            }
        };
    }

    /** A configuration that is valid, so a test can change one thing and see only that failure. */
    private static Map<String, Object> valid() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("smtpHost", "smtp.company.com");
        values.put("smtpPort", 587);
        values.put("security", "STARTTLS");
        values.put("authenticationRequired", true);
        values.put("username", "workflow@company.com");
        values.put("passwordSecret", "smtp.password");
        values.put("fromEmail", "notifications@company.com");
        values.put("to", List.of("customer@example.com"));
        values.put("subject", "Order approved");
        values.put("body", "Your order has been approved.");
        return values;
    }

    private static EmailNodeConfiguration read(Map<String, Object> values) {
        return EmailNodeConfiguration.from(configOf(values), IDENTITY,
                secretsOf(Map.of("smtp.password", "a-password")));
    }

    @Nested
    @DisplayName("Credentials")
    class Credentials {

        @Test
        @DisplayName("a password named as a secret is fetched from the secret store")
        void passwordFromSecret() {
            assertEquals("a-password", read(valid()).password());
        }

        @Test
        @DisplayName("a literal password in the workflow is refused")
        void refusesLiteralPassword() {
            Map<String, Object> values = valid();
            values.remove("passwordSecret");
            values.put("password", "hunter2-in-the-workflow");

            PluginConfigurationException refusal = assertThrows(PluginConfigurationException.class,
                    () -> read(values));

            // The message has to explain why, because the operator's next move is to store it as a secret.
            assertTrue(refusal.getMessage().contains("must not be written into the workflow"));
        }

        @Test
        @DisplayName("a ${secret.X} reference is accepted, because the workflow holds the reference not the value")
        void acceptsSecretReference() {
            Map<String, Object> values = valid();
            values.remove("passwordSecret");
            values.put("password", "${secret.SMTP_PASSWORD}");

            EmailNodeConfiguration settings = EmailNodeConfiguration.from(configOf(values),
                    resolverOf(Map.of("secret.SMTP_PASSWORD", "resolved-password")), secretsOf(Map.of()));

            assertEquals("resolved-password", settings.password());
        }

        @Test
        @DisplayName("a credential id supplies both the username and the password")
        void credentialIdSuppliesBoth() {
            Map<String, Object> values = valid();
            values.remove("passwordSecret");
            values.remove("username");
            values.put("credentialId", "company-smtp-prod");

            EmailNodeConfiguration settings = EmailNodeConfiguration.from(configOf(values), IDENTITY,
                    secretsOf(Map.of(
                            "company-smtp-prod.username", "workflow@company.com",
                            "company-smtp-prod.password", "from-the-store")));

            assertEquals("workflow@company.com", settings.username());
            assertEquals("from-the-store", settings.password());
        }

        @Test
        @DisplayName("a named secret that does not exist fails with the name, not silently")
        void missingSecret() {
            Map<String, Object> values = valid();
            values.put("passwordSecret", "smtp.absent");

            PluginConfigurationException failure = assertThrows(PluginConfigurationException.class,
                    () -> read(values));

            assertTrue(failure.getMessage().contains("smtp.absent"));
        }

        @Test
        @DisplayName("no password is needed when authentication is off")
        void unauthenticatedRelay() {
            Map<String, Object> values = valid();
            values.put("authenticationRequired", false);
            values.remove("passwordSecret");
            values.remove("username");

            assertTrue(read(values).validate().isEmpty());
        }
    }

    @Nested
    @DisplayName("Variables")
    class Variables {

        @Test
        @DisplayName("every text field goes through the engine's resolver")
        void resolvesEverywhere() {
            Map<String, Object> values = valid();
            values.put("smtpHost", "${smtp.host}");
            values.put("username", "${smtp.username}");
            values.put("fromEmail", "${workflow.sender}");
            values.put("to", List.of("${customer.email}"));
            values.put("subject", "Order ${order.id} approved");
            values.put("body", "Hello ${customer.name}");

            EmailNodeConfiguration settings = EmailNodeConfiguration.from(configOf(values),
                    resolverOf(Map.of(
                            "smtp.host", "smtp.resolved.com",
                            "smtp.username", "resolved@company.com",
                            "workflow.sender", "sender@company.com",
                            "customer.email", "customer@example.com",
                            "order.id", "A-1001",
                            "customer.name", "Sam")),
                    secretsOf(Map.of("smtp.password", "a-password")));

            assertEquals("smtp.resolved.com", settings.host());
            assertEquals("resolved@company.com", settings.username());
            assertEquals("sender@company.com", settings.fromEmail());
            assertEquals(List.of("customer@example.com"), settings.to());
            assertEquals("Order A-1001 approved", settings.subject());
            assertEquals("Hello Sam", settings.body());
        }

        @Test
        @DisplayName("one variable expanding to several addresses becomes several recipients")
        void variableExpandsToList() {
            // Splitting happens after resolution, because ${approvers.emails} becoming three addresses is the
            // ordinary case. Splitting first would send one message to a string containing commas.
            Map<String, Object> values = valid();
            values.put("to", List.of("${approvers.emails}"));

            EmailNodeConfiguration settings = EmailNodeConfiguration.from(configOf(values),
                    resolverOf(Map.of("approvers.emails", "one@example.com, two@example.com;three@example.com")),
                    secretsOf(Map.of("smtp.password", "a-password")));

            assertEquals(3, settings.to().size());
            assertTrue(settings.to().contains("three@example.com"));
        }

        @Test
        @DisplayName("a single string of recipients is accepted as well as a list")
        void acceptsStringRecipients() {
            Map<String, Object> values = valid();
            values.put("to", "one@example.com, two@example.com");

            assertEquals(2, read(values).to().size());
        }
    }

    @Nested
    @DisplayName("Presets")
    class Presets {

        @Test
        @DisplayName("a provider fills in what the node leaves blank")
        void fillsBlanks() {
            Map<String, Object> values = valid();
            values.put("provider", "GMAIL");
            values.remove("smtpHost");
            values.remove("smtpPort");
            values.remove("security");

            EmailNodeConfiguration settings = read(values);

            assertEquals("smtp.gmail.com", settings.host());
            assertEquals(587, settings.port());
            assertEquals(SmtpSecurity.STARTTLS, settings.security());
        }

        @Test
        @DisplayName("a preset never overwrites something the operator typed")
        void neverOverwrites() {
            Map<String, Object> values = valid();
            values.put("provider", "GMAIL");
            values.put("smtpHost", "smtp-relay.gmail.com");
            values.put("smtpPort", 465);
            values.put("security", "SSL_TLS");

            EmailNodeConfiguration settings = read(values);

            assertEquals("smtp-relay.gmail.com", settings.host());
            assertEquals(465, settings.port());
            assertEquals(SmtpSecurity.SSL_TLS, settings.security());
        }

        @Test
        @DisplayName("Amazon SES supplies no host, because its endpoints are regional")
        void sesNeedsAHost() {
            Map<String, Object> values = valid();
            values.put("provider", "AMAZON_SES");
            values.remove("smtpHost");

            List<String> problems = read(values).validate();

            assertTrue(problems.stream().anyMatch(problem -> problem.contains("regional endpoint")));
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("a complete configuration has nothing to report")
        void validPasses() {
            assertTrue(read(valid()).validate().isEmpty());
        }

        @Test
        @DisplayName("every problem is reported at once")
        void reportsEverythingTogether() {
            Map<String, Object> values = valid();
            values.remove("smtpHost");
            values.put("smtpPort", 0);
            values.remove("fromEmail");
            values.put("to", List.of());
            values.remove("subject");
            values.remove("body");

            // Six problems, one run. Failing on the first would mean six workflow executions to find them.
            assertTrue(read(values).validate().size() >= 6);
        }

        @Test
        @DisplayName("a port outside 1-65535 is refused, and everything inside it is allowed")
        void portRange() {
            Map<String, Object> tooHigh = valid();
            tooHigh.put("smtpPort", 70_000);
            assertFalse(read(tooHigh).validate().isEmpty());

            // Not restricted to the four common ports: a relay on 2525, or anything else, is legitimate.
            for (int port : new int[] {1, 25, 465, 587, 2525, 65_535}) {
                Map<String, Object> values = valid();
                values.put("smtpPort", port);
                assertTrue(read(values).validate().isEmpty(), "port " + port + " should be allowed");
            }
        }

        @Test
        @DisplayName("a malformed address is caught before a connection is opened")
        void addressShape() {
            Map<String, Object> values = valid();
            values.put("to", List.of("not-an-address"));
            assertFalse(read(values).validate().isEmpty());

            Map<String, Object> sender = valid();
            sender.put("fromEmail", "also not one");
            assertFalse(read(sender).validate().isEmpty());
        }

        @Test
        @DisplayName("an attachment source this plugin cannot fetch is refused rather than sent empty")
        void unsupportedAttachmentSource() {
            Map<String, Object> values = valid();
            values.put("attachments", List.of(Map.of(
                    "fileName", "invoice.pdf",
                    "source", "OBJECT_STORAGE",
                    "value", "s3://bucket/invoice.pdf")));

            assertTrue(read(values).validate().stream()
                    .anyMatch(problem -> problem.contains("cannot fetch")));
        }

        @Test
        @DisplayName("the recipient count covers to, cc and bcc")
        void recipientCount() {
            Map<String, Object> values = valid();
            values.put("cc", List.of("manager@example.com"));
            values.put("bcc", List.of("archive@example.com"));

            assertEquals(3, read(values).recipientCount());
        }
    }

    @Test
    @DisplayName("the description written to a log names the connection and never the credentials")
    void safeToString() {
        String described = read(valid()).toString();

        assertTrue(described.contains("smtp.company.com:587"));
        assertFalse(described.contains("a-password"));
        assertFalse(described.contains("workflow@company.com"));
    }
}
