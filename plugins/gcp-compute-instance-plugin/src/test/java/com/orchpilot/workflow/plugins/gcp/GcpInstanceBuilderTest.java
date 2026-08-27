package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The instances.insert body, built and asserted without any HTTP or auth. Verifies the machine-type and disk-type
 * paths, image resolution, the external-IP options, labels/tags/startup-script/service-account, and that an
 * invalid label is rejected rather than silently sent to GCP.
 */
class GcpInstanceBuilderTest {

    private Map<String, Object> base() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("instanceName", "orchpilot-vm");
        cfg.put("machineType", "e2-medium");
        cfg.put("imageProject", "ubuntu-os-cloud");
        cfg.put("imageFamily", "ubuntu-2404-lts-amd64");
        return cfg;
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsMachineTypeDiskAndImagePaths() {
        Map<String, Object> instance = GcpInstanceBuilder.build(new MapConfiguration(base()), "asia-south1-a");

        assertThat(instance.get("name")).isEqualTo("orchpilot-vm");
        assertThat(instance.get("machineType")).isEqualTo("zones/asia-south1-a/machineTypes/e2-medium");

        Map<String, Object> disk = ((List<Map<String, Object>>) instance.get("disks")).get(0);
        Map<String, Object> init = (Map<String, Object>) disk.get("initializeParams");
        assertThat(init.get("sourceImage"))
                .isEqualTo("projects/ubuntu-os-cloud/global/images/family/ubuntu-2404-lts-amd64");
        assertThat(init.get("diskType")).isEqualTo("zones/asia-south1-a/diskTypes/pd-balanced");
        assertThat(init.get("diskSizeGb")).isEqualTo("30");
        assertThat(disk.get("boot")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ephemeralExternalIpAddsAccessConfigAndNoneOmitsIt() {
        Map<String, Object> ephemeral = GcpInstanceBuilder.build(new MapConfiguration(base()), "asia-south1-a");
        Map<String, Object> nic = ((List<Map<String, Object>>) ephemeral.get("networkInterfaces")).get(0);
        assertThat(nic.get("network")).isEqualTo("global/networks/default");
        assertThat(nic.get("accessConfigs")).isNotNull();

        Map<String, Object> cfg = base();
        cfg.put("externalIp", "NONE");
        Map<String, Object> none = GcpInstanceBuilder.build(new MapConfiguration(cfg), "asia-south1-a");
        Map<String, Object> nic2 = ((List<Map<String, Object>>) none.get("networkInterfaces")).get(0);
        assertThat(nic2).doesNotContainKey("accessConfigs");
    }

    @Test
    @SuppressWarnings("unchecked")
    void carriesLabelsTagsStartupScriptAndServiceAccount() {
        Map<String, Object> cfg = base();
        cfg.put("labels", Map.of("environment", "dev", "owner", "orchpilot"));
        cfg.put("tags", "http-server https-server");
        cfg.put("startupScript", "#!/bin/bash\necho hi");
        cfg.put("serviceAccount", "vm@project.iam.gserviceaccount.com");
        cfg.put("deletionProtection", true);

        Map<String, Object> instance = GcpInstanceBuilder.build(new MapConfiguration(cfg), "asia-south1-a");

        assertThat((Map<String, Object>) instance.get("labels")).containsEntry("environment", "dev");
        assertThat((List<String>) ((Map<String, Object>) instance.get("tags")).get("items"))
                .containsExactly("http-server", "https-server");
        List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) instance.get("metadata"))
                .get("items");
        assertThat(items.get(0)).containsEntry("key", "startup-script");
        assertThat(instance.get("serviceAccounts")).isNotNull();
        assertThat(instance.get("deletionProtection")).isEqualTo(true);
    }

    @Test
    void rejectsAnInvalidLabel() {
        Map<String, Object> cfg = base();
        cfg.put("labels", Map.of("Bad Key!", "value"));
        assertThatThrownBy(() -> GcpInstanceBuilder.build(new MapConfiguration(cfg), "asia-south1-a"))
                .isInstanceOf(PluginConfigurationException.class)
                .hasMessageContaining("Invalid GCP label");
    }

    @Test
    void requiresAnImage() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("instanceName", "vm");
        assertThatThrownBy(() -> GcpInstanceBuilder.build(new MapConfiguration(cfg), "asia-south1-a"))
                .isInstanceOf(PluginConfigurationException.class)
                .hasMessageContaining("boot image");
    }

    @Test
    void derivesRegionFromZone() {
        assertThat(GcpInstanceBuilder.regionOf("asia-south1-a")).isEqualTo("asia-south1");
        assertThat(GcpInstanceBuilder.regionOf("us-central1-b")).isEqualTo("us-central1");
    }
}
