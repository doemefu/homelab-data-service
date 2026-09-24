package ch.furchert.homelab.data.netmon.ip;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PublicIpFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.1", "172.16.5.4", "172.31.255.255", "192.168.1.50", "100.64.0.1", "127.0.0.1",
            "169.254.1.1", "224.0.0.251", "239.255.255.250", "240.0.0.1", "255.255.255.255", "0.0.0.0", "::1",
            "fd00::1", "fe80::1", "::ffff:10.0.0.1", "not-an-ip"})
    void nonPublic(String ip) {
        assertThat(PublicIpFilter.isPublic(ip)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.7", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1", "2a02:1210::1"})
    void isPublic(String ip) {
        assertThat(PublicIpFilter.isPublic(ip)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0/8", "10.0.0.0/8", "224.0.0.0/3", "192.168.0.0/16", "192.0.0.0/2", "fc00::/7"})
    void overlapsNonPublic(String cidr) {
        assertThat(PublicIpFilter.overlapsNonPublic(Cidr.of(cidr))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.10.16.0/20", "223.254.0.0/16", "2001:db8::/32"})
    void publicRanges(String cidr) {
        assertThat(PublicIpFilter.overlapsNonPublic(Cidr.of(cidr))).isFalse();
    }
}
