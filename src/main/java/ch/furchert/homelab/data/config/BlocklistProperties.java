package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code netmon.blocklists.*} (docs/060 §4.4). Both lists are public; data-service is the only fetcher.
 *
 * @param spamhausDropV4Url Spamhaus DROP v4, NDJSON
 * @param fireholLevel1Url  FireHOL level1 netset
 */
@ConfigurationProperties("netmon.blocklists")
public record BlocklistProperties(
        @DefaultValue("https://www.spamhaus.org/drop/drop_v4.json") String spamhausDropV4Url,
        @DefaultValue("https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level1.netset")
        String fireholLevel1Url) {
}
