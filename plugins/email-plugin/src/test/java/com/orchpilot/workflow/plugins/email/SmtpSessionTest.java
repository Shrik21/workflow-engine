package com.orchpilot.workflow.plugins.email;

import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session properties and the message that gets built.
 *
 * <p>No server is contacted. What is worth testing without one is the part that is easy to get wrong and
 * invisible until a particular server rejects it: which security properties are set, that the two encrypted
 * modes never appear together, that timeouts are always present, and that the message carries the addresses
 * and parts it should.
 */
class SmtpSessionTest {

    private static EmailNodeConfiguration settings(Map<String, Object> overrides) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("smtpHost", "smtp.company.com");
        values.put("smtpPort", 587);
        values.put("authenticationRequired", true);
        values.put("username", "workflow@company.com");
        values.put("passwordSecret", "smtp.password");
        values.put("fromEmail", "notifications@company.com");
        values.put("to", List.of("customer@example.com"));
        values.put("subject", "Order approved");
        values.put("body", "Your order has been approved.");
        values.putAll(overrides);

        NodeConfiguration configuration = new NodeConfiguration() {
            @Override
            public Optional<Object> find(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public Map<String, Object> asMap() {
                return values;
            }
        };
        return EmailNodeConfiguration.from(configuration, value -> value == null ? "" : value,
                name -> Optional.of("a-password"));
    }

    @Nested
    @DisplayName("Security properties")
    class Security {

        @Test
        @DisplayName("STARTTLS is required as well as enabled")
        void starttls() {
            Properties properties = SmtpEmailSender.properties(settings(Map.of("security", "STARTTLS")));

            assertEquals("true", properties.get("mail.smtp.starttls.enable"));
            // Without 'required', a server that fails to offer STARTTLS causes a silent fall back to sending
            // the password in clear.
            assertEquals("true", properties.get("mail.smtp.starttls.required"));
            assertEquals("false", properties.get("mail.smtp.ssl.enable"));
        }

        @Test
        @DisplayName("SSL/TLS never enables STARTTLS at the same time")
        void sslTls() {
            Properties properties = SmtpEmailSender.properties(settings(Map.of("security", "SSL_TLS")));

            assertEquals("true", properties.get("mail.smtp.ssl.enable"));
            assertEquals("false", properties.get("mail.smtp.starttls.enable"));
        }

        @Test
        @DisplayName("NONE disables both")
        void none() {
            Properties properties = SmtpEmailSender.properties(settings(Map.of("security", "NONE")));

            assertEquals("false", properties.get("mail.smtp.starttls.enable"));
            assertEquals("false", properties.get("mail.smtp.ssl.enable"));
        }

        @Test
        @DisplayName("an encrypted connection checks the certificate against the host")
        void checksServerIdentity() {
            // Encryption without this leaves the connection open to anything able to answer for the address.
            assertEquals("true", SmtpEmailSender.properties(settings(Map.of("security", "STARTTLS")))
                    .get("mail.smtp.ssl.checkserveridentity"));
        }

        @Test
        @DisplayName("all three timeouts are always set")
        void timeouts() {
            // Jakarta Mail's defaults are infinite, so a silent server would hold an engine thread until the
            // node timeout fired.
            Properties properties = SmtpEmailSender.properties(settings(Map.of()));

            assertNotNull(properties.get("mail.smtp.connectiontimeout"));
            assertNotNull(properties.get("mail.smtp.timeout"));
            assertNotNull(properties.get("mail.smtp.writetimeout"));
        }

        @Test
        @DisplayName("authentication is advertised only when it is required")
        void auth() {
            assertEquals("true", SmtpEmailSender.properties(settings(Map.of())).get("mail.smtp.auth"));
            assertEquals("false", SmtpEmailSender
                    .properties(settings(Map.of("authenticationRequired", false)))
                    .get("mail.smtp.auth"));
        }
    }

    @Nested
    @DisplayName("The message")
    class TheMessage {

        @Test
        @DisplayName("carries the sender, recipients and subject")
        void basics() throws Exception {
            EmailNodeConfiguration configured = settings(Map.of(
                    "cc", List.of("manager@example.com"),
                    "bcc", List.of("archive@example.com"),
                    "replyTo", "support@company.com",
                    "fromName", "Workflow Automation"));

            MimeMessage message = SmtpEmailSender.build(SmtpEmailSender.session(configured), configured);

            assertTrue(message.getFrom()[0].toString().contains("notifications@company.com"));
            assertTrue(message.getFrom()[0].toString().contains("Workflow Automation"));
            assertEquals("customer@example.com",
                    message.getRecipients(Message.RecipientType.TO)[0].toString());
            assertEquals("manager@example.com",
                    message.getRecipients(Message.RecipientType.CC)[0].toString());
            assertEquals("archive@example.com",
                    message.getRecipients(Message.RecipientType.BCC)[0].toString());
            assertEquals("support@company.com", message.getReplyTo()[0].toString());
            assertEquals("Order approved", message.getSubject());
        }

        @Test
        @DisplayName("an HTML body is sent as HTML")
        void htmlBody() throws Exception {
            EmailNodeConfiguration configured = settings(Map.of(
                    "bodyType", "HTML",
                    "body", "<h1>Approved</h1>"));

            MimeMessage message = SmtpEmailSender.build(SmtpEmailSender.session(configured), configured);

            assertTrue(message.getContentType().startsWith("text/html"));
            // The charset matters: without it, non-ASCII arrives as mojibake.
            assertTrue(message.getContentType().toLowerCase(java.util.Locale.ROOT).contains("utf-8"));
        }

        @Test
        @DisplayName("an attachment makes the message multipart, with the body still first")
        void attachment() throws Exception {
            String content = Base64.getEncoder().encodeToString("an invoice".getBytes());
            EmailNodeConfiguration configured = settings(Map.of(
                    "attachments", List.of(Map.of(
                            "fileName", "invoice.pdf",
                            "source", "BASE64",
                            "value", content,
                            "contentType", "application/pdf"))));

            MimeMessage message = SmtpEmailSender.build(SmtpEmailSender.session(configured), configured);
            MimeMultipart parts = (MimeMultipart) message.getContent();

            assertEquals(2, parts.getCount());
            assertEquals("invoice.pdf", parts.getBodyPart(1).getFileName());
        }

        @Test
        @DisplayName("a message with no attachments is not multipart")
        void plainMessage() throws Exception {
            EmailNodeConfiguration configured = settings(Map.of());

            MimeMessage message = SmtpEmailSender.build(SmtpEmailSender.session(configured), configured);

            assertFalse(message.getContentType().startsWith("multipart"));
        }
    }
}
