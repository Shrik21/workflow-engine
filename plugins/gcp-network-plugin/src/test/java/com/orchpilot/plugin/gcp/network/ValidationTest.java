package com.orchpilot.plugin.gcp.network;

import com.orchpilot.plugin.gcp.network.exception.GcpNetworkException;
import com.orchpilot.plugin.gcp.network.validation.CidrValidator;
import com.orchpilot.plugin.gcp.network.validation.FirewallExposure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CIDR validation and firewall exposure assessment.
 *
 * <p>Both run before anything reaches GCP, so these are the checks that decide whether an author gets a
 * precise message locally or a generic {@code INVALID_ARGUMENT} after a round trip.
 */
class ValidationTest {

    // ------------------------------------------------------------------ CIDR

    @ParameterizedTest
    @ValueSource(strings = {"10.10.0.0/24", "192.168.1.0/28", "172.16.0.0/12", "10.0.0.0/8"})
    @DisplayName("accepts well-formed subnet ranges")
    void acceptsValidSubnetRanges(String cidr) {
        CidrValidator.requireSubnetRange(cidr, "ipCidrRange");
    }

    @Test
    @DisplayName("rejects a range with host bits set, and says what was probably meant")
    void rejectsHostBits() {
        // The classic mistake: it looks like a host address, and GCP silently treats it as the whole network.
        assertThatThrownBy(() -> CidrValidator.requireSubnetRange("10.0.0.5/24", "ipCidrRange"))
                .isInstanceOf(GcpNetworkException.class)
                .hasMessageContaining("host bits")
                .hasMessageContaining("10.0.0.0/24");
    }

    @Test
    @DisplayName("rejects a subnet smaller than /29, which GCP cannot allocate")
    void rejectsTinySubnet() {
        assertThatThrownBy(() -> CidrValidator.requireSubnetRange("10.0.0.0/30", "ipCidrRange"))
                .isInstanceOf(GcpNetworkException.class)
                .hasMessageContaining("/29");
    }

    @Test
    @DisplayName("rejects an implausibly large subnet prefix as a likely typo")
    void rejectsHugeSubnet() {
        assertThatThrownBy(() -> CidrValidator.requireSubnetRange("10.0.0.0/4", "ipCidrRange"))
                .isInstanceOf(GcpNetworkException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0", "10.0.0.0/33", "10.0.0.300/24", "not-a-cidr", "10.0.0/24",
            "10.0.0.0/abc"})
    @DisplayName("rejects malformed ranges")
    void rejectsMalformed(String cidr) {
        assertThatThrownBy(() -> CidrValidator.requireSubnetRange(cidr, "ipCidrRange"))
                .isInstanceOf(GcpNetworkException.class)
                .satisfies(ex -> assertThat(((GcpNetworkException) ex).errorCode())
                        .isEqualTo("GCP_INVALID_CIDR"));
    }

    @Test
    @DisplayName("a match range allows what a subnet range does not")
    void matchRangesAreLooser() {
        // Legitimate as a firewall source or a route destination, and correctly refused as a subnet.
        CidrValidator.requireMatchRange("0.0.0.0/0", "sourceRanges");
        CidrValidator.requireMatchRange("10.0.0.1/32", "sourceRanges");

        assertThatThrownBy(() -> CidrValidator.requireSubnetRange("0.0.0.0/0", "ipCidrRange"))
                .isInstanceOf(GcpNetworkException.class);
    }

    @Test
    @DisplayName("names the field, so the message points at the right control")
    void namesTheField() {
        assertThatThrownBy(() -> CidrValidator.requireMatchRange("nope", "destRange"))
                .hasMessageContaining("destRange");
    }

    @Test
    @DisplayName("IPv6 is passed to GCP rather than half-validated here")
    void ipv6IsDeferred() {
        CidrValidator.requireMatchRange("2001:db8::/32", "sourceRanges");

        assertThatThrownBy(() -> CidrValidator.requireMatchRange("2001:db8::", "sourceRanges"))
                .isInstanceOf(GcpNetworkException.class);
    }

    // ------------------------------------------------------------------ firewall exposure

    private static Map<String, Object> allow(String protocol, String... ports) {
        return Map.of("IPProtocol", protocol, "ports", List.of(ports));
    }

    @Test
    @DisplayName("flags SSH open to the whole internet")
    void flagsPublicSsh() {
        List<FirewallExposure.Finding> findings = FirewallExposure.assess(
                "INGRESS", "ALLOW", List.of("0.0.0.0/0"), List.of(allow("tcp", "22")));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).service()).isEqualTo("SSH");
        assertThat(findings.get(0).message()).contains("entire internet");
    }

    @Test
    @DisplayName("flags RDP too")
    void flagsPublicRdp() {
        assertThat(FirewallExposure.assess("INGRESS", "ALLOW", List.of("0.0.0.0/0"),
                List.of(allow("tcp", "3389")))).hasSize(1);
    }

    @Test
    @DisplayName("finds an administrative port hidden inside a range")
    void flagsPortInsideRange() {
        // "1-65535" is a common way to write "everything" and reporting only its endpoints would miss it.
        List<FirewallExposure.Finding> findings = FirewallExposure.assess(
                "INGRESS", "ALLOW", List.of("0.0.0.0/0"), List.of(allow("tcp", "1-65535")));

        assertThat(findings).isNotEmpty();
        assertThat(findings).anySatisfy(f -> assertThat(f.service()).isEqualTo("SSH"));
    }

    @Test
    @DisplayName("flags a tcp entry with no ports, which means every port")
    void flagsAllPorts() {
        List<FirewallExposure.Finding> findings = FirewallExposure.assess(
                "INGRESS", "ALLOW", List.of("0.0.0.0/0"), List.of(Map.of("IPProtocol", "tcp")));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).port()).isEqualTo("all");
    }

    @Test
    @DisplayName("flags protocol 'all' from the internet")
    void flagsAllProtocols() {
        assertThat(FirewallExposure.assess("INGRESS", "ALLOW", List.of("0.0.0.0/0"),
                List.of(Map.of("IPProtocol", "all")))).hasSize(1);
    }

    @Test
    @DisplayName("does not flag a web port, which is what a web server is for")
    void ignoresWebPorts() {
        assertThat(FirewallExposure.assess("INGRESS", "ALLOW", List.of("0.0.0.0/0"),
                List.of(allow("tcp", "443", "80")))).isEmpty();
    }

    @Test
    @DisplayName("does not flag SSH from a private range")
    void ignoresPrivateSource() {
        assertThat(FirewallExposure.assess("INGRESS", "ALLOW", List.of("10.0.0.0/8"),
                List.of(allow("tcp", "22")))).isEmpty();
    }

    @Test
    @DisplayName("does not flag egress, which is ordinary outbound traffic")
    void ignoresEgress() {
        assertThat(FirewallExposure.assess("EGRESS", "ALLOW", List.of("0.0.0.0/0"),
                List.of(allow("tcp", "22")))).isEmpty();
    }

    @Test
    @DisplayName("does not flag a deny rule, which is a control rather than a hole")
    void ignoresDeny() {
        assertThat(FirewallExposure.assess("INGRESS", "DENY", List.of("0.0.0.0/0"),
                List.of(allow("tcp", "22")))).isEmpty();
    }

    @Test
    @DisplayName("flags every administrative port a rule opens, not just the first")
    void flagsAllExposedPorts() {
        List<FirewallExposure.Finding> findings = FirewallExposure.assess(
                "INGRESS", "ALLOW", List.of("0.0.0.0/0"), List.of(allow("tcp", "22", "3389", "5432")));

        assertThat(findings).hasSize(3);
        assertThat(findings).extracting(FirewallExposure.Finding::service)
                .containsExactlyInAnyOrder("SSH", "RDP", "PostgreSQL");
    }
}
