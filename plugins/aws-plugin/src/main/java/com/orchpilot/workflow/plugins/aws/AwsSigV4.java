package com.orchpilot.workflow.plugins.aws;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class AwsSigV4 {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private final Clock clock;
    AwsSigV4() { this(Clock.systemUTC()); }
    AwsSigV4(Clock clock) { this.clock = clock; }

    HttpRequestSpec sign(String method, String uriText, String body, String contentType, String service,
                         String region, AwsCredentials credentials, long timeoutMillis) {
        URI uri = URI.create(uriText); Instant now = clock.instant();
        String stamp = STAMP.format(now), day = DAY.format(now), payloadHash = sha256(body == null ? "" : body);
        String canonicalHeaders = "content-type:" + contentType + "\nhost:" + uri.getHost() + "\nx-amz-date:" + stamp + "\n";
        String signedHeaders = "content-type;host;x-amz-date";
        if (credentials.sessionToken() != null) {
            canonicalHeaders += "x-amz-security-token:" + credentials.sessionToken() + "\n";
            signedHeaders += ";x-amz-security-token";
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        String canonical = method + "\n" + path + "\n" + query + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = day + "/" + region + "/" + service + "/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope + "\n" + sha256(canonical);
        byte[] key = hmac(("AWS4" + credentials.secretAccessKey()).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, region); key = hmac(key, service); key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, toSign));
        String auth = "AWS4-HMAC-SHA256 Credential=" + credentials.accessKeyId() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        HttpRequestSpec.Builder request = HttpRequestSpec.builder(method, uriText).body(body)
                .header("Content-Type", contentType).header("X-Amz-Date", stamp).header("Authorization", auth)
                .timeoutMillis(timeoutMillis);
        if (credentials.sessionToken() != null) request.header("X-Amz-Security-Token", credentials.sessionToken());
        return request.build();
    }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static byte[] hmac(byte[] key, String value) { try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException(e); } }
}
