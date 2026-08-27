package com.orchpilot.workflow.plugins.email.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An SMTP server, in about two hundred lines, that speaks enough of the protocol to be sent mail.
 *
 * <p>It exists so the send path can be tested for real: a socket, a greeting, EHLO, AUTH, MAIL FROM, RCPT TO,
 * DATA, and a queued reply. Mocking {@code Transport} would test that Jakarta Mail's API was called, which is
 * the part least likely to be wrong. What is worth proving is that a message this plugin builds is one a
 * server accepts, that authentication actually happens, and that a rejection comes back classified correctly.
 *
 * <p>Plaintext only, on a loopback port. TLS would need a certificate and a trust store to go with it, and
 * would prove something about JSSE rather than about this plugin; the security properties are covered by
 * {@code SmtpSessionTest} instead.
 */
public final class FakeSmtpServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private final AtomicInteger queued = new AtomicInteger();

    /** Every message body the server accepted, in order. */
    private final List<String> messages = new CopyOnWriteArrayList<>();

    /** Every command line the server was sent, for asserting that AUTH happened at all. */
    private final List<String> commands = new CopyOnWriteArrayList<>();

    private volatile String expectedUsername;
    private volatile String expectedPassword;

    /** The reply to RCPT TO. Changed by a test to make the server refuse a recipient. */
    private volatile String recipientReply = "250 2.1.5 Ok";

    private volatile boolean running = true;

    private FakeSmtpServer(String username, String password) throws IOException {
        this.expectedUsername = username;
        this.expectedPassword = password;
        this.serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        this.acceptor = new Thread(this::acceptLoop, "fake-smtp-acceptor");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    /**
     * Starts a server that requires these credentials.
     *
     * @param username the username it accepts
     * @param password the password it accepts
     * @return the running server
     * @throws IOException when no loopback port can be bound
     */
    public static FakeSmtpServer requiring(String username, String password) throws IOException {
        return new FakeSmtpServer(username, password);
    }

    /** @return the loopback port it is listening on */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** @return the messages it accepted */
    public List<String> messages() {
        return messages;
    }

    /** @return every command line it received */
    public List<String> commands() {
        return commands;
    }

    /** Makes the server refuse recipients permanently, as it would an unknown mailbox. */
    public void rejectRecipients(String reply) {
        this.recipientReply = reply;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread session = new Thread(() -> handle(socket), "fake-smtp-session");
                session.setDaemon(true);
                session.start();
            } catch (IOException ex) {
                // The socket was closed, which is how this loop is meant to end.
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.ISO_8859_1)) {

            say(out, "220 localhost Fake ESMTP");
            boolean authenticated = expectedUsername == null;

            String line;
            while ((line = in.readLine()) != null) {
                commands.add(line);
                String command = line.toUpperCase(Locale.ROOT);

                if (command.startsWith("EHLO")) {
                    // The AUTH line is what makes the client attempt authentication at all.
                    say(out, "250-localhost");
                    say(out, "250-AUTH PLAIN LOGIN");
                    say(out, "250-8BITMIME");
                    say(out, "250 OK");
                } else if (command.startsWith("HELO")) {
                    say(out, "250 localhost");
                } else if (command.startsWith("AUTH PLAIN")) {
                    authenticated = authenticatePlain(in, out, line);
                } else if (command.startsWith("AUTH LOGIN")) {
                    authenticated = authenticateLogin(in, out);
                } else if (command.startsWith("MAIL FROM")) {
                    if (!authenticated) {
                        say(out, "530 5.7.0 Authentication required");
                    } else {
                        say(out, "250 2.1.0 Ok");
                    }
                } else if (command.startsWith("RCPT TO")) {
                    say(out, recipientReply);
                } else if (command.startsWith("DATA")) {
                    say(out, "354 End data with <CR><LF>.<CR><LF>");
                    messages.add(readMessage(in));
                    say(out, "250 2.0.0 Ok: queued as FAKE" + queued.incrementAndGet());
                } else if (command.startsWith("QUIT")) {
                    say(out, "221 2.0.0 Bye");
                    return;
                } else if (command.startsWith("RSET") || command.startsWith("NOOP")) {
                    say(out, "250 2.0.0 Ok");
                } else {
                    say(out, "502 5.5.2 Not implemented");
                }
            }
        } catch (IOException ex) {
            // A client that hung up mid-conversation; nothing to do about it here.
        }
    }

    /** AUTH PLAIN carries {@code \0username\0password} in base64, either on the command or on the next line. */
    private boolean authenticatePlain(BufferedReader in, PrintWriter out, String line) throws IOException {
        String encoded = line.length() > "AUTH PLAIN".length()
                ? line.substring("AUTH PLAIN".length()).trim()
                : "";
        if (encoded.isEmpty()) {
            say(out, "334 ");
            encoded = in.readLine();
        }
        String decoded = new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        String[] parts = decoded.split("\0");
        String username = parts.length > 1 ? parts[1] : "";
        String password = parts.length > 2 ? parts[2] : "";
        return reply(out, username, password);
    }

    /** AUTH LOGIN is a two-step exchange, each side base64. */
    private boolean authenticateLogin(BufferedReader in, PrintWriter out) throws IOException {
        say(out, "334 " + Base64.getEncoder().encodeToString("Username:".getBytes(StandardCharsets.UTF_8)));
        String username = decode(in.readLine());
        say(out, "334 " + Base64.getEncoder().encodeToString("Password:".getBytes(StandardCharsets.UTF_8)));
        String password = decode(in.readLine());
        return reply(out, username, password);
    }

    private boolean reply(PrintWriter out, String username, String password) {
        boolean accepted = expectedUsername == null
                || (expectedUsername.equals(username) && expectedPassword.equals(password));
        say(out, accepted ? "235 2.7.0 Accepted" : "535 5.7.8 Authentication credentials invalid");
        return accepted;
    }

    private static String decode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return new String(Base64.getDecoder().decode(value.trim()), StandardCharsets.UTF_8);
    }

    /** Reads to the lone dot that ends DATA, undoing the transparency dot on the way. */
    private static String readMessage(BufferedReader in) throws IOException {
        StringBuilder message = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (".".equals(line)) {
                break;
            }
            message.append(line.startsWith("..") ? line.substring(1) : line).append('\n');
        }
        return message.toString();
    }

    private static void say(PrintWriter out, String reply) {
        out.print(reply + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
        acceptor.interrupt();
    }
}
