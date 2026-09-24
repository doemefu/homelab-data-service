package ch.furchert.homelab.data.netmon.ip;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC 5952 text form, as Postgres' host(inet) and Python's str(ip_address) print it. */
class IpAddressesTest {

    @ParameterizedTest
    @CsvSource({
            "203.0.113.9, 203.0.113.9",
            "::ffff:203.0.113.9, 203.0.113.9",
            "2001:DB8:0:0:0:0:0:1, 2001:db8::1",
            "2001:db8:0:0:1:0:0:1, 2001:db8::1:0:0:1",
            "2001:db8:0:1:0:0:0:1, 2001:db8:0:1::1",
            "2001:db8:1:1:1:1:1:0, 2001:db8:1:1:1:1:1:0",
            "0:0:0:0:0:0:0:0, ::",
            "fe80:0:0:0:0:0:0:1, fe80::1"})
    void compressesLikePostgres(String raw, String expected) {
        assertThat(IpAddresses.compressed(raw)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({"other", "10.42.0.0/16", "example.org"})
    void nonLiteralsAreEmpty(String raw) {
        assertThat(IpAddresses.compressed(raw)).isEmpty();
    }
}
