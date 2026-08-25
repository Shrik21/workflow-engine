package com.orchpilot.pluginserver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration that cannot work must stop the service starting.
 *
 * <p>The failure mode being prevented: no verification key configured, so the service reports healthy and
 * answers 401 to every caller, which looks like the caller's problem and is not.
 */
class PluginServerPropertiesTest {

    private static PluginServerProperties properties() {
        PluginServerProperties properties = new PluginServerProperties();
        properties.getRegistry().setMaxJarSize(DataSize.ofMegabytes(64));
        return properties;
    }

    @Test
    @DisplayName("a shared secret is enough to start")
    void secretIsEnough() {
        PluginServerProperties properties = properties();
        properties.getSecurity().setJwtSecret("a-secret-long-enough-to-be-worth-something");

        assertDoesNotThrow(properties::validate);
        assertTrue(properties.getSecurity().isSymmetric());
    }

    @Test
    @DisplayName("a key set is enough to start, and is not symmetric")
    void jwksIsEnough() {
        PluginServerProperties properties = properties();
        properties.getSecurity().setJwksUri("https://auth.example.test/.well-known/jwks.json");

        assertDoesNotThrow(properties::validate);
        assertTrue(!properties.getSecurity().isSymmetric());
    }

    @Test
    @DisplayName("no verification key refuses to start, and says how to fix it")
    void noKeyFailsFast() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> properties().validate());

        assertTrue(failure.getMessage().contains("PLUGIN_SERVER_JWT_SECRET"),
                () -> "the message must name the setting: " + failure.getMessage());
    }

    @Test
    @DisplayName("both key forms at once refuses to start, because they mean different trust models")
    void bothKeysFailFast() {
        PluginServerProperties properties = properties();
        properties.getSecurity().setJwtSecret("a-secret-long-enough-to-be-worth-something");
        properties.getSecurity().setJwksUri("https://auth.example.test/jwks.json");

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("a short symmetric secret is refused")
    void shortSecretFailsFast() {
        PluginServerProperties properties = properties();
        properties.getSecurity().setJwtSecret("too-short");

        // A forgeable token on this service is a credential for publishing executable code.
        IllegalStateException failure = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(failure.getMessage().contains("at least 32"));
    }

    @Test
    @DisplayName("a non-positive archive size limit is refused")
    void zeroSizeFailsFast() {
        PluginServerProperties properties = properties();
        properties.getSecurity().setJwtSecret("a-secret-long-enough-to-be-worth-something");
        properties.getRegistry().setMaxJarSize(DataSize.ofBytes(0));

        assertThrows(IllegalStateException.class, properties::validate);
    }
}
