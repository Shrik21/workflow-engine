package com.orchpilot.workflow.plugins.vpn.provider.aws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signing crypto, against published vectors.
 *
 * <p>A hand-written signer is only trustworthy if its primitives are checked against known-good values, so the
 * SHA-256 and HMAC-SHA256 underneath SigV4 are tested against the vectors everyone else's implementation is
 * tested against (FIPS 180 for SHA-256, RFC 4231 for HMAC). The signature assembly on top is then checked for
 * determinism and the exact structure AWS requires — the two ways a signer that computed correct HMACs could
 * still produce a request AWS rejects.
 */
class SigV4Test {

    @Test
    @DisplayName("SHA-256 matches the FIPS 180 vector for \"abc\"")
    void sha256Vector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SigV4.hex(SigV4.sha256("abc")));
    }

    @Test
    @DisplayName("SHA-256 of the empty string is the documented constant")
    void sha256Empty() {
        // The value AWS uses for an empty payload hash; a mistake here breaks every signed request.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SigV4.hex(SigV4.sha256("")));
    }

    @Test
    @DisplayName("HMAC-SHA256 matches RFC 4231 test case 2")
    void hmacVector() {
        // RFC 4231 case 2: key "Jefe", data "what do ya want for nothing?".
        byte[] mac = SigV4.hmac("Jefe".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "what do ya want for nothing?");
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", SigV4.hex(mac));
    }

    @Test
    @DisplayName("the signing key derivation is deterministic and depends on every input")
    void signingKeyDerivation() {
        SigV4 base = new SigV4("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null,
                "us-east-1", "ec2");

        String key1 = SigV4.hex(base.signingKey("20150830"));
        String key2 = SigV4.hex(base.signingKey("20150830"));
        assertEquals(key1, key2, "the same inputs must derive the same key");

        // A different date, region or service must derive a different key, or credentials would be reusable
        // across scopes they were never scoped to.
        assertNotEquals(key1, SigV4.hex(base.signingKey("20150831")));
        assertNotEquals(key1, SigV4.hex(new SigV4("AKIDEXAMPLE",
                "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null, "eu-west-1", "ec2").signingKey("20150830")));
        assertNotEquals(key1, SigV4.hex(new SigV4("AKIDEXAMPLE",
                "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null, "us-east-1", "iam").signingKey("20150830")));
    }

    @Test
    @DisplayName("a signed request carries the exact Authorization structure AWS requires, deterministically")
    void authorizationStructure() {
        SigV4 signer = new SigV4("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null,
                "ap-south-1", "ec2");
        ZonedDateTime when = ZonedDateTime.of(2026, 8, 17, 12, 0, 0, 0, ZoneOffset.UTC);

        SigV4.SignedHeaders a = signer.sign("POST", "ec2.ap-south-1.amazonaws.com", "/", "",
                "Action=DescribeVpnConnections&Version=2016-11-15", when);
        SigV4.SignedHeaders b = signer.sign("POST", "ec2.ap-south-1.amazonaws.com", "/", "",
                "Action=DescribeVpnConnections&Version=2016-11-15", when);

        String authorization = a.headers().get("Authorization");
        assertEquals(authorization, b.headers().get("Authorization"), "signing must be deterministic");

        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 "));
        assertTrue(authorization.contains("Credential=AKIDEXAMPLE/20260817/ap-south-1/ec2/aws4_request"));
        // The headers this signer signs, in the required sorted order.
        assertTrue(authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"),
                authorization);
        assertTrue(authorization.matches(".*Signature=[0-9a-f]{64}$"), authorization);

        // The request-time headers a caller must send are all present.
        assertEquals("20260817T120000Z", a.headers().get("x-amz-date"));
        assertTrue(a.headers().containsKey("x-amz-content-sha256"));
        assertEquals("ec2.ap-south-1.amazonaws.com", a.headers().get("host"));
    }

    @Test
    @DisplayName("a session token is signed in when present")
    void sessionToken() {
        SigV4 signer = new SigV4("AKIDEXAMPLE", "secret", "the-session-token", "ap-south-1", "ec2");
        SigV4.SignedHeaders signed = signer.sign("POST", "ec2.ap-south-1.amazonaws.com", "/", "", "body",
                ZonedDateTime.of(2026, 8, 17, 12, 0, 0, 0, ZoneOffset.UTC));

        assertEquals("the-session-token", signed.headers().get("x-amz-security-token"));
        assertTrue(signed.headers().get("Authorization")
                .contains("host;x-amz-content-sha256;x-amz-date;x-amz-security-token"));
    }
}
