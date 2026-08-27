package com.orchpilot.workflow.plugins.mongodb;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoNotPrimaryException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoServerUnavailableException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;

import java.util.Locale;

/**
 * Turns a driver failure into a code, a sentence, and a decision about retrying.
 *
 * <h2>What is worth retrying</h2>
 *
 * A failure that might succeed unchanged a moment later: a timeout, a socket that dropped, a replica set
 * mid-election. Nothing else. Retrying a duplicate key gives a second duplicate key; retrying an
 * authentication failure is another failed sign-in against the database; retrying a malformed query is the
 * same malformed query. The driver already retries reads and writes internally at the network level, so a
 * failure that reaches here has usually exhausted that.
 *
 * <h2>Server codes decide where they exist</h2>
 *
 * MongoDB numbers its errors, and the numbers are more reliable than the message text they come wrapped in.
 * Code 11000 is a duplicate key whatever the server says about it, and codes labelled {@code RetryableWriteError}
 * by the server say retryability outright.
 */
final class MongoErrors {

    // Configuration and validation
    static final String CONFIGURATION_INVALID = "MONGO_CONFIGURATION_INVALID";
    static final String VALIDATION_FAILED = "MONGO_VALIDATION_ERROR";
    static final String CONFIRMATION_REQUIRED = "MONGO_CONFIRMATION_REQUIRED";
    static final String PERMISSION_DENIED = "MONGO_PERMISSION_DENIED";
    static final String RESULT_TOO_LARGE = "MONGO_RESULT_TOO_LARGE";

    // Connection and authentication
    static final String CONNECTION_FAILED = "MONGO_CONNECTION_ERROR";
    static final String AUTHENTICATION_FAILED = "MONGO_AUTHENTICATION_ERROR";
    static final String TLS_FAILED = "MONGO_TLS_ERROR";
    static final String TIMEOUT = "MONGO_TIMEOUT";

    // Query and write
    static final String QUERY_FAILED = "MONGO_QUERY_ERROR";
    static final String WRITE_FAILED = "MONGO_WRITE_ERROR";
    static final String DUPLICATE_KEY = "MONGO_DUPLICATE_KEY";
    static final String NAMESPACE_NOT_FOUND = "MONGO_NAMESPACE_NOT_FOUND";
    static final String NOT_SUPPORTED = "MONGO_NOT_SUPPORTED";
    static final String TRANSACTION_FAILED = "MONGO_TRANSACTION_ERROR";

    /** Duplicate key, in every MongoDB version that has existed. */
    private static final int DUPLICATE_KEY_CODE = 11_000;
    private static final int DUPLICATE_KEY_CODE_LEGACY = 11_001;

    /** The command was fine; the deployment does not support it. Standalone servers and transactions. */
    private static final int ILLEGAL_OPERATION = 20;
    private static final int NAMESPACE_NOT_FOUND_CODE = 26;
    private static final int NAMESPACE_EXISTS = 48;
    private static final int UNAUTHORIZED = 13;
    private static final int AUTHENTICATION_FAILED_CODE = 18;
    private static final int MAX_TIME_MS_EXPIRED = 50;

    private MongoErrors() {
    }

    /**
     * What went wrong, and whether trying again could help.
     *
     * @param code      the structured code, published as {@code result.errorCode}
     * @param message   a sentence for whoever reads the execution record
     * @param retryable whether the engine should apply its backoff and try again
     */
    record Classification(String code, String message, boolean retryable) {
    }

    /**
     * Classifies a failure.
     *
     * @param failure    what was thrown
     * @param connection the connection, described without credentials, because "connection refused" with no
     *                   idea which server refused it is unactionable
     * @return the classification
     */
    static Classification classify(Exception failure, MongoConnectionSettings connection) {
        String where = connection == null ? "the database" : connection.toString();

        if (failure instanceof MongoSecurityException) {
            // Never retried: repeating a rejected credential is another failed authentication against a
            // database that may well be counting them.
            return new Classification(AUTHENTICATION_FAILED,
                    "Authentication against " + where + " failed. Check the username, the password secret, "
                            + "and the authentication database.", false);
        }
        if (failure instanceof MongoExecutionTimeoutException) {
            // The server stopped the operation at maxTimeMS. Trying again unchanged runs the same slow query.
            return new Classification(TIMEOUT,
                    "The operation exceeded its time limit on the server. Narrow the filter, add an index, or "
                            + "raise 'maxTimeMillis'.", false);
        }
        if (failure instanceof MongoSocketReadTimeoutException) {
            return new Classification(TIMEOUT, "Timed out waiting for " + where + " to answer.", true);
        }
        if (failure instanceof MongoTimeoutException) {
            // Server selection: no suitable server within the window. A replica set failing over looks like
            // this and recovers on its own.
            return new Classification(CONNECTION_FAILED,
                    "No server was available at " + where + " within the selection timeout. The deployment "
                            + "may be down, mid-election, or unreachable from this engine.", true);
        }
        if (failure instanceof MongoSocketException socket) {
            return socketClassification(socket, where);
        }
        if (failure instanceof MongoServerUnavailableException || failure instanceof MongoNotPrimaryException) {
            return new Classification(CONNECTION_FAILED,
                    "The server at " + where + " is not currently able to serve this operation. A replica set "
                            + "election looks like this and resolves itself.", true);
        }
        if (failure instanceof MongoBulkWriteException bulk) {
            return bulkClassification(bulk);
        }
        if (failure instanceof MongoWriteException write) {
            return codeClassification(write.getError().getCode(), safe(write), WRITE_FAILED);
        }
        if (failure instanceof MongoCommandException command) {
            return codeClassification(command.getErrorCode(), safe(command), QUERY_FAILED);
        }
        if (failure instanceof IllegalArgumentException) {
            // The driver rejects a malformed query shape before sending it.
            return new Classification(VALIDATION_FAILED, safe(failure), false);
        }
        return new Classification(QUERY_FAILED, "The operation failed: " + safe(failure), false);
    }

    private static Classification socketClassification(MongoSocketException failure, String where) {
        String text = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("ssl") || text.contains("tls") || text.contains("certificate")
                || text.contains("handshake")) {
            return new Classification(TLS_FAILED,
                    "The encrypted connection to " + where + " could not be established. Check whether the "
                            + "deployment requires TLS, and whether its certificate is trusted by this engine's "
                            + "JVM.", false);
        }
        return new Classification(CONNECTION_FAILED,
                "The connection to " + where + " failed: " + safe(failure), true);
    }

    private static Classification bulkClassification(MongoBulkWriteException failure) {
        boolean duplicate = failure.getWriteErrors().stream()
                .anyMatch(error -> error.getCode() == DUPLICATE_KEY_CODE
                        || error.getCode() == DUPLICATE_KEY_CODE_LEGACY);
        int failed = failure.getWriteErrors().size();

        if (duplicate) {
            return new Classification(DUPLICATE_KEY,
                    failed + " of the writes were refused as duplicates of documents that already exist. "
                            + "Nothing was retried, because a second attempt produces the same duplicates.",
                    false);
        }
        return new Classification(WRITE_FAILED,
                failed + " of the writes failed. Earlier writes in the batch may have been applied: a bulk "
                        + "write is not a transaction unless it runs inside one.", false);
    }

    private static Classification codeClassification(int code, String message, String fallback) {
        return switch (code) {
            case DUPLICATE_KEY_CODE, DUPLICATE_KEY_CODE_LEGACY -> new Classification(DUPLICATE_KEY,
                    "A document with that key already exists. " + message, false);
            case NAMESPACE_NOT_FOUND_CODE -> new Classification(NAMESPACE_NOT_FOUND,
                    "The collection does not exist. " + message, false);
            case NAMESPACE_EXISTS -> new Classification(WRITE_FAILED,
                    "The collection already exists. " + message, false);
            case UNAUTHORIZED, AUTHENTICATION_FAILED_CODE -> new Classification(AUTHENTICATION_FAILED,
                    "The database refused the operation for this user: " + message
                            + " This is the database's own authorization, not the workflow platform's.", false);
            case MAX_TIME_MS_EXPIRED -> new Classification(TIMEOUT,
                    "The operation exceeded its time limit on the server. " + message, false);
            case ILLEGAL_OPERATION -> new Classification(NOT_SUPPORTED,
                    "This deployment does not support that operation: " + message
                            + " Transactions, for instance, need a replica set or a sharded cluster; a "
                            + "standalone server refuses them with this error.", false);
            default -> new Classification(fallback, message, false);
        };
    }

    /**
     * The exception's message, never its stack, and never a connection string.
     *
     * <p>The driver includes the server address in most messages, which is wanted, and occasionally includes
     * the whole connection string it was built from, which is not: on a deployment configured by URI that
     * string is where the password would be if one had been written into it. Anything shaped like credentials
     * in an authority is removed.
     */
    private static String safe(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String redacted = message.replaceAll("(mongodb(\\+srv)?://)[^@/\\s]+@", "$1<credentials removed>@");
        return redacted.length() > 400 ? redacted.substring(0, 400) + "…" : redacted;
    }
}
