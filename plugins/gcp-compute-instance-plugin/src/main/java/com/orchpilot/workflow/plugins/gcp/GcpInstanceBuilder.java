package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the Compute Engine {@code instances.insert} request body from a node's resolved configuration.
 *
 * <p>Kept apart from the plugin so the shape of the JSON GCP expects — machine type paths, disk init params,
 * network interfaces, access configs, metadata, labels and tags — can be built and unit-tested on its own, with no
 * HTTP and no auth. Nothing here is hard-coded to a region, machine family or image; the values come from
 * configuration (or the documented defaults), so a new machine type or image works without a plugin change.
 */
final class GcpInstanceBuilder {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9_-]{0,63}");

    private GcpInstanceBuilder() {
    }

    static Map<String, Object> build(NodeConfiguration cfg, String zone) {
        String region = regionOf(zone);
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("name", cfg.requireString("instanceName"));

        String machineType = cfg.getString("machineType", "e2-medium");
        instance.put("machineType", "zones/" + zone + "/machineTypes/" + machineType);

        instance.put("disks", List.of(bootDisk(cfg, zone)));
        instance.put("networkInterfaces", List.of(networkInterface(cfg, region)));

        Map<String, String> labels = labels(cfg);
        if (!labels.isEmpty()) {
            instance.put("labels", labels);
        }
        List<String> tags = tags(cfg);
        if (!tags.isEmpty()) {
            instance.put("tags", Map.of("items", tags));
        }
        Map<String, Object> metadata = metadata(cfg);
        if (metadata != null) {
            instance.put("metadata", metadata);
        }
        String serviceAccount = cfg.getString("serviceAccount", null);
        if (serviceAccount != null && !serviceAccount.isBlank()) {
            instance.put("serviceAccounts", List.of(Map.of(
                    "email", serviceAccount,
                    "scopes", List.of("https://www.googleapis.com/auth/cloud-platform"))));
        }
        if (cfg.getBoolean("deletionProtection", false)) {
            instance.put("deletionProtection", true);
        }
        return instance;
    }

    private static Map<String, Object> bootDisk(NodeConfiguration cfg, String zone) {
        Map<String, Object> initializeParams = new LinkedHashMap<>();
        initializeParams.put("sourceImage", sourceImage(cfg));
        initializeParams.put("diskSizeGb", String.valueOf(cfg.getLong("diskSizeGb", 30)));
        initializeParams.put("diskType",
                "zones/" + zone + "/diskTypes/" + cfg.getString("diskType", "pd-balanced"));

        Map<String, Object> disk = new LinkedHashMap<>();
        disk.put("boot", true);
        disk.put("autoDelete", cfg.getBoolean("autoDeleteBootDisk", true));
        disk.put("initializeParams", initializeParams);
        return disk;
    }

    /**
     * Resolves the boot image without a hard-coded OS table: a full image path or self-link is used as given,
     * otherwise it is assembled from an image project plus either a specific image name or an image family.
     */
    private static String sourceImage(NodeConfiguration cfg) {
        String image = cfg.getString("image", null);
        if (image != null && !image.isBlank()) {
            return image.startsWith("http") || image.contains("/") ? image
                    : "projects/" + image; // bare value is unusual; treated as a project-qualified path fragment
        }
        String imageProject = cfg.getString("imageProject", null);
        String imageName = cfg.getString("imageName", null);
        String imageFamily = cfg.getString("imageFamily", null);
        if (imageProject == null || imageProject.isBlank()) {
            throw new PluginConfigurationException(
                    "Provide a boot image: either 'image' (a full image path), or 'imageProject' with "
                            + "'imageFamily' or 'imageName'.");
        }
        if (imageName != null && !imageName.isBlank()) {
            return "projects/" + imageProject + "/global/images/" + imageName;
        }
        if (imageFamily != null && !imageFamily.isBlank()) {
            return "projects/" + imageProject + "/global/images/family/" + imageFamily;
        }
        throw new PluginConfigurationException(
                "Provide 'imageFamily' or 'imageName' alongside 'imageProject'.");
    }

    private static Map<String, Object> networkInterface(NodeConfiguration cfg, String region) {
        Map<String, Object> nic = new LinkedHashMap<>();
        nic.put("network", "global/networks/" + cfg.getString("network", "default"));
        String subnet = cfg.getString("subnet", null);
        if (subnet != null && !subnet.isBlank()) {
            nic.put("subnetwork", "regions/" + region + "/subnetworks/" + subnet);
        }
        String externalIp = cfg.getString("externalIp", "EPHEMERAL").trim().toUpperCase(Locale.ROOT);
        switch (externalIp) {
            case "NONE" -> { /* no accessConfigs → no external IP */ }
            case "STATIC" -> {
                String natIp = cfg.requireString("staticExternalIp");
                nic.put("accessConfigs", List.of(Map.of(
                        "type", "ONE_TO_ONE_NAT", "name", "External NAT", "natIP", natIp)));
            }
            default -> nic.put("accessConfigs", List.of(Map.of(
                    "type", "ONE_TO_ONE_NAT", "name", "External NAT")));
        }
        return nic;
    }

    /** Startup script (and any other metadata items) go into instance metadata, GCP's key/value item list. */
    private static Map<String, Object> metadata(NodeConfiguration cfg) {
        List<Map<String, Object>> items = new ArrayList<>();
        String startupScript = cfg.getString("startupScript", null);
        if (startupScript != null && !startupScript.isBlank()) {
            items.add(Map.of("key", "startup-script", "value", startupScript));
        }
        cfg.getStringMap("metadata").forEach((k, v) -> {
            if (k != null && v != null) {
                items.add(Map.of("key", k, "value", v));
            }
        });
        return items.isEmpty() ? null : Map.of("items", items);
    }

    private static Map<String, String> labels(NodeConfiguration cfg) {
        Map<String, String> out = new LinkedHashMap<>();
        cfg.getStringMap("labels").forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String k = key.toLowerCase(Locale.ROOT);
            String v = value.toLowerCase(Locale.ROOT);
            if (!LABEL.matcher(k).matches() || k.isEmpty() || !LABEL.matcher(v).matches()) {
                throw new PluginConfigurationException("Invalid GCP label '" + key + "="
                        + value + "'. Keys and values must be <=63 chars of lowercase letters, digits, '-' or '_'.");
            }
            out.put(k, v);
        });
        return out;
    }

    /**
     * Network tags may arrive either as a JSON list (a workflow authored as raw JSON, or a variable that resolved
     * to a list) or as a comma/space/newline-separated string (the designer's text field). Both are accepted.
     */
    private static List<String> tags(NodeConfiguration cfg) {
        List<String> out = new ArrayList<>();
        Object raw = cfg.find("tags").orElse(null);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        } else if (raw != null) {
            for (String part : String.valueOf(raw).split("[,\\s]+")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    /** Region is the zone without its trailing {@code -<letter>}, e.g. asia-south1-a → asia-south1. */
    static String regionOf(String zone) {
        int last = zone.lastIndexOf('-');
        return last > 0 ? zone.substring(0, last) : zone;
    }
}
