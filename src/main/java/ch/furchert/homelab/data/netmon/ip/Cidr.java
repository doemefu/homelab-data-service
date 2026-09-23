package ch.furchert.homelab.data.netmon.ip;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.Optional;

/**
 * A network prefix with its host bits cleared, so the text form is always accepted by PostgreSQL's
 * {@code cidr} type (which rejects e.g. {@code 1.2.3.4/24}).
 *
 * @param network the network address (host bits zero)
 * @param prefix  prefix length
 */
public record Cidr(InetAddress network, int prefix) {

    /** Parses {@code a.b.c.d/nn}, {@code x::/nn} or a bare address (as /32 or /128); empty if invalid. */
    public static Optional<Cidr> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String text = value.strip();
        int slash = text.indexOf('/');
        Optional<InetAddress> address = IpAddresses.parse(slash < 0 ? text : text.substring(0, slash));
        if (address.isEmpty()) {
            return Optional.empty();
        }
        int bits = bits(address.get());
        int prefix = bits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(text.substring(slash + 1));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            if (prefix < 0 || prefix > bits) {
                return Optional.empty();
            }
        }
        return Optional.of(new Cidr(mask(address.get(), prefix), prefix));
    }

    public static Cidr of(String value) {
        return parse(value).orElseThrow(() -> new IllegalArgumentException("invalid CIDR: " + value));
    }

    /** True if {@code address} lies inside this prefix (same address family only). */
    public boolean contains(InetAddress address) {
        return bits(address) == bits(network) && mask(address, prefix).equals(network);
    }

    /** True if the two prefixes share at least one address, i.e. one contains the other. */
    public boolean overlaps(Cidr other) {
        if (bits(other.network) != bits(network)) {
            return false;
        }
        int shorter = Math.min(prefix, other.prefix);
        return mask(network, shorter).equals(mask(other.network, shorter));
    }

    @Override
    public String toString() {
        return network.getHostAddress() + "/" + prefix;
    }

    private static int bits(InetAddress address) {
        return address.getAddress().length * 8;
    }

    private static InetAddress mask(InetAddress address, int prefix) {
        byte[] bytes = address.getAddress();
        int total = bytes.length * 8;
        BigInteger value = new BigInteger(1, bytes);
        BigInteger hostMask = BigInteger.ONE.shiftLeft(total - prefix).subtract(BigInteger.ONE);
        BigInteger masked = value.andNot(hostMask);
        byte[] raw = masked.toByteArray();
        byte[] out = new byte[bytes.length];
        int copy = Math.min(raw.length, out.length);
        System.arraycopy(raw, raw.length - copy, out, out.length - copy, copy);
        try {
            return InetAddress.getByAddress(out);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
