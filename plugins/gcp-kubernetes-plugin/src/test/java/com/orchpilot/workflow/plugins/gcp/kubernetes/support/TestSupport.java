package com.orchpilot.workflow.plugins.gcp.kubernetes.support;

import com.orchpilot.workflow.sdk.json.Json;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Shared fixtures: a real service-account key, and a {@link NodeConfiguration} over a plain map. */
public final class TestSupport {

    /** The private key material that must never appear in output, logs or traffic. */
    public static final String SECRET_MARKER_EMAIL = "k8s-bot@test.iam.gserviceaccount.com";

    private TestSupport() {
    }

    /**
     * Builds a genuine service-account JSON key — a freshly generated RSA key in the exact shape Google issues.
     *
     * <p>Real crypto rather than a stub, so credential parsing and RS256 JWT signing are actually exercised.
     */
    public static String serviceAccountJson(String tokenUri) {
        try {
            var keyPair = KeyPairGenerator.getInstance("RSA").genKeyPair();
            String der = Base64.getMimeEncoder(64, "\n".getBytes())
                    .encodeToString(keyPair.getPrivate().getEncoded());

            Map<String, Object> account = new LinkedHashMap<>();
            account.put("type", "service_account");
            account.put("project_id", "test-project");
            account.put("private_key_id", "key-1");
            account.put("private_key", "-----BEGIN PRIVATE KEY-----\n" + der + "\n-----END PRIVATE KEY-----\n");
            account.put("client_email", SECRET_MARKER_EMAIL);
            account.put("token_uri", tokenUri);
            return Json.write(account);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build a test service account", ex);
        }
    }

    public static NodeConfiguration config(Map<String, Object> values) {
        return new MapConfiguration(values);
    }

    /** A minimal {@link NodeConfiguration} over a map; the engine normally supplies a resolved one. */
    private record MapConfiguration(Map<String, Object> values) implements NodeConfiguration {

        @Override
        @SuppressWarnings("unchecked")
        public Optional<Object> find(String key) {
            if (key == null) {
                return Optional.empty();
            }
            Object current = values;
            for (String part : key.split("\\.")) {
                if (!(current instanceof Map<?, ?> map)) {
                    return Optional.empty();
                }
                current = ((Map<String, Object>) map).get(part);
            }
            return Optional.ofNullable(current);
        }

        @Override
        public Map<String, Object> asMap() {
            return Collections.unmodifiableMap(values);
        }
    }
}
