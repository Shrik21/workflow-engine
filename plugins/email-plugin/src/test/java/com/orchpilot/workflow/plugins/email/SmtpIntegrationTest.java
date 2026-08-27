package com.orchpilot.workflow.plugins.email;

import com.orchpilot.workflow.plugins.email.support.FakeSmtpServer;
import com.orchpilot.workflow.plugins.email.support.TestExecution;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin sending mail to a server that is really there.
 *
 * <p>An SMTP server on a loopback port, spoken to over a socket: greeting, EHLO, AUTH, MAIL FROM, RCPT TO,
 * DATA. It covers the ground a mocked {@code Transport} cannot — that authentication happens, that the message
 * this plugin builds is one a server accepts, and that a refusal comes back as the right code with the right
 * retry decision.
 *
 * <p>The last test in this file talks to a real provider instead, and runs only when one is configured through
 * the environment.
 */
class SmtpIntegrationTest {

    private static final String USERNAME = "workflow@company.com";
    private static final String PASSWORD = "the-smtp-password";

    private FakeSmtpServer server;
    private EmailPlugin plugin;
    private TestExecution execution;

    @BeforeEach
    void startServer() throws IOException {
        server = FakeSmtpServer.requiring(USERNAME, PASSWORD);
        plugin = new EmailPlugin();
    }

    @AfterEach
    void stopServer() throws IOException {
        plugin.destroy();
        server.close();
    }

    /** A node pointed at the fake server. Plaintext, because the server speaks no TLS. */
    private Map<String, Object> node() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("smtpHost", "127.0.0.1");
        configuration.put("smtpPort", server.port());
        configuration.put("security", "NONE");
        configuration.put("authenticationRequired", true);
        configuration.put("username", USERNAME);
        configuration.put("passwordSecret", "smtp.password");
        configuration.put("fromEmail", "notifications@company.com");
        configuration.put("to", List.of("customer@example.com"));
        configuration.put("subject", "Order approved");
        configuration.put("body", "Your order has been approved.");
        return configuration;
    }

    private NodeExecutionResult run(Map<String, Object> configuration) {
        return run(configuration, Map.of());
    }

    private NodeExecutionResult run(Map<String, Object> configuration, Map<String, String> variables) {
        execution = TestExecution.of(configuration, Map.of("smtp.password", PASSWORD), variables);
        plugin.initialize(execution);
        return plugin.execute(execution);
    }

    /**
     * Reads a dotted path out of the outputs, the way the engine's {@code VariableMapper} does.
     *
     * <p>Outputs are nested — {@code {"email": {"messageId": …}}} — because a key containing a dot cannot be
     * persisted: it becomes a field name in the execution document, MongoDB refuses it, and the failure lands
     * after the mail has already been sent.
     */
    @SuppressWarnings("unchecked")
    private static Object output(NodeExecutionResult result, String path) {
        Object current = result.outputs();
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(segment);
        }
        return current;
    }

    @Test
    @DisplayName("a message is authenticated, sent, and reported with its id")
    void sends() {
        NodeExecutionResult result = run(node());

        assertTrue(result.isSuccess(), () -> result.errorCode() + " " + result.errorMessage());
        assertEquals(true, output(result, "email.success"));
        assertNotNull(output(result, "email.messageId"));
        assertEquals(1, output(result, "email.recipientCount"));

        // The keys themselves must be plain: a dotted key cannot be persisted, and the failure would land
        // after the message had already been delivered.
        for (String key : result.outputs().keySet()) {
            assertFalse(key.contains("."), () -> "output key '" + key + "' contains a dot");
        }

        assertEquals(1, server.messages().size());
        String message = server.messages().get(0);
        assertTrue(message.contains("Subject: Order approved"));
        assertTrue(message.contains("Your order has been approved."));

        // The password reached the server as an AUTH exchange, which is the only place it belongs.
        assertTrue(server.commands().stream().anyMatch(command -> command.startsWith("AUTH")));
    }

    @Test
    @DisplayName("resolved variables arrive in the message the server receives")
    void resolvesIntoTheMessage() {
        Map<String, Object> configuration = node();
        configuration.put("to", List.of("${customer.email}"));
        configuration.put("subject", "Order ${order.id} approved");
        configuration.put("body", "Hello ${customer.name}, order ${order.id} is on its way.");

        NodeExecutionResult result = run(configuration, Map.of(
                "customer.email", "sam@example.com",
                "customer.name", "Sam",
                "order.id", "A-1001"));

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        String message = server.messages().get(0);
        assertTrue(message.contains("sam@example.com"));
        assertTrue(message.contains("Subject: Order A-1001 approved"));
        assertTrue(message.contains("Hello Sam"));
    }

    @Test
    @DisplayName("cc and bcc are delivered, and bcc does not appear in the message")
    void bccStaysHidden() {
        Map<String, Object> configuration = node();
        configuration.put("cc", List.of("manager@example.com"));
        configuration.put("bcc", List.of("archive@example.com"));

        NodeExecutionResult result = run(configuration);

        assertTrue(result.isSuccess());
        assertEquals(3, output(result, "email.recipientCount"));

        // Three RCPT TO commands: the transport delivers to the bcc address.
        assertEquals(3, server.commands().stream().filter(c -> c.startsWith("RCPT TO")).count());

        String message = server.messages().get(0);
        assertTrue(message.contains("manager@example.com"));
        // ...and strips it from the headers, which is the entire point of a bcc.
        assertFalse(message.contains("archive@example.com"));
    }

    @Test
    @DisplayName("an attachment arrives as a MIME part with its filename")
    void attachment() {
        Map<String, Object> configuration = node();
        configuration.put("attachments", List.of(Map.of(
                "fileName", "invoice.txt",
                "source", "BASE64",
                "value", Base64.getEncoder().encodeToString("total: 42.00".getBytes()),
                "contentType", "text/plain")));

        NodeExecutionResult result = run(configuration);

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        String message = server.messages().get(0);
        assertTrue(message.contains("multipart"));
        assertTrue(message.contains("invoice.txt"));
    }

    @Test
    @DisplayName("TEST_CONNECTION authenticates and disconnects without sending anything")
    void testConnectionSendsNothing() {
        Map<String, Object> configuration = node();
        configuration.put("operation", "TEST_CONNECTION");

        NodeExecutionResult result = run(configuration);

        assertTrue(result.isSuccess(), () -> result.errorCode() + " " + result.errorMessage());
        assertEquals(true, output(result, "email.connectionVerified"));
        assertTrue(server.commands().stream().anyMatch(command -> command.startsWith("AUTH")));
        // The whole value of this operation: the settings are proven and nobody received mail.
        assertTrue(server.messages().isEmpty());
        assertTrue(server.commands().stream().noneMatch(command -> command.startsWith("DATA")));
    }

    @Test
    @DisplayName("a wrong password fails as an authentication error, and is never retried")
    void wrongPassword() {
        execution = TestExecution.of(node(), Map.of("smtp.password", "not-the-password"));
        plugin.initialize(execution);

        NodeExecutionResult result = plugin.execute(execution);

        assertTrue(result.isFailed());
        assertEquals(EmailErrors.AUTHENTICATION_FAILED, result.errorCode());
        // Retrying a rejected password is how an account gets locked out.
        assertFalse(result.retryable());
        assertTrue(server.messages().isEmpty());
    }

    @Test
    @DisplayName("a recipient the server refuses fails permanently")
    void refusedRecipient() {
        server.rejectRecipients("550 5.1.1 <customer@example.com>: Recipient address rejected");

        NodeExecutionResult result = run(node());

        assertTrue(result.isFailed());
        assertEquals(EmailErrors.RECIPIENT_REJECTED, result.errorCode());
        assertFalse(result.retryable());
    }

    @Test
    @DisplayName("a greylisted recipient fails temporarily, so the engine will try again")
    void greylisted() {
        server.rejectRecipients("450 4.2.0 Greylisted, try again in 5 minutes");

        NodeExecutionResult result = run(node());

        assertTrue(result.isFailed());
        assertEquals(EmailErrors.TEMPORARY_FAILURE, result.errorCode());
        assertTrue(result.retryable());
    }

    @Test
    @DisplayName("nothing sent to a closed port waits forever")
    void connectionRefused() throws IOException {
        server.close();

        NodeExecutionResult result = run(node());

        assertTrue(result.isFailed());
        assertEquals(EmailErrors.CONNECTION_FAILED, result.errorCode());
        assertTrue(result.retryable());
    }

    @Test
    @DisplayName("no log line written during a send contains the password")
    void logsNeverCarryThePassword() {
        run(node());

        assertFalse(String.join("\n", execution.logLines()).contains(PASSWORD));
    }

    @Test
    @DisplayName("a literal password in the workflow is refused before anything is connected to")
    void refusesLiteralPassword() {
        Map<String, Object> configuration = node();
        configuration.remove("passwordSecret");
        configuration.put("password", PASSWORD);

        NodeExecutionResult result = run(configuration);

        assertTrue(result.isFailed());
        assertEquals(EmailErrors.CONFIGURATION_INVALID, result.errorCode());
        assertFalse(result.retryable());
        assertTrue(server.commands().isEmpty());
    }

    /**
     * The same send against a real provider.
     *
     * <p>Skipped unless the environment names one, because a test that needs a credential and delivers mail to
     * a real mailbox cannot run on every build. Set {@code EMAIL_PLUGIN_SMTP_HOST}, {@code _PORT},
     * {@code _USERNAME}, {@code _PASSWORD}, {@code _FROM} and {@code _TO} to run it — a Gmail app password or
     * a SendGrid key against a mailbox you own is the intended use.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "EMAIL_PLUGIN_SMTP_HOST", matches = ".+")
    @DisplayName("against a real SMTP server, when one is configured")
    void realServer() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("smtpHost", System.getenv("EMAIL_PLUGIN_SMTP_HOST"));
        configuration.put("smtpPort", Integer.parseInt(
                System.getenv().getOrDefault("EMAIL_PLUGIN_SMTP_PORT", "587")));
        configuration.put("security", System.getenv().getOrDefault("EMAIL_PLUGIN_SMTP_SECURITY", "STARTTLS"));
        configuration.put("username", System.getenv("EMAIL_PLUGIN_SMTP_USERNAME"));
        configuration.put("passwordSecret", "smtp.password");
        configuration.put("fromEmail", System.getenv("EMAIL_PLUGIN_SMTP_FROM"));
        configuration.put("to", List.of(System.getenv("EMAIL_PLUGIN_SMTP_TO")));
        configuration.put("subject", "Workflow engine email plugin integration test");
        configuration.put("body", "Sent by the email plugin's integration test.");

        TestExecution real = TestExecution.of(configuration,
                Map.of("smtp.password", System.getenv("EMAIL_PLUGIN_SMTP_PASSWORD")));
        plugin.initialize(real);

        NodeExecutionResult result = plugin.execute(real);

        assertTrue(result.isSuccess(), () -> result.errorCode() + " " + result.errorMessage());
        assertNotNull(output(result, "email.messageId"));
    }
}
