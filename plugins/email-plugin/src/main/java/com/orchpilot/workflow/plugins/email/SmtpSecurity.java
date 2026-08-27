package com.orchpilot.workflow.plugins.email;

import java.util.Locale;
import java.util.Properties;

/**
 * How the connection to the SMTP server is protected.
 *
 * <p>The two encrypted modes are mutually exclusive and this enum is what enforces that. STARTTLS opens a
 * plaintext connection and upgrades it; SSL/TLS is encrypted from the first byte. Setting both leaves the mail
 * library to pick one, which produces a connection that works on one server and fails on the next for reasons
 * nobody can see from the configuration.
 */
public enum SmtpSecurity {

    /**
     * No encryption.
     *
     * <p>Offered because internal relays on a trusted network legitimately run this way. Everything sent —
     * credentials included — is readable by anything on the path, so it should not be chosen for a server
     * reached over the internet.
     */
    NONE {
        @Override
        void apply(Properties properties) {
            properties.put("mail.smtp.starttls.enable", "false");
            properties.put("mail.smtp.ssl.enable", "false");
        }
    },

    /**
     * Connect in plaintext, then upgrade. The usual choice, and what port 587 expects.
     *
     * <p>{@code required=true} as well as {@code enable=true}: with only the first, a server that fails to
     * offer STARTTLS causes a silent fall back to sending the password in clear.
     */
    STARTTLS {
        @Override
        void apply(Properties properties) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.ssl.enable", "false");
        }
    },

    /** Encrypted from the first byte. What port 465 expects. */
    SSL_TLS {
        @Override
        void apply(Properties properties) {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.starttls.enable", "false");
        }
    };

    /**
     * Writes this mode's properties, and only this mode's.
     *
     * @param properties the session properties to configure
     */
    abstract void apply(Properties properties);

    /**
     * @param value a configured name, in any case, with dashes or underscores
     * @return the mode, defaulting to STARTTLS, which is right for the port most servers listen on
     */
    public static SmtpSecurity parse(String value) {
        if (value == null || value.isBlank()) {
            return STARTTLS;
        }
        String name = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace('/', '_');
        return switch (name) {
            case "NONE", "PLAIN", "NO", "FALSE" -> NONE;
            case "SSL", "TLS", "SSL_TLS", "SSLTLS", "SMTPS" -> SSL_TLS;
            default -> STARTTLS;
        };
    }

    /** @return whether this mode encrypts the connection at all */
    public boolean isEncrypted() {
        return this != NONE;
    }
}
