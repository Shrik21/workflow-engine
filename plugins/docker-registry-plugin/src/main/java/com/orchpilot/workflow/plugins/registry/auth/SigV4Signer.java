package com.orchpilot.workflow.plugins.registry.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A minimal AWS Signature Version 4 signer for a single JSON POST — enough to call the ECR API without the AWS
 * SDK, which cannot be added to this offline build.
 *
 * <p>Deliberately small: it signs one request (canonical request, string to sign, derived signing key,
 * {@code Authorization} header) and returns the headers to attach. It is not a general AWS client.
 */
public final class SigV4Signer {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private SigV4Signer() {
    }

    /**
     * @return the signed headers to attach to the POST, including the AWS target and content type
     */
    public static Map<String, String> sign(String host, String path, String region, String service,
                                           String amzTarget, byte[] body, String accessKey, String secretKey,
                                           String sessionToken) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE.format(now);
        String payloadHash = hex(sha256(body));
        String contentType = "application/x-amz-json-1.1";

        Map<String, String> canonicalHeaders = new TreeMap<>();
        canonicalHeaders.put("content-type", contentType);
        canonicalHeaders.put("host", host);
        canonicalHeaders.put("x-amz-date", amzDate);
        canonicalHeaders.put("x-amz-target", amzTarget);
        if (sessionToken != null && !sessionToken.isBlank()) {
            canonicalHeaders.put("x-amz-security-token", sessionToken);
        }

        StringBuilder headersBlock = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        canonicalHeaders.forEach((name, value) -> {
            headersBlock.append(name).append(':').append(value).append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(name);
        });

        String canonicalRequest = "POST\n" + path + "\n\n" + headersBlock + "\n" + signedHeaders + "\n"
                + payloadHash;
        String scope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = signatureKey(secretKey, dateStamp, region, service);
        String signature = hex(hmac(signingKey, stringToSign));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("X-Amz-Date", amzDate);
        headers.put("X-Amz-Target", amzTarget);
        headers.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        if (sessionToken != null && !sessionToken.isBlank()) {
            headers.put("X-Amz-Security-Token", sessionToken);
        }
        return headers;
    }

    private static byte[] signatureKey(String secret, String dateStamp, String region, String service) {
        byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, "aws4_request");
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 failed", ex);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
