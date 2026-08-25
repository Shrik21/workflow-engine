package com.orchpilot.workflow.plugins.email;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Turns a mail failure into a code, a sentence, and a decision about retrying.
 *
 * <h2>Retrying the wrong thing is worse than not retrying</h2>
 *
 * A wrong password retried three times with backoff is three more failed sign-ins against the provider, which
 * is how an account gets locked. A malformed recipient retried is the same rejection three times. Only
 * failures that might succeed unchanged a moment later are marked retryable: timeouts, refused connections,
 * and the 4xx SMTP codes that mean "not now".
 *
 * <h2>Reading the SMTP reply code</h2>
 *
 * SMTP says this itself, and says it clearly: 4xx is temporary, 5xx is permanent. Where a reply code can be
 * found in the exception it decides, because the server knows better than any guess made from the message
 * text. The text is only consulted when there is no code, which is the case for failures that happen before
 * any SMTP conversation — DNS, TCP, TLS.
 */
final class EmailErrors {

    // Configuration and validation
    static final String CONFIGURATION_INVALID = "SMTP_CONFIGURATION_INVALID";
    static final String INVALID_SENDER = "INVALID_SENDER";
    static final String INVALID_RECIPIENT = "INVALID_RECIPIENT";
    static final String ATTACHMENT_TOO_LARGE = "ATTACHMENT_TOO_LARGE";
    static final String MESSAGE_TOO_LARGE = "MESSAGE_TOO_LARGE";

    // Connection and transport
    static final String CONNECTION_FAILED = "SMTP_CONNECTION_FAILED";
    static final String AUTHENTICATION_FAILED = "SMTP_AUTHENTICATION_FAILED";
    static final String TLS_FAILED = "SMTP_TLS_FAILED";
    static final String SSL_FAILED = "SMTP_SSL_FAILED";
    static final String TIMEOUT = "SMTP_TIMEOUT";
    static final String TEMPORARY_FAILURE = "SMTP_TEMPORARY_FAILURE";
    static final String RECIPIENT_REJECTED = "RECIPIENT_REJECTED";
    static final String SEND_FAILED = "EMAIL_SEND_FAILED";

    private EmailErrors() {
    }

    /**
     * What went wrong, and whether trying again could help.
     *
     * @param code      the structured code
     * @param message   a sentence for whoever reads the execution record
     * @param retryable whether the engine should apply its backoff and try again
     */
    record Classification(String code, String message, boolean retryable) {
    }

    /**
     * Classifies a failure.
     *
     * @param failure what was thrown
     * @param host    the server being talked to, named in the message because "connection refused" without it
     *                is unactionable
     * @param port    the port, for the same reason
     * @return the classification
     */
    static Classification classify(Exception failure, String host, int port) {
        String where = host + ":" + port;

        if (failure instanceof AuthenticationFailedException) {
            // Never retried. Repeating a rejected password is how an account gets locked out.
            return new Classification(AUTHENTICATION_FAILED,
                    "The SMTP server at " + where + " rejected the username and password.", false);
        }
        if (failure instanceof SendFailedException sendFailure) {
            return classifySendFailure(sendFailure, where);
        }
        if (failure instanceof SocketTimeoutException) {
            return new Classification(TIMEOUT, "Timed out talking to " + where + ".", true);
        }
        if (failure instanceof UnknownHostException) {
            // A name that does not resolve will not resolve on the next attempt either.
            return new Classification(CONNECTION_FAILED,
                    "The host '" + host + "' could not be resolved.", false);
        }
        if (failure instanceof ConnectException || failure instanceof NoRouteToHostException) {
            return new Classification(CONNECTION_FAILED,
                    "Could not open a connection to " + where + ".", true);
        }
        if (failure instanceof SSLException) {
            return sslClassification(failure, where);
        }
        if (failure instanceof MessagingException messaging) {
            return classifyMessaging(messaging, where);
        }
        if (failure instanceof IOException) {
            return new Classification(CONNECTION_FAILED,
                    "The connection to " + where + " failed: " + safe(failure), true);
        }
        return new Classification(SEND_FAILED, "The email could not be sent: " + safe(failure), false);
    }

    private static Classification classifySendFailure(SendFailedException failure, String where) {
        // Some addresses were refused. Permanent unless the reply code says otherwise, because a rejected
        // address is normally rejected every time.
        int code = replyCode(failure);
        boolean temporary = code >= 400 && code < 500;
        return new Classification(temporary ? TEMPORARY_FAILURE : RECIPIENT_REJECTED,
                "The server at " + where + " refused one or more recipients"
                        + (code > 0 ? " (SMTP " + code + ")" : "") + ".",
                temporary);
    }

    private static Classification classifyMessaging(MessagingException failure, String where) {
        Throwable cause = failure.getCause();
        if (cause instanceof SSLException) {
            return sslClassification(cause, where);
        }
        if (cause instanceof SocketTimeoutException) {
            return new Classification(TIMEOUT, "Timed out talking to " + where + ".", true);
        }
        if (cause instanceof UnknownHostException) {
            return new Classification(CONNECTION_FAILED,
                    "The host in " + where + " could not be resolved.", false);
        }
        if (cause instanceof ConnectException) {
            return new Classification(CONNECTION_FAILED,
                    "Could not open a connection to " + where + ".", true);
        }

        String text = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("starttls")) {
            return new Classification(TLS_FAILED,
                    "STARTTLS could not be negotiated with " + where
                            + ". The server may not support it on this port.", false);
        }

        int code = replyCode(failure);
        if (code >= 400 && code < 500) {
            return new Classification(TEMPORARY_FAILURE,
                    "The server at " + where + " reported a temporary failure (SMTP " + code + ").", true);
        }
        if (code == 552 || code == 523) {
            return new Classification(MESSAGE_TOO_LARGE,
                    "The server at " + where + " rejected the message as too large.", false);
        }
        if (code >= 500) {
            return new Classification(SEND_FAILED,
                    "The server at " + where + " refused the message (SMTP " + code + ").", false);
        }
        return new Classification(CONNECTION_FAILED,
                "The conversation with " + where + " failed: " + safe(failure), true);
    }

    private static Classification sslClassification(Throwable failure, String where) {
        String text = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        boolean startTls = text.contains("starttls");
        return new Classification(startTls ? TLS_FAILED : SSL_FAILED,
                "The encrypted connection to " + where + " could not be established. Check that the port "
                        + "matches the security mode: 587 usually expects STARTTLS and 465 expects SSL/TLS.",
                false);
    }

    /**
     * A reply code such as the {@code 451} in {@code 451 4.3.0 Temporary failure}.
     *
     * <h2>Why the whole chain is searched</h2>
     *
     * The code is rarely on the exception that gets caught. A refused recipient arrives as a
     * {@code SendFailedException} whose own message is {@code "Invalid Addresses"}, with the server's actual
     * reply on a chained exception reached through {@code getNextException()}. Reading only the outer message
     * finds no code at all, and no code means "permanent" — which would make every greylisted message a
     * permanent failure, when greylisting is a mechanism that works precisely by expecting the sender to try
     * again.
     *
     * <p>Jakarta Mail does expose the code on its own SMTP exception types, but reading it there would mean
     * compiling against the implementation rather than the {@code jakarta.mail} API. The text is searched
     * instead, which stays correct if the implementation is swapped.
     */
    private static int replyCode(MessagingException failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++) {
            int code = codeIn(current.getMessage());
            if (code > 0) {
                return code;
            }
            Throwable next = current instanceof MessagingException messaging
                    ? messaging.getNextException()
                    : null;
            current = next != null ? next : current.getCause();
        }
        return 0;
    }

    /** The first 4xx or 5xx standing on its own in the text, which is where an SMTP reply begins. */
    private static int codeIn(String message) {
        if (message == null) {
            return 0;
        }
        java.util.regex.Matcher matcher = REPLY_CODE.matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** Anchored on a word boundary so an address or a message id containing digits is not read as a code. */
    private static final java.util.regex.Pattern REPLY_CODE =
            java.util.regex.Pattern.compile("(?:^|[\\s(\\[])([45]\\d{2})(?=[\\s\\-]|$)");

    /**
     * The exception's message, never its stack.
     *
     * <p>Mail exceptions sometimes echo the SMTP conversation, which can include a base64 AUTH line. Anything
     * that looks like one is dropped rather than carried into an execution record an operator can read.
     */
    private static String safe(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("auth ") || lower.contains("password") || lower.contains("credential")) {
            return failure.getClass().getSimpleName() + " during authentication";
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }
}
