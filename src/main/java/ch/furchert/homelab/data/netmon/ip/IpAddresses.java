package ch.furchert.homelab.data.netmon.ip;

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

    /** The canonical text form (IPv6 compressed as Java prints it; IPv4-mapped IPv6 becomes IPv4). */
    public static Optional<String> canonical(String value) {
        return parse(value).map(InetAddress::getHostAddress);
    }
}
