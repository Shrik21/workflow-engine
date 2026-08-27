package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.plugin.PluginDescriptor;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.support.testplugin.EchoPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leasing, draining and version resolution.
 */
class PluginHandleAndRegistryTest {

    private static PluginHandle handle(String pluginId, String version, String... nodeTypes) {
        List<NodeDefinition> definitions = java.util.Arrays.stream(nodeTypes)
                .map(nodeType -> NodeDefinition.builder(nodeType).displayName(nodeType).build())
                .toList();
        PluginDescriptor descriptor = PluginDescriptor.builder(pluginId, version)
                .name(pluginId)
                .type(PluginType.NODE)
                .nodeDefinitions(definitions)
                .build();
        PluginVersion metadata = new PluginVersion();
        metadata.setId(PluginVersion.idFor(pluginId, version));
        metadata.setPluginId(pluginId);
        metadata.setVersion(version);
        PluginHandle created = new PluginHandle(descriptor, new EchoPlugin(), null, null, metadata,
                Path.of(System.getProperty("java.io.tmpdir")));
        created.state(PluginState.ACTIVE);
        return created;
    }

    @Test
    @DisplayName("leases are granted while active and refused once draining")
    void leasesAreRefusedWhenDraining() {
        PluginHandle handle = handle("echo", "1.0.0", "ECHO");

        assertTrue(handle.tryAcquireLease());
        assertEquals(1, handle.activeLeaseCount());

        handle.beginDraining();

        assertFalse(handle.tryAcquireLease(), "a draining plugin must not admit new work");
        assertEquals(1, handle.activeLeaseCount(), "a refused lease must not be counted");
    }

    @Test
    @DisplayName("awaitQuiescence waits for in-flight work and then returns true")
    void awaitQuiescenceWaitsForInFlightWork() throws Exception {
        PluginHandle handle = handle("echo", "1.0.0", "ECHO");
        assertTrue(handle.tryAcquireLease());

        CountDownLatch released = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(120);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            handle.releaseLease();
            released.countDown();
        });
        worker.start();

        handle.beginDraining();
        boolean quiescent = handle.awaitQuiescence(3_000);

        assertTrue(released.await(3, TimeUnit.SECONDS));
        assertTrue(quiescent, "draining must wait for the node that is already running");
        assertEquals(0, handle.activeLeaseCount());
        worker.join();
    }

    @Test
    @DisplayName("awaitQuiescence gives up and reports failure when work does not finish")
    void awaitQuiescenceTimesOut() {
        PluginHandle handle = handle("echo", "1.0.0", "ECHO");
        assertTrue(handle.tryAcquireLease());
        handle.beginDraining();

        assertFalse(handle.awaitQuiescence(150));
        assertEquals(1, handle.activeLeaseCount());
    }

    @Test
    @DisplayName("concurrent leases are counted correctly")
    void concurrentLeasesAreCounted() throws Exception {
        PluginHandle handle = handle("echo", "1.0.0", "ECHO");
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();
        AtomicBoolean failure = new AtomicBoolean();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (handle.tryAcquireLease()) {
                        granted.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failure.set(true);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertFalse(failure.get());
        assertEquals(threads, granted.get());
        assertEquals(threads, handle.activeLeaseCount());
        assertEquals(threads, handle.totalInvocations());
    }

    @Test
    @DisplayName("the registry resolves a pinned version exactly and an unpinned one to the default")
    void resolvesPinnedAndDefaultVersions() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry();
        registry.register(handle("sendgrid", "1.0.0", "SENDGRID_EMAIL"));
        registry.register(handle("sendgrid", "1.1.0", "SENDGRID_EMAIL"));

        assertEquals("1.0.0", registry.find("sendgrid", "1.0.0").orElseThrow().version());
        assertEquals("1.1.0", registry.findDefault("sendgrid").orElseThrow().version(),
                "the most recently registered version becomes the default");
        assertEquals("1.1.0", registry.findByNodeType("SENDGRID_EMAIL").orElseThrow().version());
        assertEquals(2, registry.versionsOf("sendgrid").size());
    }

    @Test
    @DisplayName("unregistering the default promotes a remaining version instead of orphaning its node types")
    void unregisteringDefaultPromotesAnother() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry();
        registry.register(handle("sendgrid", "1.0.0", "SENDGRID_EMAIL"));
        registry.register(handle("sendgrid", "2.0.0", "SENDGRID_EMAIL"));

        registry.unregister("sendgrid", "2.0.0");

        assertEquals("1.0.0", registry.findDefault("sendgrid").orElseThrow().version());
        assertEquals("1.0.0", registry.findByNodeType("SENDGRID_EMAIL").orElseThrow().version(),
                "removing the newer version must hand its node type back, not make it unresolvable");
    }

    @Test
    @DisplayName("unregistering the last version removes its node types from the index")
    void unregisteringLastVersionClearsNodeTypes() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry();
        registry.register(handle("slack", "1.0.0", "SLACK_MESSAGE"));

        registry.unregister("slack", "1.0.0");

        assertTrue(registry.findByNodeType("SLACK_MESSAGE").isEmpty());
        assertTrue(registry.nodeTypes().isEmpty());
        assertTrue(registry.findDefault("slack").isEmpty());
    }

    @Test
    @DisplayName("the default version can be chosen explicitly, and only among loaded versions")
    void defaultVersionCanBeChosen() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry();
        registry.register(handle("sendgrid", "1.0.0", "SENDGRID_EMAIL"));
        registry.register(handle("sendgrid", "2.0.0", "SENDGRID_EMAIL"));

        registry.setDefaultVersion("sendgrid", "1.0.0");

        assertEquals("1.0.0", registry.findByNodeType("SENDGRID_EMAIL").orElseThrow().version());
        assertThrows(IllegalArgumentException.class,
                () -> registry.setDefaultVersion("sendgrid", "9.9.9"));
    }

    @Test
    @DisplayName("node definitions are reported for the default version only")
    void nodeDefinitionsComeFromDefaultVersion() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry();
        registry.register(handle("multi", "1.0.0", "A", "B"));
        registry.register(handle("other", "1.0.0", "C"));

        assertEquals(3, registry.nodeDefinitions().size());
        assertEquals(3, registry.nodeTypes().size());
    }
}
