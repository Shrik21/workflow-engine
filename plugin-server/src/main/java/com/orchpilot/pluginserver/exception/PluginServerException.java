package com.orchpilot.pluginserver.exception;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * A registry failure that knows how it should be reported.
 *
 * <p>One exception type carrying its own status and code, rather than a family of classes with a handler method
 * each. The registry's failures differ only in status, code and wording, and a hierarchy would add a file per
 * message without adding a distinction anything branches on.
 *
 * <p>Every message here is written to be read by the person who made the request. The static factories are the
 * whole vocabulary of things that can go wrong, which makes the set easy to review.
 */
public class PluginServerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    protected PluginServerException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<String> getDetails() {
        return details;
    }

    // ------------------------------------------------------------------ not found

    public static PluginServerException pluginNotFound(String pluginId) {
        return new PluginServerException(HttpStatus.NOT_FOUND, "PLUGIN_NOT_FOUND",
                "No plugin with id '" + pluginId + "'.", List.of());
    }

    public static PluginServerException versionNotFound(String pluginId, String version) {
        return new PluginServerException(HttpStatus.NOT_FOUND, "PLUGIN_VERSION_NOT_FOUND",
                "Plugin '" + pluginId + "' has no version '" + version + "'.", List.of());
    }

    /**
     * The metadata exists but the bytes do not.
     *
     * <p>410 rather than 404, because the difference matters to the client: a missing version means the client
     * asked for the wrong thing, while a missing archive means this registry has lost something it promised to
     * keep, and only one of those is worth paging somebody about.
     */
    public static PluginServerException archiveMissing(String pluginId, String version) {
        return new PluginServerException(HttpStatus.GONE, "PLUGIN_ARCHIVE_MISSING",
                "The record for '" + pluginId + ":" + version + "' exists but its archive is not in storage. "
                        + "The version needs to be uploaded again.", List.of());
    }

    // ------------------------------------------------------------------- conflict

    public static PluginServerException versionAlreadyExists(String pluginId, String version) {
        return new PluginServerException(HttpStatus.CONFLICT, "PLUGIN_VERSION_ALREADY_EXISTS",
                "Plugin '" + pluginId + "' version " + version + " already exists. Published versions are "
                        + "immutable: publish a new version rather than replacing this one.", List.of());
    }

    public static PluginServerException illegalTransition(String coordinate, String from, String to) {
        return new PluginServerException(HttpStatus.CONFLICT, "PLUGIN_ILLEGAL_TRANSITION",
                "'" + coordinate + "' is " + from + " and cannot become " + to + ".", List.of());
    }

    /**
     * A revoked version may not be distributed.
     *
     * <p>409 rather than 403: the caller is entitled to download, and this particular version has been
     * withdrawn. Answering "forbidden" would send them looking at their permissions.
     */
    public static PluginServerException revoked(String pluginId, String version, String reason) {
        return new PluginServerException(HttpStatus.CONFLICT, "PLUGIN_REVOKED",
                "Plugin '" + pluginId + ":" + version + "' has been revoked and can no longer be downloaded."
                        + (reason == null || reason.isBlank() ? "" : " Reason: " + reason), List.of());
    }

    // ----------------------------------------------------------------- validation

    /**
     * The archive was rejected. 422, because the request was well formed and the artefact was not.
     *
     * @param problems every problem found, not the first
     */
    public static PluginServerException invalidArchive(String message, List<String> problems) {
        return new PluginServerException(HttpStatus.UNPROCESSABLE_ENTITY, "PLUGIN_ARCHIVE_INVALID",
                message, problems);
    }

    public static PluginServerException invalidManifest(List<String> problems) {
        return new PluginServerException(HttpStatus.UNPROCESSABLE_ENTITY, "PLUGIN_MANIFEST_INVALID",
                problems.size() == 1
                        ? "The plugin manifest has one problem."
                        : "The plugin manifest has " + problems.size() + " problems.",
                problems);
    }

    public static PluginServerException archiveTooLarge(long sizeBytes, long limitBytes) {
        return new PluginServerException(HttpStatus.PAYLOAD_TOO_LARGE, "PLUGIN_ARCHIVE_TOO_LARGE",
                "The archive is " + sizeBytes + " bytes, above the configured limit of " + limitBytes
                        + " bytes.", List.of());
    }

    public static PluginServerException badRequest(String code, String message) {
        return new PluginServerException(HttpStatus.BAD_REQUEST, code, message, List.of());
    }

    /** A named thing that does not exist: a user, a role, a session. */
    public static PluginServerException notFound(String code, String message) {
        return new PluginServerException(HttpStatus.NOT_FOUND, code, message, List.of());
    }

    /** A request that cannot be satisfied because of the current state, such as a name already taken. */
    public static PluginServerException conflict(String code, String message) {
        return new PluginServerException(HttpStatus.CONFLICT, code, message, List.of());
    }

    /**
     * A request that is well-formed but breaks a rule, with each broken rule listed.
     *
     * <p>Every violation at once: a password checker that stopped at the first problem would make somebody
     * submit four times to learn four rules.
     */
    public static PluginServerException invalid(String code, String message, List<String> problems) {
        return new PluginServerException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, problems);
    }

    // ------------------------------------------------------------ authentication

    /**
     * Bad credentials. One message for every cause, so a caller with a list of guesses learns nothing about
     * which client ids are real.
     */
    public static PluginServerException invalidClient() {
        return new PluginServerException(HttpStatus.UNAUTHORIZED, "INVALID_CLIENT",
                "The client id or secret is not valid.", List.of());
    }

    /**
     * A request that failed because of who is making it, rather than what it contains.
     *
     * <p>401 rather than 400 for a refused sign-in, because the two mean different things to a client: 400 says
     * "the request was malformed", which sends a console looking for a field to correct and, in ours, produced
     * "the service may be unreachable" for somebody who had simply mistyped their password.
     */
    public static PluginServerException unauthorized(String code, String message) {
        return new PluginServerException(HttpStatus.UNAUTHORIZED, code, message, List.of());
    }

    public static PluginServerException forbidden(String code, String message) {
        return new PluginServerException(HttpStatus.FORBIDDEN, code, message, List.of());
    }

    /**
     * Something this deployment is configured not to be able to do.
     *
     * <p>501 rather than 500: the request is understood and the capability is absent by choice, which is a
     * different thing from a fault and points the caller at configuration rather than at a bug.
     */
    public static PluginServerException notImplemented(String code, String message) {
        return new PluginServerException(HttpStatus.NOT_IMPLEMENTED, code, message, List.of());
    }
}
