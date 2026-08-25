package com.orchpilot.workflow.plugins.vpn;

import com.orchpilot.workflow.plugins.vpn.provider.VpnProviderRegistry;
import com.orchpilot.workflow.plugins.vpn.spi.VpnProvider;
import com.orchpilot.workflow.sdk.context.PluginContext;

import java.util.List;

/** A plugin whose registry is one fake provider, so the node can be tested without a network. */
final class TestVpnPlugin extends VpnPlugin {

    private final VpnProvider provider;

    TestVpnPlugin(VpnProvider provider) {
        this.provider = provider;
    }

    @Override
    protected VpnProviderRegistry buildRegistry(PluginContext pluginContext) {
        return VpnProviderRegistry.of(List.of(provider));
    }
}
