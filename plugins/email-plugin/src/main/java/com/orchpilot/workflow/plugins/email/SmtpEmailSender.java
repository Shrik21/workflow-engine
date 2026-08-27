package com.orchpilot.workflow.plugins.email;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

/**
 * Sends a message over SMTP, and tests a connection without sending one.
 *
 * <h2>A session per send</h2>
 *
 * Sessions are not cached. Each send configures a session from that node's settings and closes the transport
 * afterwards. A cache keyed on the connection would have to be invalidated when a password rotates, would hold
 * an authenticated connection open to a third party between executions, and would let one workflow's settings
 * serve another's send if the key were ever wrong. Opening a connection costs a few hundred milliseconds; the
 * alternative costs correctness.
 *
 * <h2>Timeouts are always set</h2>
 *
 * All three of them. Jakarta Mail's defaults are infinite, so a server that accepts a connection and then says
 * nothing parks the executing thread until the engine's own node timeout fires — and on a pool of threads,
 * enough of those stop the engine doing anything else.
 *
 * <h2>What this class does not do</h2>
 *
 * It does not decide whether the sender is allowed to send as that address. SPF, DKIM, DMARC and the
 * provider's own sender verification decide that, at the server. A client that tried to work around any of
 * them would be writing a spoofing tool.
 */
final class SmtpEmailSender {

    /** What a send produced, when it worked. */
    record SentMessage(String messageId, Instant sentAt, int recipientCount) {
    }

    private SmtpEmailSender() {
    }

    /**
     * Builds the session properties for a connection.
     *
     * @param settings the node's settings
     * @return properties for {@link Session}
     */
    static Properties properties(EmailNodeConfiguration settings) {
        Properties properties = new Properties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.host", settings.host());
        properties.put("mail.smtp.port", String.valueOf(settings.port()));
        properties.put("mail.smtp.auth", String.valueOf(settings.authenticationRequired()));

        // Exactly one security mode writes its properties; see SmtpSecurity.
        settings.security().apply(properties);

        if (settings.security().isEncrypted()) {
            // Without this the certificate is negotiated but never checked against the host, which leaves the
            // connection encrypted and still open to anybody able to answer for that address.
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        }

        properties.put("mail.smtp.connectiontimeout", String.valueOf(settings.connectionTimeoutMillis()));
        properties.put("mail.smtp.timeout", String.valueOf(settings.readTimeoutMillis()));
        properties.put("mail.smtp.writetimeout", String.valueOf(settings.readTimeoutMillis()));
        return properties;
    }

    /**
     * Opens a session for these settings.
     *
     * @param settings the node's settings
     * @return the session
     */
    static Session session(EmailNodeConfiguration settings) {
        Properties properties = properties(settings);
        if (!settings.authenticationRequired()) {
            return Session.getInstance(properties);
        }
        return Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(settings.username(), settings.password());
            }
        });
    }

    /**
     * Connects, authenticates, and disconnects without sending anything.
     *
     * <p>This is a real conversation with the server: DNS, TCP, the TLS handshake, and the AUTH exchange. It
     * proves the settings work, which is the only thing worth proving before a workflow depends on them.
     *
     * @param settings the node's settings
     * @throws MessagingException when any step fails
     */
    static void testConnection(EmailNodeConfiguration settings) throws MessagingException {
        Session session = session(settings);
        try (Transport transport = session.getTransport("smtp")) {
            if (settings.authenticationRequired()) {
                transport.connect(settings.host(), settings.port(), settings.username(), settings.password());
            } else {
                transport.connect(settings.host(), settings.port(), null, null);
            }
        }
    }

    /**
     * Builds and sends the message.
     *
     * @param settings the node's settings
     * @return what was sent
     * @throws MessagingException when the server refuses it or the connection fails
     */
    static SentMessage send(EmailNodeConfiguration settings) throws MessagingException {
        Session session = session(settings);
        MimeMessage message = build(session, settings);

        try (Transport transport = session.getTransport("smtp")) {
            if (settings.authenticationRequired()) {
                transport.connect(settings.host(), settings.port(), settings.username(), settings.password());
            } else {
                transport.connect(settings.host(), settings.port(), null, null);
            }
            transport.sendMessage(message, message.getAllRecipients());
        }

        return new SentMessage(message.getMessageID(), Instant.now(), settings.recipientCount());
    }

    /**
     * Assembles the MIME message.
     *
     * @param session  the session it belongs to
     * @param settings the node's settings
     * @return the message, ready to send
     * @throws MessagingException when an address or a part cannot be built
     */
    static MimeMessage build(Session session, EmailNodeConfiguration settings) throws MessagingException {
        MimeMessage message = new MimeMessage(session);

        message.setFrom(from(settings));
        message.setRecipients(Message.RecipientType.TO, addresses(settings.to()));
        if (!settings.cc().isEmpty()) {
            message.setRecipients(Message.RecipientType.CC, addresses(settings.cc()));
        }
        if (!settings.bcc().isEmpty()) {
            // BCC recipients are set on the envelope and stripped from the header by the transport, which is
            // the whole point of them: the other recipients must not learn who else received it.
            message.setRecipients(Message.RecipientType.BCC, addresses(settings.bcc()));
        }
        if (settings.replyTo().isPresent()) {
            message.setReplyTo(addresses(List.of(settings.replyTo().get())));
        }

        message.setSubject(settings.subject(), StandardCharsets.UTF_8.name());
        message.setSentDate(new java.util.Date());

        if (settings.attachments().isEmpty()) {
            message.setContent(settings.body(), settings.bodyType().contentType());
        } else {
            message.setContent(multipart(settings));
        }
        // Fixes the Message-ID and headers now, so the id reported back is the one the server received.
        message.saveChanges();
        return message;
    }

    private static MimeMultipart multipart(EmailNodeConfiguration settings) throws MessagingException {
        MimeMultipart multipart = new MimeMultipart();

        MimeBodyPart bodyPart = new MimeBodyPart();
        bodyPart.setContent(settings.body(), settings.bodyType().contentType());
        multipart.addBodyPart(bodyPart);

        for (EmailAttachment attachment : settings.attachments()) {
            MimeBodyPart part = new MimeBodyPart();
            byte[] content = attachment.content();
            part.setDataHandler(new jakarta.activation.DataHandler(
                    new ByteArrayDataSource(content, attachment.contentType())));
            part.setFileName(attachment.fileName());
            multipart.addBodyPart(part);
        }
        return multipart;
    }

    private static InternetAddress from(EmailNodeConfiguration settings) throws MessagingException {
        if (settings.fromName().isEmpty()) {
            return new InternetAddress(settings.fromEmail());
        }
        try {
            return new InternetAddress(settings.fromEmail(), settings.fromName().orElseThrow(),
                    StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            // UTF-8 is always present; falling back rather than failing a send over a display name.
            return new InternetAddress(settings.fromEmail());
        }
    }

    private static InternetAddress[] addresses(List<String> values) throws MessagingException {
        InternetAddress[] parsed = new InternetAddress[values.size()];
        for (int index = 0; index < values.size(); index++) {
            parsed[index] = new InternetAddress(values.get(index));
        }
        return parsed;
    }
}
