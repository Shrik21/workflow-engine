package com.orchpilot.pluginserver.security;

import com.orchpilot.pluginserver.exception.ApiError;

/**
 * Serialises an {@link ApiError} by hand, for the two places that write a response outside the MVC stack.
 *
 * <h2>Why not the configured object mapper</h2>
 *
 * <p>Security responders run in the filter chain, before content negotiation, and Spring Boot 4 ships Jackson 3.
 * Injecting a mapper here would tie two small classes to a JSON library that this service otherwise never names
 * directly, for four fields and a list that is almost always empty.
 *
 * <p>The trade is that the escaping is ours. It is deliberately conservative: control characters are escaped
 * rather than passed through, because one of these fields is a request path an attacker chooses.
 */
final class ErrorBodyWriter {

    private ErrorBodyWriter() {
    }

    static String write(ApiError error) {
        StringBuilder json = new StringBuilder(160);
        json.append('{');
        field(json, "code", error.code()).append(',');
        field(json, "message", error.message()).append(',');
        json.append("\"details\":[");
        for (int index = 0; index < error.details().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(escape(error.details().get(index))).append('"');
        }
        json.append("],");
        field(json, "path", error.path()).append(',');
        field(json, "at", String.valueOf(error.at()));
        return json.append('}').toString();
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        return json.append('"').append(name).append("\":\"").append(escape(value)).append('"');
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
