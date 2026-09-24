package ch.furchert.homelab.data.config;

import ch.furchert.homelab.data.netmon.ip.Cidr;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code netmon.egress.*} (docs/060 §3.3, §4.1, §4.6).
 *
 * @param podCidr          post-NAT destinations in this range get scope {@code pod}
 * @param serviceCidr      ... scope {@code service}
 * @param lanCidr          ... scope {@code lan}; anything else except loopback is {@code external}
 * @param maxWindowsPerRun catch-up throttle: hourly windows processed per run (each costs six Prometheus queries);
 *                         the 48 h cap is 48 windows
 * @param maxRowsPerWindow row cap per window, kept by bytes sent then connects (§4.6: 2 000)
 */
@ConfigurationProperties("netmon.egress")
public record EgressProperties(
        @DefaultValue("10.42.0.0/16") String podCidr,
        @DefaultValue("10.43.0.0/16") String serviceCidr,
        @DefaultValue("192.168.1.0/24") String lanCidr,
        @DefaultValue("12") int maxWindowsPerRun,
        @DefaultValue("2000") int maxRowsPerWindow) {

    public EgressProperties {
        for (String cidr : new String[]{podCidr, serviceCidr, lanCidr}) {
            if (Cidr.parse(cidr).isEmpty()) {
                throw new IllegalArgumentException("netmon.egress CIDRs must be valid prefixes, was " + cidr);
            }
        }
        if (maxWindowsPerRun < 1) {
            throw new IllegalArgumentException("netmon.egress.max-windows-per-run must be >= 1, was " + maxWindowsPerRun);
        }
        if (maxRowsPerWindow < 1) {
            throw new IllegalArgumentException("netmon.egress.max-rows-per-window must be >= 1, was " + maxRowsPerWindow);
        }
    }

    public Cidr pod() {
        return Cidr.of(podCidr);
    }

    public Cidr service() {
        return Cidr.of(serviceCidr);
    }

    public Cidr lan() {
        return Cidr.of(lanCidr);
    }
}
