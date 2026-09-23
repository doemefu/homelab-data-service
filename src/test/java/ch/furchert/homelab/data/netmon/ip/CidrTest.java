package ch.furchert.homelab.data.netmon.ip;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class CidrTest {

    @Test
    void normalisesHostBitsSoPostgresAcceptsTheValue() {
        assertThat(Cidr.of("1.2.3.4/24")).hasToString("1.2.3.0/24");
        assertThat(Cidr.of("2001:db8::1/32")).hasToString("2001:db8:0:0:0:0:0:0/32");
    }

    @Test
    void bareAddressIsAHostPrefix() {
        assertThat(Cidr.of("203.0.113.7")).hasToString("203.0.113.7/32");
        assertThat(Cidr.of("::1").prefix()).isEqualTo(128);
    }

    @Test
    void rejectsGarbageAndHostnames() {
        assertThat(Cidr.parse("example.com")).isEmpty();
        assertThat(Cidr.parse("1.2.3.4/33")).isEmpty();
        assertThat(Cidr.parse("1.2.3.4/x")).isEmpty();
        assertThat(Cidr.parse("")).isEmpty();
        assertThat(Cidr.parse(null)).isEmpty();
    }

    @Test
    void containsAndOverlaps() {
        Cidr net = Cidr.of("198.51.100.0/24");
        assertThat(net.contains(InetAddress.ofLiteral("198.51.100.200"))).isTrue();
        assertThat(net.contains(InetAddress.ofLiteral("198.51.101.1"))).isFalse();
        assertThat(net.contains(InetAddress.ofLiteral("::1"))).isFalse();
        assertThat(Cidr.of("224.0.0.0/3").overlaps(Cidr.of("240.0.0.0/4"))).isTrue();
        assertThat(Cidr.of("10.1.2.0/24").overlaps(Cidr.of("10.0.0.0/8"))).isTrue();
        assertThat(Cidr.of("11.0.0.0/8").overlaps(Cidr.of("10.0.0.0/8"))).isFalse();
    }
}
