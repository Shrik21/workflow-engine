package com.orchpilot.workflow.plugins.vpn.provider.aws;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4, for calling AWS with the JDK's own crypto and no SDK.
 *
 * <h2>Why by hand, and why that is safe</h2>
 *
 * The AWS SDK is the obvious way to sign a request and the wrong way to do it inside a plugin: it is tens of
 * megabytes, it opens its own connections outside the engine's allowlist, and it cannot be installed offline.
 * SigV4 itself is a precisely specified sequence of SHA-256 and HMAC steps — a canonical request, a string to
 * sign, a derived key — and AWS publishes worked examples for every step. That makes a hand-written signer
 * something that can be checked against a known-good vector rather than merely hoped correct, which
 * {@code SigV4Test} does.
 *
 * <p>This produces the {@code Authorization} header for a request already built by the caller. It signs the
 * body it is given; it does not construct the request.
 */
public final class SigV4 {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final String accessKeyId;
    private final String secretAccessKey;
    private final String sessionToken;
    private final String region;
    private final String service;

    /**
     * @param accessKeyId     the access key id
     * @param secretAccessKey the secret access key
     * @param sessionToken    a session token for temporary credentials, or null
     * @param region          the region, e.g. {@code ap-south-1}
     * @param service         the service, e.g. {@code ec2}
     */
    public SigV4(String accessKeyId, String secretAccessKey, String sessionToken, String region,
                 String service) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken;
        this.region = region;
        this.service = service;
    }

    /** The headers a signed request must carry, computed for one request. */
    public record SignedHeaders(Map<String, String> headers) {
    }

    /**
     * Signs a request and returns every header it must be sent with.
     *
     * @param method  HTTP method
     * @param host    the host, e.g. {@code ec2.ap-south-1.amazonaws.com}
     * @param path    the canonical path, e.g. {@code /}
     * @param query   the canonical query string, already sorted and encoded, or empty
     * @param body    the request body
     * @param now     the signing time, a parameter so a test can pin it
     * @return the headers, including Authorization, X-Amz-Date, Host and the content hash
     */
    public SignedHeaders sign(String method, String host, String path, String query, String body,
                              ZonedDateTime now) {
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String payloadHash = hex(sha256(body));

        // Canonical headers must be sorted by lower-cased name; a TreeMap does that for us.
        TreeMap<String, String> canonicalHeaders = new TreeMap<>();
        canonicalHeaders.put("host", host);
        canonicalHeaders.put("x-amz-content-sha256", payloadHash);
        canonicalHeaders.put("x-amz-date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) {
            canonicalHeaders.put("x-amz-security-token", sessionToken);
        }

        StringBuilder canonicalHeaderBlock = new StringBuilder();
        StringBuilder signedHeaderList = new StringBuilder();
        for (Map.Entry<String, String> header : canonicalHeaders.entrySet()) {
            canonicalHeaderBlock.append(header.getKey()).append(':').append(header.getValue().trim())
                    .append('\n');
            if (signedHeaderList.length() > 0) {
                signedHeaderList.append(';');
            }
            signedHeaderList.append(header.getKey());
        }
        String signedHeaders = signedHeaderList.toString();

        String canonicalRequest = method + "\n" + path + "\n" + (query == null ? "" : query) + "\n"
                + canonicalHeaderBlock + "\n" + signedHeaders + "\n" + payloadHash;

        String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + credentialScope + "\n"
                + hex(sha256(canonicalRequest));

        byte[] signingKey = signingKey(dateStamp);
        String signature = hex(hmac(signingKey, stringToSign));

        String authorization = "AWS4-HMAC-SHA256 "
                + "Credential=" + accessKeyId + "/" + credentialScope + ", "
                + "SignedHeaders=" + signedHeaders + ", "
                + "Signature=" + signature;

        TreeMap<String, String> headers = new TreeMap<>(canonicalHeaders);
        headers.put("Authorization", authorization);
        return new SignedHeaders(headers);
    }

    /** The SigV4 signing-key derivation: HMAC the date, region, service and terminator in turn. */
    byte[] signingKey(String dateStamp) {
        byte[] key = ("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8);
        byte[] dateKey = hmac(key, dateStamp);
        byte[] regionKey = hmac(dateKey, region);
        byte[] serviceKey = hmac(regionKey, service);
        return hmac(serviceKey, "aws4_request");
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
