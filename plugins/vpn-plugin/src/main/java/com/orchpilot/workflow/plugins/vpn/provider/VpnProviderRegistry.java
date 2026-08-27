package com.orchpilot.workflow.plugins.vpn.provider;

import com.orchpilot.workflow.plugins.vpn.provider.aws.AwsVpnProvider;
import com.orchpilot.workflow.plugins.vpn.provider.cloud.AzureVpnProvider;
import com.orchpilot.workflow.plugins.vpn.provider.cloud.GcpVpnProvider;
import com.orchpilot.workflow.plugins.vpn.provider.generic.IpsecVpnProvider;
import com.orchpilot.workflow.plugins.vpn.provider.generic.OpenVpnProvider;
import com.orchpilot.workflow.plugins.vpn.provider.generic.WireGuardProvider;
import com.orchpilot.workflow.plugins.vpn.spi.VpnProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The set of providers, and the one place a new one is added.
 *
 * <h2>Adding a provider is a one-line change here and nowhere else</h2>
 *
 * This is the concrete form of the "additional providers without modifying the engine" requirement. The engine
 * knows only the {@code VPN} node; the node asks this registry for a provider by id and dispatches to the SPI.
 * A new provider — a new SD-WAN vendor, a new cloud — is a class implementing {@link VpnProvider} and a line in
 * {@link #built}. Nothing in the engine, the node, the schema or the designer changes.
 *
 * <p>The cloud providers are constructed with the engine's HTTP client so their control-plane calls go through
 * the platform's allowlist; the generic providers need nothing and are constructed bare.
 */
public final class VpnProviderRegistry {

    private final Map<String, VpnProvider> byId;

    private VpnProviderRegistry(List<VpnProvider> providers) {
        Map<String, VpnProvider> map = new LinkedHashMap<>();
        for (VpnProvider provider : providers) {
            map.put(provider.id().toUpperCase(Locale.ROOT), provider);
        }
        this.byId = Map.copyOf(map);
    }

    /**
     * The built-in providers.
     *
     * @param http the engine HTTP client, given to the cloud providers
     * @return the registry
     */
    public static VpnProviderRegistry built(CloudHttp http) {
        return new VpnProviderRegistry(List.of(
                new AwsVpnProvider(http),
                new AzureVpnProvider(http),
                new GcpVpnProvider(http),
                new IpsecVpnProvider(),
                new OpenVpnProvider(),
                new WireGuardProvider()));
    }

    /** Visible for testing, so a fake provider can stand in for a real one. */
    public static VpnProviderRegistry of(List<VpnProvider> providers) {
        return new VpnProviderRegistry(providers);
    }

    /**
     * @param providerId the provider id, in any case
     * @return the provider, or empty when none is registered under that id
     */
    public Optional<VpnProvider> find(String providerId) {
        if (providerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(providerId.trim().toUpperCase(Locale.ROOT)));
    }

    /** @return every provider id, for the node's dropdown and the documentation */
    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }

    /** @return every provider, for building the schema and health */
    public List<VpnProvider> all() {
        return List.copyOf(byId.values());
    }
}
