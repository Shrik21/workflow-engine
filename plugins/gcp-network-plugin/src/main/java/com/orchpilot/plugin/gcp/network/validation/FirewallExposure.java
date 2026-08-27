package com.orchpilot.plugin.gcp.network.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reports firewall rules that expose administrative access to the whole internet.
 *
 * <h2>Report, never silently block</h2>
 *
 * The specification is explicit, and it is right: a rule opening SSH to {@code 0.0.0.0/0} is sometimes exactly
 * what an operator intends — a bastion host, a lab, a deliberately public jump box. Refusing it outright would
 * make the plugin useless for those and teach people to work around it.
 *
 * <p>So this returns findings. The node turns a finding into the platform's existing confirmation gate: the
 * operation proceeds when someone has said so explicitly, and the finding is recorded in the audit trail either
 * way. That is a decision with a name against it, which is what a review actually needs.
 *
 * <h2>What counts as exposed</h2>
 *
 * An <em>ingress</em> <em>allow</em> rule whose source is the entire internet and whose ports include one of the
 * administrative ports. All three must hold. An egress rule to {@code 0.0.0.0/0} is ordinary outbound access, a
 * deny rule to the internet is a control rather than a hole, and an ingress allow on port 443 is a web server.
 */
public final class FirewallExposure {

    /** Ports whose exposure to the internet is a finding, and what each one is. */
    private static final Map<Integer, String> ADMINISTRATIVE_PORTS = Map.of(
            22, "SSH",
            3389, "RDP",
            5900, "VNC",
            23, "Telnet",
            3306, "MySQL",
            5432, "PostgreSQL",
            27017, "MongoDB",
            6379, "Redis",
            9200, "Elasticsearch",
            1433, "SQL Server");

    /** One exposure, in terms an operator can act on. */
    public record Finding(String port, String service, String sourceRange, String message) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("port", port);
            map.put("service", service);
            map.put("sourceRange", sourceRange);
            map.put("message", message);
            return map;
        }
    }

    private FirewallExposure() {
    }

    /**
     * Assesses a rule.
     *
     * @param direction    {@code INGRESS} or {@code EGRESS}
     * @param action       {@code ALLOW} or {@code DENY}
     * @param sourceRanges the rule's source ranges
     * @param allowed      the allow entries, each {@code {protocol, ports[]}}
     * @return every exposure found; empty when the rule opens nothing administrative to the internet
     */
    public static List<Finding> assess(String direction, String action, List<String> sourceRanges,
                                       List<Map<String, Object>> allowed) {
        List<Finding> findings = new ArrayList<>();

        // Egress to the internet is ordinary outbound traffic, and a deny rule is a control rather than a hole.
        if (!"INGRESS".equalsIgnoreCase(orDefault(direction, "INGRESS"))
                || !"ALLOW".equalsIgnoreCase(orDefault(action, "ALLOW"))) {
            return findings;
        }
        String publicRange = firstPublicRange(sourceRanges);
        if (publicRange == null) {
            return findings;
        }
        if (allowed == null || allowed.isEmpty()) {
            return findings;
        }

        for (Map<String, Object> entry : allowed) {
            String protocol = orDefault(text(entry.get("IPProtocol")), "").toLowerCase(Locale.ROOT);
            // "all" opens every port on every protocol, which includes all of them at once.
            if ("all".equals(protocol)) {
                findings.add(new Finding("all", "every service", publicRange,
                        "This rule allows every protocol and port from the entire internet."));
                continue;
            }
            if (!"tcp".equals(protocol) && !"udp".equals(protocol)) {
                continue;
            }
            Object ports = entry.get("ports");
            if (!(ports instanceof List<?> list) || list.isEmpty()) {
                // No ports on a tcp/udp entry means every port of that protocol.
                findings.add(new Finding("all", "every " + protocol + " service", publicRange,
                        "This rule allows every " + protocol + " port from the entire internet."));
                continue;
            }
            for (Object port : list) {
                findings.addAll(assessPort(String.valueOf(port), publicRange));
            }
        }
        return findings;
    }

    /**
     * Assesses one port entry, which GCP allows to be a single port or a {@code from-to} range.
     *
     * <p>A range is checked against every administrative port it contains, because {@code 1-65535} is a common
     * way to write "everything" and reporting only its endpoints would miss the point entirely.
     */
    private static List<Finding> assessPort(String port, String sourceRange) {
        List<Finding> findings = new ArrayList<>();
        String trimmed = port.trim();
        int dash = trimmed.indexOf('-');

        if (dash < 0) {
            Integer single = parse(trimmed);
            if (single != null && ADMINISTRATIVE_PORTS.containsKey(single)) {
                findings.add(finding(trimmed, ADMINISTRATIVE_PORTS.get(single), sourceRange));
            }
            return findings;
        }

        Integer from = parse(trimmed.substring(0, dash));
        Integer to = parse(trimmed.substring(dash + 1));
        if (from == null || to == null || from > to) {
            return findings;
        }
        for (Map.Entry<Integer, String> administrative : ADMINISTRATIVE_PORTS.entrySet()) {
            int candidate = administrative.getKey();
            if (candidate >= from && candidate <= to) {
                findings.add(finding(candidate + " (in range " + trimmed + ")",
                        administrative.getValue(), sourceRange));
            }
        }
        return findings;
    }

    private static Finding finding(String port, String service, String sourceRange) {
        return new Finding(port, service, sourceRange,
                "This rule exposes " + service + " on port " + port + " to " + sourceRange
                        + ", which is the entire internet.");
    }

    private static String firstPublicRange(List<String> sourceRanges) {
        if (sourceRanges == null) {
            return null;
        }
        for (String range : sourceRanges) {
            if (CidrValidator.isEntireInternet(range)) {
                return range.trim();
            }
        }
        return null;
    }

    private static Integer parse(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
