package com.orchpilot.workflow.plugins.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Presets, security parsing and attachment sources. */
class EmailProviderTest {

    @Test
    @DisplayName("a preset carries a host and a port and never a credential")
    void presetsCarryNoCredentials() {
        for (EmailProvider provider : EmailProvider.values()) {
            assertTrue(provider.port() >= 0);
            // suggestedUsername is a public, fixed username some providers require — never a secret.
            if (provider.suggestedUsername() != null) {
                assertEquals(EmailProvider.SENDGRID, provider);
            }
        }
    }

    @Test
    @DisplayName("SendGrid's username is the literal 'apikey'")
    void sendgrid() {
        // Not a secret and not derived from one: SendGrid's SMTP username is this string for every account,
        // and the API key is the password.
        assertEquals("apikey", EmailProvider.SENDGRID.suggestedUsername());
        assertEquals("smtp.sendgrid.net", EmailProvider.SENDGRID.host());
    }

    @Test
    @DisplayName("Amazon SES supplies no host because its endpoints are regional")
    void ses() {
        assertNull(EmailProvider.AMAZON_SES.host());
        assertFalse(EmailProvider.AMAZON_SES.suppliesHost());
    }

    @Test
    @DisplayName("common aliases land on the right provider")
    void aliases() {
        assertEquals(EmailProvider.MICROSOFT365, EmailProvider.parse("office365"));
        assertEquals(EmailProvider.MICROSOFT365, EmailProvider.parse("Outlook"));
        assertEquals(EmailProvider.AMAZON_SES, EmailProvider.parse("aws-ses"));
        assertEquals(EmailProvider.GMAIL, EmailProvider.parse("Google Workspace"));
    }

    @Test
    @DisplayName("an unrecognised name falls back to CUSTOM rather than failing")
    void unknownProvider() {
        // 'Custom' means everything is typed in, which is exactly what an operator naming an unknown provider
        // is going to do. An error here would block a workflow over a spelling.
        assertEquals(EmailProvider.CUSTOM, EmailProvider.parse("our-relay"));
        assertEquals(EmailProvider.CUSTOM, EmailProvider.parse(""));
        assertEquals(EmailProvider.CUSTOM, EmailProvider.parse(null));
    }

    @Test
    @DisplayName("security names are parsed loosely, defaulting to STARTTLS")
    void securityParsing() {
        assertEquals(SmtpSecurity.SSL_TLS, SmtpSecurity.parse("ssl"));
        assertEquals(SmtpSecurity.SSL_TLS, SmtpSecurity.parse("SSL/TLS"));
        assertEquals(SmtpSecurity.SSL_TLS, SmtpSecurity.parse("smtps"));
        assertEquals(SmtpSecurity.NONE, SmtpSecurity.parse("none"));
        assertEquals(SmtpSecurity.STARTTLS, SmtpSecurity.parse("anything else"));
        assertEquals(SmtpSecurity.STARTTLS, SmtpSecurity.parse(null));
    }

    @Test
    @DisplayName("only NONE is unencrypted")
    void encryption() {
        assertFalse(SmtpSecurity.NONE.isEncrypted());
        assertTrue(SmtpSecurity.STARTTLS.isEncrypted());
        assertTrue(SmtpSecurity.SSL_TLS.isEncrypted());
    }

    @Test
    @DisplayName("a base64 attachment decodes to the original bytes")
    void base64Attachment() {
        EmailAttachment attachment = EmailAttachment.from(Map.of(
                "fileName", "note.txt",
                "source", "BASE64",
                "value", Base64.getEncoder().encodeToString("hello".getBytes())), value -> value);

        assertArrayEquals("hello".getBytes(), attachment.content());
    }

    @Test
    @DisplayName("a variable holding plain text is attached as that text")
    void variableAttachment() {
        EmailAttachment attachment = EmailAttachment.from(Map.of(
                "fileName", "report.csv",
                "source", "VARIABLE",
                "value", "${report.body}"), value -> "id,name\n1,Sam");

        assertArrayEquals("id,name\n1,Sam".getBytes(), attachment.content());
    }

    @Test
    @DisplayName("no source names a filesystem path")
    void noFilesystemSource() {
        // Deliberate. A node that could attach an arbitrary path — one assembled from a variable somebody else
        // controls, say — turns 'send an email' into 'read any file the engine can read and post it off site'.
        for (EmailAttachment.Source source : EmailAttachment.Source.values()) {
            assertFalse(source.name().contains("PATH"));
            assertFalse(source.name().contains("FILESYSTEM"));
        }
        // A path-looking source name is read as VARIABLE, so it fetches nothing from disk.
        assertEquals(EmailAttachment.Source.VARIABLE, EmailAttachment.Source.parse("FILE_PATH"));
    }

    @Test
    @DisplayName("a source this plugin cannot fetch fails loudly rather than attaching nothing")
    void unsupportedSource() {
        EmailAttachment attachment = EmailAttachment.from(Map.of(
                "fileName", "invoice.pdf",
                "source", "S3",
                "value", "s3://bucket/invoice.pdf"), value -> value);

        assertEquals(EmailAttachment.Source.OBJECT_STORAGE, attachment.source());
        assertThrows(IllegalStateException.class, attachment::content);
    }
}
