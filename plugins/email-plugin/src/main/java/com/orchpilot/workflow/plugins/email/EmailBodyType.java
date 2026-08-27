package com.orchpilot.workflow.plugins.email;

import java.util.Locale;

/** Whether the body is plain text or HTML. Decides the MIME subtype the message is built with. */
public enum EmailBodyType {

    TEXT("text/plain; charset=UTF-8"),
    HTML("text/html; charset=UTF-8");

    private final String contentType;

    EmailBodyType(String contentType) {
        this.contentType = contentType;
    }

    /** @return the MIME content type, with a charset: omitting it makes non-ASCII arrive as mojibake */
    public String contentType() {
        return contentType;
    }

    /**
     * @param value a configured name
     * @return the type, defaulting to TEXT — a body meant as HTML shown as text is ugly but readable, where
     *         the reverse renders markup the author never wrote
     */
    public static EmailBodyType parse(String value) {
        if (value == null || value.isBlank()) {
            return TEXT;
        }
        return "HTML".equalsIgnoreCase(value.trim()) ? HTML : TEXT;
    }
}
