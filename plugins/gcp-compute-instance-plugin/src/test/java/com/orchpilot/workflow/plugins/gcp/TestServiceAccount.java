package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.json.Json;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a genuine service-account JSON key for tests — a freshly generated RSA key, in the exact shape Google
 * issues — so the credential parsing and JWT signing exercise real crypto without any real Google account.
 */
final class TestServiceAccount {

    private TestServiceAccount() {
    }

    static String json(String clientEmail, String tokenUri) {
        try {
            var keyPair = KeyPairGenerator.getInstance("RSA").genKeyPair();
            String der = Base64.getMimeEncoder(64, "\n".getBytes())
                    .encodeToString(keyPair.getPrivate().getEncoded());
            String pem = "-----BEGIN PRIVATE KEY-----\n" + der + "\n-----END PRIVATE KEY-----\n";

            Map<String, Object> sa = new LinkedHashMap<>();
            sa.put("type", "service_account");
            sa.put("project_id", "test-project");
            sa.put("private_key_id", "key-1");
            sa.put("private_key", pem);
            sa.put("client_email", clientEmail);
            sa.put("token_uri", tokenUri);
            return Json.write(sa);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build a test service account", ex);
        }
    }
}
