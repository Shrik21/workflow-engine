package com.orchpilot.workflow.plugin.icon;

import java.util.Base64;

/**
 * An icon shipped inside a plugin archive.
 *
 * @param fileName   the entry it came from, for diagnostics
 * @param mediaType  {@code image/svg+xml} or {@code image/png}
 * @param data       the bytes, already sanitised when SVG
 */
public record PluginIcon(String fileName, String mediaType, byte[] data) {

    /**
     * @return a {@code data:} URL suitable for an {@code <img src>}
     */
    public String toDataUrl() {
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(data);
    }
}
