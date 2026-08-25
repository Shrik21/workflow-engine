package com.orchpilot.workflow.ai.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's own IAM reference — what it will and will not vouch for.
 */
class GcpIamKnowledgeTest {

    private final GcpIamKnowledge iam = new GcpIamKnowledge();

    @ParameterizedTest
    @ValueSource(strings = {"compute.networks.create", "compute.firewalls.delete", "container.clusters.get",
            "iam.serviceAccounts.actAs"})
    @DisplayName("recognises permissions the plugins here can actually raise")
    void knowsRelevantPermissions(String permission) {
        assertThat(iam.isWellFormedPermission(permission)).isTrue();
        assertThat(iam.isKnownPermission(permission)).isTrue();
        assertThat(iam.leastPrivilegeRole(permission)).isPresent();
    }

    @Test
    @DisplayName("returns the least-privilege role first")
    void prefersLeastPrivilege() {
        // networks.get is in both viewer and admin; viewer is the right recommendation.
        assertThat(iam.leastPrivilegeRole("compute.networks.get")).contains("roles/compute.networkViewer");
        assertThat(iam.rolesContaining("compute.networks.get"))
                .containsExactly("roles/compute.networkViewer", "roles/compute.networkAdmin");
    }

    @Test
    @DisplayName("knows firewalls need securityAdmin, not networkAdmin")
    void firewallsNeedSecurityAdmin() {
        // The distinction GCP actually makes, and the one a plausible-sounding answer gets wrong.
        assertThat(iam.leastPrivilegeRole("compute.firewalls.create"))
                .contains("roles/compute.securityAdmin");
        assertThat(iam.rolesContaining("compute.firewalls.create"))
                .doesNotContain("roles/compute.networkAdmin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"just create it", "compute", "compute.networks", "Compute.Networks.Create ",
            "compute networks create"})
    @DisplayName("rejects strings that are not shaped like a permission")
    void rejectsMalformed(String candidate) {
        assertThat(iam.isWellFormedPermission(candidate)).isFalse();
    }

    @Test
    @DisplayName("a well-formed permission it has never heard of is unknown, not invalid")
    void unknownIsNotWrong() {
        // GCP has thousands; this table has dozens. Unknown must not mean "the model made it up".
        assertThat(iam.isWellFormedPermission("bigquery.datasets.create")).isTrue();
        assertThat(iam.isKnownPermission("bigquery.datasets.create")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"roles/owner", "roles/editor", "roles/iam.securityAdmin"})
    @DisplayName("treats blanket roles as over-broad")
    void flagsOverbroadRoles(String role) {
        assertThat(iam.isOverbroad(role)).isTrue();
    }

    @Test
    @DisplayName("accepts custom role names")
    void acceptsCustomRoles() {
        assertThat(iam.isWellFormedRole("projects/acme/roles/networkCreator")).isTrue();
        assertThat(iam.isWellFormedRole("organizations/12345/roles/netAdmin")).isTrue();
        assertThat(iam.isWellFormedRole("networkAdmin")).isFalse();
    }

    @Test
    @DisplayName("validation is silent when everything checks out")
    void validatesGoodPair() {
        assertThat(iam.validate("compute.networks.create", "roles/compute.networkAdmin")).isEmpty();
    }

    @Test
    @DisplayName("validation warns about each kind of problem")
    void validatesProblems() {
        assertThat(iam.validate("compute.vpc.invent", "roles/compute.networkAdmin"))
                .anyMatch(w -> w.contains("could not be confirmed"));
        assertThat(iam.validate("compute.networks.create", "roles/owner"))
                .anyMatch(w -> w.contains("grants far more"));
        assertThat(iam.validate("compute.firewalls.create", "roles/compute.networkAdmin"))
                .anyMatch(w -> w.contains("roles/compute.securityAdmin"));
        assertThat(iam.validate("not a permission", null))
                .anyMatch(w -> w.contains("not shaped like"));
    }

    @Test
    @DisplayName("nulls are not a problem to validate")
    void tolerantOfNulls() {
        assertThat(iam.validate(null, null)).isEmpty();
        assertThat(iam.isKnownPermission(null)).isFalse();
        assertThat(iam.rolesContaining(null)).isEmpty();
        assertThat(iam.leastPrivilegeRole(null)).isEmpty();
    }
}
