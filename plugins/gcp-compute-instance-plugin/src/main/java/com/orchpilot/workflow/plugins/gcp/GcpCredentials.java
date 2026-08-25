package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.json.Json;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * A parsed Google service-account key, the credential this plugin authenticates with.
 *
 * <h2>Held only as long as one call needs it</h2>
 *
 * The raw service-account JSON comes from the OrchPilot secret store, never from workflow configuration, and is
 * turned into exactly what the JWT-bearer flow needs: the client email, the token endpoint, the project, and the
 * RSA {@link PrivateKey}. The key material is never rendered to a string — {@link #toString()} is deliberately
 * opaque — so it cannot slip into a log, an output, or the model. Instances are short-lived and discarded after
 * an access token is minted.
 */
public final class GcpCredentials {

    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

    private final String clientEmail;
    private final String tokenUri;
    private final String projectId;
    private final PrivateKey privateKey;

    private GcpCredentials(String clientEmail, String tokenUri, String projectId, PrivateKey privateKey) {
        this.clientEmail = clientEmail;
        this.tokenUri = tokenUri;
        this.projectId = projectId;
        this.privateKey = privateKey;
    }

    /**
     * Parses a service-account JSON key.
     *
     * @param serviceAccountJson the full key file contents, as stored in a secret
     * @return the parsed credential
     * @throws PluginConfigurationException when the JSON is missing required fields or the key cannot be read
     */
    public static GcpCredentials fromServiceAccountJson(String serviceAccountJson) {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            throw new PluginConfigurationException("The GCP credentials secret is empty.");
        }
        Map<String, Object> json;
        try {
            json = Json.parseObject(serviceAccountJson);
        } catch (RuntimeException ex) {
            throw new PluginConfigurationException("The GCP credentials secret is not valid service-account JSON.");
        }
        String type = string(json, "type");
        if (type != null && !"service_account".equals(type)) {
            throw new PluginConfigurationException(
                    "The GCP credentials secret is not a service_account key (type=" + type + ").");
        }
        String clientEmail = require(json, "client_email");
        String pem = require(json, "private_key");
        String tokenUri = string(json, "token_uri");
        String projectId = string(json, "project_id");
        return new GcpCredentials(clientEmail,
                tokenUri == null || tokenUri.isBlank() ? DEFAULT_TOKEN_URI : tokenUri,
                projectId, parsePrivateKey(pem));
    }

    public String clientEmail() {
        return clientEmail;
    }

    public String tokenUri() {
        return tokenUri;
    }

    /** @return the project id from the key, or null; the node's configured project takes precedence over this */
    public String projectId() {
        return projectId;
    }

    PrivateKey privateKey() {
        return privateKey;
    }

    /** Parses a PKCS#8 PEM private key (the shape a GCP service-account key always uses). */
    private static PrivateKey parsePrivateKey(String pem) {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (RuntimeException | java.security.GeneralSecurityException ex) {
            // No key material in the message — only that it could not be read.
            throw new PluginConfigurationException("The GCP service-account private key could not be parsed.");
        }
    }

    private static String require(Map<String, Object> json, String field) {
        String value = string(json, field);
        if (value == null || value.isBlank()) {
            throw new PluginConfigurationException(
                    "The GCP credentials secret is missing the '" + field + "' field.");
        }
        return value;
    }

    private static String string(Map<String, Object> json, String field) {
        Object value = json.get(field);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public String toString() {
        // Never expose the key material or client email in logs.
        return "GcpCredentials{serviceAccount}";
    }
}
