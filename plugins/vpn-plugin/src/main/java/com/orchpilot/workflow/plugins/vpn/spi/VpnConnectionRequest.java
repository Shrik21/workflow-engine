package com.orchpilot.workflow.plugins.vpn.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a provider is given for one operation, resolved and safe to hand across the SPI.
 *
 * <h2>Resolved, and free of raw secrets in the parts a provider logs</h2>
 *
 * Every value here has already been through the engine's variable resolver, and every credential has already
 * been fetched from the secret store — {@link #secrets()} holds the values, {@link #settings()} does not.
 * A provider reads a credential by name from {@code secrets()} and is expected never to put it in a log, a
 * result, or an exception message; the {@link #describe()} method is the safe thing to log instead.
 *
 * @param operation   what to do
 * @param provider    which provider
 * @param connectionId the provider-specific connection identifier, where the operation acts on an existing one
 * @param region      cloud region, where the provider has the concept
 * @param settings    non-secret configuration, resolved
 * @param secrets     resolved credential values, keyed by the logical name a provider asks for
 */
public record VpnConnectionRequest(
        String operation,
        String provider,
        String connectionId,
        String region,
        Map<String, Object> settings,
        Map<String, String> secrets) {

    public VpnConnectionRequest {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }

    /** @return a setting as text, or the default when absent */
    public String setting(String key, String defaultValue) {
        Object value = settings.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    /** @return a required setting, or empty when it is absent or blank */
    public Optional<String> optionalSetting(String key) {
        String value = setting(key, "");
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /** @return a setting as an int, or the default when absent or unparseable */
    public int intSetting(String key, int defaultValue) {
        Object value = settings.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /** @return a setting as a boolean */
    public boolean boolSetting(String key, boolean defaultValue) {
        Object value = settings.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    /** @return a setting split into a list on commas, for CIDR lists and allowed IPs */
    public List<String> listSetting(String key) {
        String value = setting(key, "");
        if (value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[,\\s]+"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /**
     * @param name the logical credential name, such as {@code secretKey} or {@code presharedKey}
     * @return the resolved value, or empty when the operator supplied none
     */
    public Optional<String> secret(String name) {
        return Optional.ofNullable(secrets.get(name)).filter(value -> !value.isBlank());
    }

    /**
     * A description safe to log or return: the operation, provider and connection, never a credential.
     *
     * @return the safe description
     */
    public String describe() {
        StringBuilder description = new StringBuilder(provider).append(' ').append(operation);
        if (connectionId != null && !connectionId.isBlank()) {
            description.append(" ").append(connectionId);
        }
        if (region != null && !region.isBlank()) {
            description.append(" @").append(region);
        }
        return description.toString();
    }
}
