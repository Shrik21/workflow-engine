package com.orchpilot.workflow.plugins.gcp.kubernetes.client;

import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.json.Json;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * A parsed Google service-account key — the credential the GKE provider authenticates with.
 *
 * <h2>Never leaves this object</h2>
 *
 * The raw key JSON comes from the OrchPilot secret store, never from workflow configuration. It is reduced
 * immediately to the four things the token exchange needs, and the RSA key is held as a {@link PrivateKey} that is
 * never rendered back to text. {@link #toString()} is deliberately opaque so an accidental string concatenation in
 * a log statement cannot leak key material.
 */
public final class GoogleCredentials {

    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

    private final String clientEmail;
    private final String tokenUri;
    private final String projectId;
    private final PrivateKey privateKey;

    private GoogleCredentials(String clientEmail, String tokenUri, String projectId, PrivateKey privateKey) {
        this.clientEmail = clientEmail;
        this.tokenUri = tokenUri;
        this.projectId = projectId;
        this.privateKey = privateKey;
    }

    /**
     * @param serviceAccountJson the full service-account key file contents, as held in a secret
     * @throws PluginConfigurationException when the JSON is not a usable service-account key
     */
    public static GoogleCredentials fromServiceAccountJson(String serviceAccountJson) {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            throw new PluginConfigurationException("The GCP credentials secret is empty.");
        }
        Map<String, Object> json;
        try {
            json = Json.parseObject(serviceAccountJson);
        } catch (RuntimeException ex) {
            throw new PluginConfigurationException(
                    "The GCP credentials secret is not valid service-account JSON.");
        }
        String type = string(json, "type");
        if (type != null && !"service_account".equals(type)) {
            throw new PluginConfigurationException(
                    "The GCP credentials secret is not a service_account key (type=" + type + ").");
        }
        String tokenUri = string(json, "token_uri");
        return new GoogleCredentials(require(json, "client_email"),
                tokenUri == null || tokenUri.isBlank() ? DEFAULT_TOKEN_URI : tokenUri,
                string(json, "project_id"),
                parsePrivateKey(require(json, "private_key")));
    }

    public String clientEmail() {
        return clientEmail;
    }

    public String tokenUri() {
        return tokenUri;
    }

    /** @return the project from the key file, used only when the node did not configure one */
    public String projectId() {
        return projectId;
    }

    PrivateKey privateKey() {
        return privateKey;
    }

    private static PrivateKey parsePrivateKey(String pem) {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (RuntimeException | java.security.GeneralSecurityException ex) {
            // Deliberately says nothing about the key itself.
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
        return "GoogleCredentials{serviceAccount}";
    }
}
