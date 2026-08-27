package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Everything needed to open one MongoDB connection, resolved and ready.
 *
 * <h2>Two ways to describe a deployment</h2>
 *
 * A connection string, or its parts. Both are offered because both are what people have: a URI is what an
 * Atlas console hands you and what a container prints, while host/port/database is what a form fills in. The
 * URI wins when both are present, and the parts are then ignored rather than merged — merging would mean a
 * host typed in one field silently overriding one written in the other, which is a configuration nobody can
 * read back.
 *
 * <h2>The credentials are never in the workflow</h2>
 *
 * What a node stores is a <em>name</em>: a secret holding the password, or a credential id whose username and
 * password are stored as {@code <id>.username} and {@code <id>.password}. A URI containing credentials —
 * {@code mongodb://user:pass@host/db} — is refused outright, because it is the most natural thing in the world
 * to paste one in and it would put a database password into the workflow definition, every export of it, and
 * its version history. The same URI with the credentials supplied separately is accepted.
 */
final class MongoConnectionSettings {

    /** Somewhere between "the server is busy" and "the workflow has hung". */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_SERVER_SELECTION_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_MAX_POOL_SIZE = 20;
    private static final int DEFAULT_MIN_POOL_SIZE = 0;

    private final String uri;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String authenticationDatabase;
    private final String replicaSet;
    private final boolean tls;
    private final boolean tlsAllowInvalidHostnames;
    private final int connectTimeoutMillis;
    private final int socketTimeoutMillis;
    private final int serverSelectionTimeoutMillis;
    private final int maxPoolSize;
    private final int minPoolSize;
    private final String applicationName;

    private MongoConnectionSettings(Builder builder) {
        this.uri = builder.uri;
        this.host = builder.host;
        this.port = builder.port;
        this.database = builder.database;
        this.username = builder.username;
        this.password = builder.password;
        this.authenticationDatabase = builder.authenticationDatabase;
        this.replicaSet = builder.replicaSet;
        this.tls = builder.tls;
        this.tlsAllowInvalidHostnames = builder.tlsAllowInvalidHostnames;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.socketTimeoutMillis = builder.socketTimeoutMillis;
        this.serverSelectionTimeoutMillis = builder.serverSelectionTimeoutMillis;
        this.maxPoolSize = builder.maxPoolSize;
        this.minPoolSize = builder.minPoolSize;
        this.applicationName = builder.applicationName;
    }

    /**
     * Reads a node's connection configuration.
     *
     * @param configuration the node configuration
     * @param resolve       the engine's variable resolver, applied to every text field
     * @param secrets       the scoped secret provider, the only source of a password
     * @return the resolved settings
     * @throws PluginConfigurationException when credentials are written into the workflow
     */
    static MongoConnectionSettings from(NodeConfiguration configuration, UnaryOperator<String> resolve,
                                        SecretProvider secrets) {
        Builder builder = new Builder();

        builder.uri = resolve.apply(configuration.getString("connectionUri", "")).trim();
        if (!builder.uri.isBlank()) {
            refuseCredentialsInUri(builder.uri);
        }

        builder.host = resolve.apply(configuration.getString("host", "")).trim();
        builder.port = configuration.getInt("port", 27017);
        builder.database = resolve.apply(configuration.getString("database", "")).trim();
        builder.authenticationDatabase =
                resolve.apply(configuration.getString("authenticationDatabase", "")).trim();
        builder.replicaSet = resolve.apply(configuration.getString("replicaSet", "")).trim();

        builder.tls = configuration.getBoolean("tls", configuration.getBoolean("ssl", false));
        builder.tlsAllowInvalidHostnames = configuration.getBoolean("tlsAllowInvalidHostnames", false);

        builder.connectTimeoutMillis =
                configuration.getInt("connectionTimeoutMillis", DEFAULT_CONNECT_TIMEOUT_MS);
        builder.socketTimeoutMillis = configuration.getInt("socketTimeoutMillis", DEFAULT_SOCKET_TIMEOUT_MS);
        builder.serverSelectionTimeoutMillis =
                configuration.getInt("serverSelectionTimeoutMillis", DEFAULT_SERVER_SELECTION_TIMEOUT_MS);
        builder.maxPoolSize = configuration.getInt("maxPoolSize", DEFAULT_MAX_POOL_SIZE);
        builder.minPoolSize = configuration.getInt("minPoolSize", DEFAULT_MIN_POOL_SIZE);

        builder.applicationName = "workflow-engine";

        resolveCredentials(configuration, resolve, secrets, builder);

        return new MongoConnectionSettings(builder);
    }

    /**
     * Finds the username and password without either having been in the workflow.
     *
     * <p>In order of preference: a {@code credentialId} naming both halves in the secret store, a
     * {@code passwordSecret} naming the password alone, or a {@code password} field holding a
     * {@code ${secret.NAME}} reference the engine expands. A literal password is refused.
     */
    private static void resolveCredentials(NodeConfiguration configuration, UnaryOperator<String> resolve,
                                           SecretProvider secrets, Builder builder) {
        builder.username = resolve.apply(configuration.getString("username", "")).trim();

        String credentialId = configuration.getString("credentialId", "").trim();
        if (!credentialId.isBlank()) {
            if (builder.username.isBlank()) {
                builder.username = secrets.find(credentialId + ".username").orElse("");
            }
            builder.password = secrets.find(credentialId + ".password")
                    .orElseThrow(() -> new PluginConfigurationException(
                            "Credential '" + credentialId + "' has no stored password. Store it as the "
                                    + "secret '" + credentialId + ".password'."));
            return;
        }

        String passwordSecret = configuration.getString("passwordSecret", "").trim();
        if (!passwordSecret.isBlank()) {
            builder.password = secrets.find(passwordSecret)
                    .orElseThrow(() -> new PluginConfigurationException(
                            "No secret named '" + passwordSecret + "' is available to this plugin. Check the "
                                    + "name, and that the plugin's secret scopes allow it."));
            return;
        }

        String inline = configuration.getString("password", "").trim();
        if (inline.isBlank()) {
            builder.password = "";
            return;
        }
        if (inline.startsWith("${")) {
            builder.password = resolve.apply(inline);
            return;
        }
        throw new PluginConfigurationException(
                "A database password must not be written into the workflow. Store it as a secret and name it "
                        + "in 'passwordSecret', or use a credential id. A literal value here would be readable "
                        + "by anyone who can read this workflow, and would travel in every export of it.");
    }

    /**
     * Refuses {@code mongodb://user:pass@host/db}.
     *
     * <p>Pasting a working URI is the obvious thing to do and it is exactly what must not be stored. The
     * message says how to split it, because "invalid connection string" would leave somebody guessing.
     */
    private static void refuseCredentialsInUri(String uri) {
        int scheme = uri.indexOf("://");
        if (scheme < 0) {
            return;
        }
        int at = uri.indexOf('@', scheme);
        if (at < 0) {
            return;
        }
        String authority = uri.substring(scheme + 3, at);
        if (authority.isBlank()) {
            return;
        }
        throw new PluginConfigurationException(
                "The connection string contains credentials. Remove the 'user:password@' part and supply the "
                        + "username in 'username' and the password through 'passwordSecret' or 'credentialId'; "
                        + "the rest of the URI, including mongodb+srv:// and its options, is kept as written. "
                        + "A URI with credentials in it would be stored in the workflow definition.");
    }

    /**
     * Everything wrong with these settings, all of it at once.
     *
     * @return the problems, empty when the connection can be opened
     */
    List<String> validate() {
        List<String> problems = new ArrayList<>();

        if (uri.isBlank() && host.isBlank()) {
            problems.add("A connection string or a host is required.");
        }
        if (!uri.isBlank()) {
            try {
                new ConnectionString(uri);
            } catch (IllegalArgumentException ex) {
                problems.add("The connection string could not be parsed: " + ex.getMessage());
            }
        }
        if (uri.isBlank() && (port < 1 || port > 65_535)) {
            problems.add("The port must be between 1 and 65535, not " + port + ".");
        }
        if (database().isBlank()) {
            problems.add("A database is required, either in the connection string or in 'database'.");
        }
        if (!password.isBlank() && username.isBlank()) {
            problems.add("A password was resolved but no username was given.");
        }
        if (maxPoolSize < 1) {
            problems.add("The maximum pool size must be at least 1.");
        }
        if (minPoolSize < 0 || minPoolSize > maxPoolSize) {
            problems.add("The minimum pool size must be between 0 and the maximum (" + maxPoolSize + ").");
        }
        return problems;
    }

    /**
     * The driver settings for this connection.
     *
     * @return settings ready to hand to {@code MongoClients.create}
     */
    MongoClientSettings clientSettings() {
        MongoClientSettings.Builder settings = MongoClientSettings.builder()
                .applicationName(applicationName)
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
                        .readTimeout(socketTimeoutMillis, TimeUnit.MILLISECONDS))
                .applyToClusterSettings(cluster -> cluster
                        .serverSelectionTimeout(serverSelectionTimeoutMillis, TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(pool -> pool
                        .maxSize(maxPoolSize)
                        .minSize(minPoolSize)
                        // An idle connection to somebody else's database is a socket held open for no reason
                        // between executions that may be hours apart.
                        .maxConnectionIdleTime(5, TimeUnit.MINUTES));

        if (!uri.isBlank()) {
            // The URI supplies hosts, replica set, TLS and any options written into it; the fields below only
            // add what a URI cannot carry safely, which is the credentials.
            settings.applyConnectionString(new ConnectionString(uri));
        } else {
            settings.applyToClusterSettings(cluster -> {
                cluster.hosts(List.of(new ServerAddress(host, port)));
                if (!replicaSet.isBlank()) {
                    cluster.requiredReplicaSetName(replicaSet);
                }
            });
        }

        if (tls) {
            settings.applyToSslSettings(ssl -> ssl
                    .enabled(true)
                    // Only when explicitly asked for. Off, a certificate is checked against the host it was
                    // presented by; on, an encrypted connection to anything able to answer for that address is
                    // accepted, which is worth doing only against a deployment with a self-signed certificate
                    // the operator has decided to trust.
                    .invalidHostNameAllowed(tlsAllowInvalidHostnames));
        }

        if (!username.isBlank()) {
            String authSource = authenticationDatabase.isBlank() ? database() : authenticationDatabase;
            settings.credential(MongoCredential.createCredential(
                    username, authSource.isBlank() ? "admin" : authSource, password.toCharArray()));
        }

        return settings.build();
    }

    /**
     * The database this node works in.
     *
     * @return the configured database, or the one named in the connection string
     */
    String database() {
        if (!database.isBlank()) {
            return database;
        }
        if (!uri.isBlank()) {
            try {
                return Optional.ofNullable(new ConnectionString(uri).getDatabase()).orElse("");
            } catch (IllegalArgumentException ex) {
                return "";
            }
        }
        return "";
    }

    /**
     * A stable key for the client cache.
     *
     * <p>Every setting that changes the connection is in it, so a rotated password or a changed pool size
     * produces a different client rather than silently reusing one built with the old value. The password is
     * hashed rather than included: this key is held in a map for the life of the plugin and appears in debug
     * logging, and neither is a place for a database password.
     *
     * @return an opaque key
     */
    String cacheKey() {
        String material = String.join(" ",
                uri, host, String.valueOf(port), database(), username, authenticationDatabase, replicaSet,
                String.valueOf(tls), String.valueOf(tlsAllowInvalidHostnames),
                String.valueOf(connectTimeoutMillis), String.valueOf(socketTimeoutMillis),
                String.valueOf(serverSelectionTimeoutMillis),
                String.valueOf(maxPoolSize), String.valueOf(minPoolSize),
                digest(password));
        return digest(material);
    }

    private static String digest(String value) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the platform; this cannot happen.
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    Duration socketTimeout() {
        return Duration.ofMillis(socketTimeoutMillis);
    }

    boolean authenticated() {
        return !username.isBlank();
    }

    /**
     * A description safe to log: where it connects, never how it authenticates.
     *
     * <p>No username, no password, and no URI — a connection string frequently carries an Atlas cluster name
     * and the account it belongs to, which is not something to write into every execution record.
     */
    @Override
    public String toString() {
        String where = uri.isBlank() ? host + ":" + port : redactedUri();
        return "mongodb " + where + "/" + database() + (tls ? " tls" : "")
                + (authenticated() ? " authenticated" : "");
    }

    /** The URI's scheme and host, with any query string dropped. */
    private String redactedUri() {
        int query = uri.indexOf('?');
        String withoutQuery = query < 0 ? uri : uri.substring(0, query);
        int lastSlash = withoutQuery.lastIndexOf('/');
        return lastSlash > "mongodb+srv://".length() ? withoutQuery.substring(0, lastSlash) : withoutQuery;
    }

    /** Mutable while reading a configuration, immutable afterwards. */
    private static final class Builder {
        private String uri = "";
        private String host = "";
        private int port = 27017;
        private String database = "";
        private String username = "";
        private String password = "";
        private String authenticationDatabase = "";
        private String replicaSet = "";
        private boolean tls;
        private boolean tlsAllowInvalidHostnames;
        private int connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS;
        private int socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS;
        private int serverSelectionTimeoutMillis = DEFAULT_SERVER_SELECTION_TIMEOUT_MS;
        private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;
        private int minPoolSize = DEFAULT_MIN_POOL_SIZE;
        private String applicationName = "workflow-engine";
    }
}
