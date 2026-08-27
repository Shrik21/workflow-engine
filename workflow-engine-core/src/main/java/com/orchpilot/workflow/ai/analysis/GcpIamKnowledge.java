package com.orchpilot.workflow.ai.analysis;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the engine itself knows about GCP IAM, used to check the model's claims.
 *
 * <h2>Why a curated table rather than the IAM API</h2>
 *
 * The specification asks to validate recommendations "against known GCP IAM metadata/API where possible". The
 * live API would be authoritative but needs a credential, a network call and a permission of its own on every
 * analysis — turning an explanation into a billable, failable API dependency. This table covers the permissions
 * the plugins in this repository can actually raise, which is the set that can realistically appear, and is
 * checked in so a reviewer can see exactly what the engine will vouch for.
 *
 * <h2>What "unknown" means</h2>
 *
 * Not "wrong". GCP has thousands of permissions and this table has dozens. An unrecognised permission is
 * reported as unverified and shown to the operator as such — never silently accepted, and never silently
 * discarded either, because a correct recommendation outside this table is entirely possible.
 */
@Component
public class GcpIamKnowledge {

    /** {@code service.resource.verb} — the shape every GCP IAM permission takes. */
    private static final Pattern PERMISSION_SHAPE =
            Pattern.compile("^[a-z][a-zA-Z0-9]*\\.[a-zA-Z0-9]+(\\.[a-zA-Z0-9]+)+$");

    /** {@code roles/...} for predefined, {@code projects/x/roles/y} or {@code organizations/...} for custom. */
    private static final Pattern ROLE_SHAPE =
            Pattern.compile("^(roles/[a-zA-Z0-9._]+|(projects|organizations)/[^/]+/roles/[a-zA-Z0-9._-]+)$");

    /**
     * Permission to the predefined roles that contain it.
     *
     * <p>Ordered least-privilege first, so the first entry is the one to recommend. That ordering is the whole
     * value of the table: {@code roles/owner} contains every permission here and is almost never the right
     * answer.
     */
    private static final Map<String, List<String>> PERMISSION_ROLES = new LinkedHashMap<>();

    /** Roles this engine will never recommend, however plausible the model finds them. */
    private static final Set<String> OVERBROAD_ROLES = Set.of(
            "roles/owner", "roles/editor", "roles/admin", "roles/iam.securityAdmin");

    static {
        // ---------------------------------------------------------------- Compute networks
        network("compute.networks.create", "roles/compute.networkAdmin");
        network("compute.networks.get", "roles/compute.networkViewer", "roles/compute.networkAdmin");
        network("compute.networks.list", "roles/compute.networkViewer", "roles/compute.networkAdmin");
        network("compute.networks.delete", "roles/compute.networkAdmin");
        network("compute.networks.update", "roles/compute.networkAdmin");
        network("compute.networks.updatePolicy", "roles/compute.networkAdmin");
        network("compute.networks.addPeering", "roles/compute.networkAdmin");
        network("compute.networks.removePeering", "roles/compute.networkAdmin");

        // ---------------------------------------------------------------- Subnets
        network("compute.subnetworks.create", "roles/compute.networkAdmin");
        network("compute.subnetworks.get", "roles/compute.networkViewer", "roles/compute.networkAdmin");
        network("compute.subnetworks.list", "roles/compute.networkViewer", "roles/compute.networkAdmin");
        network("compute.subnetworks.delete", "roles/compute.networkAdmin");
        network("compute.subnetworks.update", "roles/compute.networkAdmin");
        network("compute.subnetworks.use", "roles/compute.networkUser");
        network("compute.subnetworks.useExternalIp", "roles/compute.networkUser");

        // ---------------------------------------------------------------- Firewalls
        // securityAdmin, not networkAdmin: firewall rules are the security surface and GCP separates them.
        network("compute.firewalls.create", "roles/compute.securityAdmin");
        network("compute.firewalls.get", "roles/compute.networkViewer", "roles/compute.securityAdmin");
        network("compute.firewalls.list", "roles/compute.networkViewer", "roles/compute.securityAdmin");
        network("compute.firewalls.update", "roles/compute.securityAdmin");
        network("compute.firewalls.delete", "roles/compute.securityAdmin");

        // ---------------------------------------------------------------- Routes, routers, NAT
        network("compute.routes.create", "roles/compute.networkAdmin");
        network("compute.routes.get", "roles/compute.networkViewer", "roles/compute.networkAdmin");
        network("compute.routes.list", "roles/compute.networkViewer", "roles/compute.networkAdmin");
        network("compute.routes.delete", "roles/compute.networkAdmin");
        network("compute.routers.create", "roles/compute.networkAdmin");
        network("compute.routers.get", "roles/compute.networkViewer", "roles/compute.networkAdmin");
        network("compute.routers.list", "roles/compute.networkViewer", "roles/compute.networkAdmin");
        network("compute.routers.update", "roles/compute.networkAdmin");
        network("compute.routers.delete", "roles/compute.networkAdmin");

        // ---------------------------------------------------------------- Instances
        network("compute.instances.create", "roles/compute.instanceAdmin.v1");
        network("compute.instances.get", "roles/compute.viewer", "roles/compute.instanceAdmin.v1");
        network("compute.instances.list", "roles/compute.viewer", "roles/compute.instanceAdmin.v1");
        network("compute.instances.delete", "roles/compute.instanceAdmin.v1");
        network("compute.instances.start", "roles/compute.instanceAdmin.v1");
        network("compute.instances.stop", "roles/compute.instanceAdmin.v1");
        network("compute.instances.setMetadata", "roles/compute.instanceAdmin.v1");
        network("compute.zones.list", "roles/compute.viewer");
        network("compute.regions.list", "roles/compute.viewer");

        // ---------------------------------------------------------------- GKE
        network("container.clusters.create", "roles/container.clusterAdmin");
        network("container.clusters.get", "roles/container.clusterViewer", "roles/container.clusterAdmin");
        network("container.clusters.list", "roles/container.clusterViewer", "roles/container.clusterAdmin");
        network("container.clusters.update", "roles/container.clusterAdmin");
        network("container.clusters.delete", "roles/container.clusterAdmin");
        network("container.pods.get", "roles/container.viewer");
        network("container.pods.list", "roles/container.viewer");
        network("container.deployments.get", "roles/container.viewer");
        network("container.deployments.update", "roles/container.developer");

        // ---------------------------------------------------------------- Service accounts and IAM
        network("iam.serviceAccounts.actAs", "roles/iam.serviceAccountUser");
        network("iam.serviceAccounts.get", "roles/iam.serviceAccountViewer");
        network("resourcemanager.projects.get", "roles/browser", "roles/viewer");
        network("resourcemanager.projects.setIamPolicy", "roles/resourcemanager.projectIamAdmin");
        network("resourcemanager.projects.getIamPolicy", "roles/iam.securityReviewer");
    }

    private static void network(String permission, String... roles) {
        PERMISSION_ROLES.put(permission, List.of(roles));
    }

    /** @return whether the string is even shaped like a GCP IAM permission */
    public boolean isWellFormedPermission(String permission) {
        return permission != null && PERMISSION_SHAPE.matcher(permission.trim()).matches();
    }

    /** @return whether the string is shaped like a GCP role name */
    public boolean isWellFormedRole(String role) {
        return role != null && ROLE_SHAPE.matcher(role.trim()).matches();
    }

    /** @return whether this permission is one the engine can vouch for */
    public boolean isKnownPermission(String permission) {
        return permission != null && PERMISSION_ROLES.containsKey(permission.trim());
    }

    /**
     * @param permission a GCP IAM permission
     * @return the least-privilege predefined role containing it, when known
     */
    public Optional<String> leastPrivilegeRole(String permission) {
        if (permission == null) {
            return Optional.empty();
        }
        List<String> roles = PERMISSION_ROLES.get(permission.trim());
        return roles == null || roles.isEmpty() ? Optional.empty() : Optional.of(roles.get(0));
    }

    /**
     * @param permission a GCP IAM permission
     * @return every predefined role known to contain it
     */
    public List<String> rolesContaining(String permission) {
        if (permission == null) {
            return List.of();
        }
        return PERMISSION_ROLES.getOrDefault(permission.trim(), List.of());
    }

    /** @return whether the role grants far more than any single permission needs */
    public boolean isOverbroad(String role) {
        return role != null && OVERBROAD_ROLES.contains(role.trim());
    }

    /**
     * Checks a model's IAM claims and says what could not be confirmed.
     *
     * @param permission the permission the model named, or null
     * @param role       the role the model recommended, or null
     * @return warnings; empty when everything checked out
     */
    public List<String> validate(String permission, String role) {
        Set<String> warnings = new LinkedHashSet<>();

        if (permission != null && !permission.isBlank()) {
            if (!isWellFormedPermission(permission)) {
                warnings.add("'" + permission + "' is not shaped like a GCP IAM permission "
                        + "(service.resource.verb). Treat it as unverified.");
            } else if (!isKnownPermission(permission)) {
                warnings.add("'" + permission + "' is not in this engine's IAM reference, so it could not be "
                        + "confirmed. Check it against GCP's documentation before acting on it.");
            }
        }

        if (role != null && !role.isBlank()) {
            if (!isWellFormedRole(role)) {
                warnings.add("'" + role + "' is not shaped like a GCP role name (roles/...). "
                        + "Treat it as unverified.");
            } else if (isOverbroad(role)) {
                warnings.add("'" + role + "' grants far more than this operation needs. Prefer the narrowest "
                        + "role that contains the missing permission.");
            } else if (isKnownPermission(permission) && !rolesContaining(permission).contains(role.trim())) {
                warnings.add("This engine's reference does not list '" + role + "' as containing '" + permission
                        + "'. Known roles: " + String.join(", ", rolesContaining(permission)) + ".");
            }
        }

        return List.copyOf(warnings);
    }
}
