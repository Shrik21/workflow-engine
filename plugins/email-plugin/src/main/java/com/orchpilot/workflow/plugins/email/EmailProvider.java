package com.orchpilot.workflow.plugins.email;

import java.util.Locale;

/**
 * A known SMTP service, and the settings it expects.
 *
 * <h2>Presets are defaults, not constraints</h2>
 *
 * Choosing a provider fills in the host, port and security mode so nobody has to look them up. Every one of
 * those remains editable afterwards, because providers move endpoints, run regional variants, and offer ports
 * this list does not mention. A preset that could not be overridden would be a smaller version of the same
 * lookup problem.
 *
 * <h2>No credentials, ever</h2>
 *
 * A preset carries a host and a port. It never carries a username, a password or an API key, and there is no
 * field here that could hold one. Where a provider requires a fixed username — SendGrid's literal
 * {@code apikey} — that is offered as a suggestion the operator can see and change, not as a secret this code
 * knows.
 */
public enum EmailProvider {

    /** Everything typed in by hand. The default, and the only one that assumes nothing. */
    CUSTOM("Custom SMTP", null, 0, SmtpSecurity.STARTTLS, null),

    GMAIL("Gmail", "smtp.gmail.com", 587, SmtpSecurity.STARTTLS, null),

    MICROSOFT365("Microsoft 365", "smtp.office365.com", 587, SmtpSecurity.STARTTLS, null),

    YAHOO("Yahoo", "smtp.mail.yahoo.com", 587, SmtpSecurity.STARTTLS, null),

    ZOHO("Zoho", "smtp.zoho.com", 587, SmtpSecurity.STARTTLS, null),

    /** The username is the literal string {@code apikey}; the API key itself is the password. */
    SENDGRID("SendGrid", "smtp.sendgrid.net", 587, SmtpSecurity.STARTTLS, "apikey"),

    /**
     * Amazon SES.
     *
     * <p>No host, deliberately. SES endpoints are regional — {@code email-smtp.eu-west-1.amazonaws.com} and a
     * dozen others — and defaulting to one region would send mail from the wrong place for everybody else, or
     * fail to connect. The operator supplies their region's endpoint.
     */
    AMAZON_SES("Amazon SES", null, 587, SmtpSecurity.STARTTLS, null),

    MAILGUN("Mailgun", "smtp.mailgun.org", 587, SmtpSecurity.STARTTLS, null);

    private final String label;
    private final String host;
    private final int port;
    private final SmtpSecurity security;
    private final String suggestedUsername;

    EmailProvider(String label, String host, int port, SmtpSecurity security, String suggestedUsername) {
        this.label = label;
        this.host = host;
        this.port = port;
        this.security = security;
        this.suggestedUsername = suggestedUsername;
    }

    public String label() {
        return label;
    }

    /** @return the provider's SMTP host, or null when it has no single one */
    public String host() {
        return host;
    }

    /** @return the port, or 0 when the provider defines none */
    public int port() {
        return port;
    }

    public SmtpSecurity security() {
        return security;
    }

    /** @return the username this provider requires, where it requires a fixed one; otherwise null */
    public String suggestedUsername() {
        return suggestedUsername;
    }

    /** @return whether choosing this provider supplies a host, or the operator must */
    public boolean suppliesHost() {
        return host != null;
    }

    /**
     * @param value a configured name, in any case
     * @return the provider, defaulting to CUSTOM so an unknown name means "I will type it in" rather than an
     *         error the operator cannot act on
     */
    public static EmailProvider parse(String value) {
        if (value == null || value.isBlank()) {
            return CUSTOM;
        }
        String name = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (EmailProvider provider : values()) {
            if (provider.name().equals(name)) {
                return provider;
            }
        }
        return switch (name) {
            case "OFFICE365", "O365", "MICROSOFT", "OUTLOOK" -> MICROSOFT365;
            case "SES", "AWS_SES", "AWS" -> AMAZON_SES;
            case "GOOGLE", "GSUITE", "GOOGLE_WORKSPACE" -> GMAIL;
            default -> CUSTOM;
        };
    }
}
