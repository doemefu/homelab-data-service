package ch.furchert.homelab.data.netmon.ip;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Optional;

/**
 * Parses IP literals from untrusted input (upstream payloads, query parameters) without ever
 * resolving a hostname: {@link InetAddress#ofLiteral(String)} rejects anything that is not a literal.
 */
public final class IpAddresses {

    private IpAddresses() {
    }

    public static Optional<InetAddress> parse(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(value.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Java's text form: IPv4-mapped IPv6 becomes plain IPv4, other IPv6 is printed in full, not compressed
     * (e.g. {@code 2001:db8:0:0:0:0:0:1}). Use {@link #compressed(String)} where a value must match Postgres'
     * {@code host(inet)} or Python's {@code str(ip_address)}.
     */
    public static Optional<String> canonical(String value) {
        return parse(value).map(InetAddress::getHostAddress);
    }

    /**
     * The RFC 5952 text form, which equals Postgres' {@code host(inet)} and Python's {@code str(ip_address)}:
     * IPv4-mapped IPv6 becomes plain IPv4; IPv6 is lower case with the longest run of two or more zero groups
     * (the first on a tie) written as {@code ::}.
     */
    public static Optional<String> compressed(String value) {
        return parse(value).map(address -> address instanceof Inet6Address v6 ? compress(v6) : address.getHostAddress());
    }

    private static String compress(Inet6Address address) {
        byte[] bytes = address.getAddress();
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[2 * i] & 0xff) << 8) | (bytes[2 * i + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && groups[j] == 0) {
                j++;
            }
            if (j - i > bestLength) {
                bestStart = i;
                bestLength = j - i;
            }
            i = j;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                text.append("::");
                i += bestLength - 1;
                continue;
            }
            if (!text.isEmpty() && text.charAt(text.length() - 1) != ':') {
                text.append(':');
            }
            text.append(Integer.toHexString(groups[i]));
        }
        return text.toString();
    }
}
