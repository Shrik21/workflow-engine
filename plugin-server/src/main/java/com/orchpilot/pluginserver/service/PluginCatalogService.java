package com.orchpilot.pluginserver.service;

import com.orchpilot.pluginserver.dto.PluginCatalogEntry;
import com.orchpilot.pluginserver.model.Plugin;
import com.orchpilot.pluginserver.model.PluginStatus;
import com.orchpilot.pluginserver.model.PluginVersion;
import com.orchpilot.pluginserver.repository.PluginVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue: one payload a workflow service can sync from.
 *
 * <h2>Assembled in two queries, not two per plugin</h2>
 *
 * <p>Every available plugin, then every published version of any of them, joined in memory. The obvious loop, one
 * version query per plugin, is fine at ten plugins and is a hundred round trips at a hundred, on a request every
 * workflow service in the estate makes every few minutes.
 *
 * <h2>Why the ETag matters here more than usual</h2>
 *
 * <p>This payload changes rarely and is polled constantly. With a conditional request, a steady state costs a 304
 * and a fingerprint computation; without one, it costs the full catalogue serialised and transferred per service
 * per interval. The fingerprint is derived from what a client would actually react to, so it does not change when
 * something meaningless does.
 */
@Service
public class PluginCatalogService {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogService.class);

    /** Versions a workflow service may install. A draft is not offered; a revoked version is refused. */
    private static final EnumSet<PluginStatus> PUBLISHED =
            EnumSet.of(PluginStatus.ACTIVE, PluginStatus.DEPRECATED);

    private final PluginService plugins;
    private final PluginVersionRepository versions;

    public PluginCatalogService(PluginService plugins, PluginVersionRepository versions) {
        this.plugins = plugins;
        this.versions = versions;
    }

    /**
     * The catalogue, and the tag that identifies it.
     *
     * @param entries     available plugins, by plugin id
     * @param etag        strong validator, quoted, for {@code ETag} and {@code If-None-Match}
     * @param generatedAt when this snapshot was assembled
     */
    public record Catalog(List<PluginCatalogEntry> entries, String etag, Instant generatedAt) {

        public Catalog {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        /** @return whether a client's validator matches this snapshot */
        public boolean matches(String ifNoneMatch) {
            if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
                return false;
            }
            // A client may send several, and a proxy may weaken one to W/"...". Both are handled rather than
            // treated as a miss, because a miss here means transferring the whole catalogue again.
            for (String candidate : ifNoneMatch.split(",")) {
                String trimmed = candidate.trim();
                if (trimmed.startsWith("W/")) {
                    trimmed = trimmed.substring(2);
                }
                if (trimmed.equals(etag) || "*".equals(trimmed)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Builds the catalogue.
     *
     * @return the snapshot, ordered by plugin id so a diff between two syncs is readable
     */
    public Catalog catalog() {
        List<Plugin> available = plugins.catalogue();
        if (available.isEmpty()) {
            return new Catalog(List.of(), etagOf(List.of()), Instant.now());
        }

        Map<String, List<PluginVersion>> byPlugin = new HashMap<>();
        for (PluginVersion version : versions.findByStatusIn(PUBLISHED)) {
            byPlugin.computeIfAbsent(version.getPluginId(), key -> new ArrayList<>()).add(version);
        }

        List<PluginCatalogEntry> entries = new ArrayList<>(available.size());
        for (Plugin plugin : available) {
            List<PluginVersion> published = byPlugin.getOrDefault(plugin.getPluginId(), List.of());
            PluginCatalogEntry entry = PluginCatalogEntry.of(plugin, published);
            if (entry != null) {
                entries.add(entry);
            } else {
                // The head says it has a latest version and no published version backs that up. Worth a line:
                // it means a recompute was missed, and the plugin is invisible to every workflow service.
                log.warn("Plugin '{}' claims latest version {} but has no published version; omitting it "
                        + "from the catalogue", plugin.getPluginId(), plugin.getLatestVersion());
            }
        }
        entries.sort(Comparator.comparing(PluginCatalogEntry::pluginId));

        Catalog catalog = new Catalog(entries, etagOf(entries), Instant.now());
        log.debug("Assembled a catalogue of {} plugin(s), etag {}", entries.size(), catalog.etag());
        return catalog;
    }

    /**
     * A strong validator over the whole catalogue.
     *
     * <p>Strong rather than weak because the payload is derived deterministically from these fields: two responses
     * with the same tag are byte-identical, which is what a strong validator promises and what makes a 304 safe.
     */
    private String etagOf(List<PluginCatalogEntry> entries) {
        StringBuilder fingerprint = new StringBuilder(entries.size() * 64);
        for (PluginCatalogEntry entry : entries) {
            fingerprint.append(entry.fingerprint()).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            // Half the digest. A catalogue tag is a cache key, not a signature, and 128 bits of it is beyond
            // any chance of accidental collision.
            for (int index = 0; index < 16; index++) {
                hex.append(Character.forDigit((hash[index] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[index] & 0xF, 16));
            }
            return "\"" + hex + "\"";
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", ex);
        }
    }
}
