package com.orchpilot.plugin.gcp.network.validation;

import com.orchpilot.plugin.gcp.network.exception.GcpNetworkException;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates IPv4 CIDR ranges before anything is sent to GCP.
 *
 * <h2>Why check locally rather than let the API say no</h2>
 *
 * A subnet with a malformed range fails at Google with a generic {@code INVALID_ARGUMENT} that names neither
 * the field nor what was wrong with it. Checking here turns that into "10.0.0.300/24 is not a valid IPv4
 * address" before a round trip — and, more usefully, catches the two mistakes the API accepts silently or
 * reports confusingly: host bits set outside the prefix, and a prefix too large for GCP to allocate.
 *
 * <h2>What is deliberately not checked</h2>
 *
 * Overlap with existing subnets. That needs the current state of the VPC, changes between validation and
 * execution, and is exactly the check GCP performs authoritatively. Reimplementing it here would produce a
 * second opinion that is sometimes wrong, which is worse than no opinion.
 */
public final class CidrValidator {

    /**
     * GCP refuses a subnet primary range smaller than /29 — the four reserved addresses leave nothing usable.
     */
    private static final int MAX_SUBNET_PREFIX = 29;

    /** Below this a range is implausibly large for a subnet and usually a typo for a longer prefix. */
    private static final int MIN_SUBNET_PREFIX = 8;

    private CidrValidator() {
    }

    /**
     * Validates a CIDR intended as a subnet range.
     *
     * @param cidr  the range, e.g. {@code 10.10.0.0/24}
     * @param field the configuration field it came from, so the message points at the right control
     * @throws GcpNetworkException with {@code GCP_INVALID_CIDR} when it cannot be used
     */
    public static void requireSubnetRange(String cidr, String field) {
        Parsed parsed = parse(cidr, field);
        if (parsed.prefix() > MAX_SUBNET_PREFIX) {
            throw GcpNetworkException.invalidCidr(field, cidr,
                    "a subnet range cannot be smaller than /" + MAX_SUBNET_PREFIX
                            + "; GCP reserves four addresses in every subnet.");
        }
        if (parsed.prefix() < MIN_SUBNET_PREFIX) {
            throw GcpNetworkException.invalidCidr(field, cidr,
                    "/" + parsed.prefix() + " covers more than sixteen million addresses, which is almost "
                            + "always a mistyped prefix.");
        }
        requireNoHostBits(parsed, cidr, field);
    }

    /**
     * Validates a CIDR used as a match, such as a firewall source range or a route destination.
     *
     * <p>Looser than a subnet range on purpose: {@code 0.0.0.0/0} and a single {@code /32} host are both
     * legitimate here, and both are refused by the subnet rules above.
     */
    public static void requireMatchRange(String cidr, String field) {
        Parsed parsed = parse(cidr, field);
        requireNoHostBits(parsed, cidr, field);
    }

    /** Validates every entry of a list, naming the offending one. */
    public static void requireMatchRanges(List<String> cidrs, String field) {
        if (cidrs == null) {
            return;
        }
        for (String cidr : cidrs) {
            requireMatchRange(cidr, field);
        }
    }

    /**
     * @return whether the range covers the entire IPv4 space, which is what makes a firewall rule public
     */
    public static boolean isEntireInternet(String cidr) {
        if (cidr == null) {
            return false;
        }
        String trimmed = cidr.trim();
        return "0.0.0.0/0".equals(trimmed) || "::/0".equals(trimmed);
    }

    // ------------------------------------------------------------------ internals

    private record Parsed(int[] octets, int prefix) {
    }

    private static Parsed parse(String cidr, String field) {
        if (cidr == null || cidr.isBlank()) {
            throw GcpNetworkException.invalidCidr(field, cidr, "a range is required.");
        }
        String trimmed = cidr.trim();

        // IPv6 is accepted as-is: GCP validates it, and a hand-rolled IPv6 parser here would be a source of
        // wrong rejections rather than a useful check.
        if (trimmed.contains(":")) {
            if (!trimmed.contains("/")) {
                throw GcpNetworkException.invalidCidr(field, cidr, "an IPv6 range needs a prefix length.");
            }
            return new Parsed(new int[0], -1);
        }

        int slash = trimmed.indexOf('/');
        if (slash < 0) {
            throw GcpNetworkException.invalidCidr(field, cidr,
                    "a range needs a prefix length, for example " + trimmed + "/24.");
        }
        String address = trimmed.substring(0, slash);
        String prefixText = trimmed.substring(slash + 1);

        int prefix;
        try {
            prefix = Integer.parseInt(prefixText);
        } catch (NumberFormatException ex) {
            throw GcpNetworkException.invalidCidr(field, cidr, "'" + prefixText + "' is not a prefix length.");
        }
        if (prefix < 0 || prefix > 32) {
            throw GcpNetworkException.invalidCidr(field, cidr, "an IPv4 prefix must be between 0 and 32.");
        }
        return new Parsed(parseOctets(address, cidr, field), prefix);
    }

    private static int[] parseOctets(String address, String cidr, String field) {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) {
            throw GcpNetworkException.invalidCidr(field, cidr,
                    "'" + address + "' is not an IPv4 address.");
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                int octet = Integer.parseInt(parts[i]);
                if (octet < 0 || octet > 255) {
                    throw GcpNetworkException.invalidCidr(field, cidr,
                            "'" + address + "' is not a valid IPv4 address.");
                }
                octets[i] = octet;
            } catch (NumberFormatException ex) {
                throw GcpNetworkException.invalidCidr(field, cidr,
                        "'" + address + "' is not a valid IPv4 address.");
            }
        }
        return octets;
    }

    /**
     * Rejects a range whose address has bits set below its prefix.
     *
     * <p>{@code 10.0.0.5/24} is the classic one: it looks like a host address and GCP interprets it as the
     * whole {@code 10.0.0.0/24} network, so what gets created is not what was written. Saying so is more
     * useful than silently normalising it.
     */
    private static void requireNoHostBits(Parsed parsed, String cidr, String field) {
        if (parsed.octets().length == 0 || parsed.prefix() < 0) {
            return; // IPv6, left to GCP.
        }
        long address = 0;
        for (int octet : parsed.octets()) {
            address = (address << 8) | octet;
        }
        long mask = parsed.prefix() == 0 ? 0L : (0xFFFFFFFFL << (32 - parsed.prefix())) & 0xFFFFFFFFL;
        if ((address & ~mask & 0xFFFFFFFFL) != 0) {
            throw GcpNetworkException.invalidCidr(field, cidr,
                    "it has host bits set below the /" + parsed.prefix() + " prefix. Use "
                            + render(address & mask) + "/" + parsed.prefix() + " to mean the same network.");
        }
    }

    private static String render(long address) {
        List<String> parts = new ArrayList<>(4);
        for (int shift = 24; shift >= 0; shift -= 8) {
            parts.add(String.valueOf((address >> shift) & 0xFF));
        }
        return String.join(".", parts);
    }
}
