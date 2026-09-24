package ch.furchert.homelab.data.netmon.enrichment;

import java.time.Instant;

/**
 * One IP observed by a data set, with the Cloudflare geo/ASN attributes when known (null otherwise).
 *
 * @param ip        canonical IP literal
 * @param firstSeen earliest time the data set saw it
 * @param lastSeen  latest time the data set saw it
 */
public record Sighting(String ip, Instant firstSeen, Instant lastSeen, String country, Integer asn, String asnOrg) {
}
