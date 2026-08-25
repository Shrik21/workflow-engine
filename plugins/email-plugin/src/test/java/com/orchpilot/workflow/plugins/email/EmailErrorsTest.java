package com.orchpilot.workflow.plugins.email;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which failures are worth trying again.
 *
 * <p>The retry decisions are the point of this class, and getting them wrong has consequences past a failed
 * workflow: a rejected password retried with backoff is three more failed sign-ins, which is how a provider
 * locks an account.
 */
class EmailErrorsTest {

    private static EmailErrors.Classification classify(Exception failure) {
        return EmailErrors.classify(failure, "smtp.company.com", 587);
    }

    @Nested
    @DisplayName("Never retried")
    class NeverRetried {

        @Test
        @DisplayName("a rejected password")
        void authentication() {
            EmailErrors.Classification result = classify(new AuthenticationFailedException("535 5.7.8"));

            assertEquals(EmailErrors.AUTHENTICATION_FAILED, result.code());
            assertFalse(result.retryable());
        }

        @Test
        @DisplayName("a host that does not resolve")
        void unknownHost() {
            // It will not resolve on the next attempt either.
            EmailErrors.Classification result = classify(new UnknownHostException("smtp.compnay.com"));

            assertEquals(EmailErrors.CONNECTION_FAILED, result.code());
            assertFalse(result.retryable());
        }

        @Test
        @DisplayName("a refused recipient")
        void rejectedRecipient() {
            EmailErrors.Classification result = classify(
                    new SendFailedException("550 5.1.1 User unknown"));

            assertEquals(EmailErrors.RECIPIENT_REJECTED, result.code());
            assertFalse(result.retryable());
        }

        @Test
        @DisplayName("a TLS handshake that failed")
        void tls() {
            // Almost always a port that does not match the security mode, which no amount of retrying fixes.
            EmailErrors.Classification result = classify(
                    new SSLHandshakeException("Unrecognized SSL message, plaintext connection?"));

            assertEquals(EmailErrors.SSL_FAILED, result.code());
            assertFalse(result.retryable());
            assertTrue(result.message().contains("587"));
        }

        @Test
        @DisplayName("a server that says 5xx")
        void permanentReply() {
            EmailErrors.Classification result = classify(
                    new MessagingException("554 5.7.1 Message rejected"));

            assertFalse(result.retryable());
        }

        @Test
        @DisplayName("a message the server calls too large")
        void tooLarge() {
            EmailErrors.Classification result = classify(
                    new MessagingException("552 Message size exceeds fixed maximum"));

            assertEquals(EmailErrors.MESSAGE_TOO_LARGE, result.code());
            assertFalse(result.retryable());
        }
    }

    @Nested
    @DisplayName("Worth trying again")
    class WorthRetrying {

        @Test
        @DisplayName("a timeout")
        void timeout() {
            EmailErrors.Classification result = classify(new SocketTimeoutException("Read timed out"));

            assertEquals(EmailErrors.TIMEOUT, result.code());
            assertTrue(result.retryable());
        }

        @Test
        @DisplayName("a refused connection")
        void refused() {
            EmailErrors.Classification result = classify(new ConnectException("Connection refused"));

            assertEquals(EmailErrors.CONNECTION_FAILED, result.code());
            assertTrue(result.retryable());
        }

        @Test
        @DisplayName("a 4xx reply, because SMTP says 4xx means not now")
        void temporary() {
            EmailErrors.Classification result = classify(
                    new MessagingException("451 4.3.0 Temporary failure, try again later"));

            assertEquals(EmailErrors.TEMPORARY_FAILURE, result.code());
            assertTrue(result.retryable());
        }

        @Test
        @DisplayName("a 4xx recipient refusal, because greylisting looks like this")
        void greylisted() {
            EmailErrors.Classification result = classify(
                    new SendFailedException("450 4.2.0 Greylisted, try again in 5 minutes"));

            assertEquals(EmailErrors.TEMPORARY_FAILURE, result.code());
            assertTrue(result.retryable());
        }

        @Test
        @DisplayName("a reply code chained behind 'Invalid Addresses' still decides")
        void codeOnTheChainedException() {
            // How a refused recipient actually arrives: the outer message says nothing useful and the server's
            // reply is on getNextException(). Reading only the outer message finds no code, and no code means
            // permanent — which would make every greylisted message a permanent failure.
            SendFailedException failure = new SendFailedException("Invalid Addresses");
            failure.setNextException(new MessagingException("450 4.7.1 Try again later"));

            EmailErrors.Classification result = classify(failure);

            assertEquals(EmailErrors.TEMPORARY_FAILURE, result.code());
            assertTrue(result.retryable());
        }
    }

    @Nested
    @DisplayName("What the message says")
    class Messages {

        @Test
        @DisplayName("it names the server, because 'connection refused' alone is unactionable")
        void namesTheServer() {
            assertTrue(classify(new ConnectException("Connection refused"))
                    .message().contains("smtp.company.com:587"));
        }

        @Test
        @DisplayName("it never carries text that could be part of an AUTH exchange")
        void neverEchoesCredentials() {
            // Mail exceptions sometimes echo the SMTP conversation, base64 AUTH line included.
            EmailErrors.Classification result = classify(
                    new MessagingException("AUTH LOGIN dXNlckBjb21wYW55LmNvbQ==  password rejected"));

            assertFalse(result.message().contains("dXNlckBjb21wYW55LmNvbQ=="));
        }

        @Test
        @DisplayName("a cause wrapped in a MessagingException is classified by the cause")
        void looksAtTheCause() {
            // Jakarta Mail wraps nearly every transport failure, so reading only the outer type would classify
            // every timeout as a generic send failure.
            EmailErrors.Classification result = classify(
                    new MessagingException("Could not connect", new SocketTimeoutException("connect timed out")));

            assertEquals(EmailErrors.TIMEOUT, result.code());
            assertTrue(result.retryable());
        }

        @Test
        @DisplayName("STARTTLS trouble says so, since it points at the port")
        void starttls() {
            EmailErrors.Classification result = classify(
                    new MessagingException("STARTTLS is required but the host did not advertise it"));

            assertEquals(EmailErrors.TLS_FAILED, result.code());
        }
    }
}
