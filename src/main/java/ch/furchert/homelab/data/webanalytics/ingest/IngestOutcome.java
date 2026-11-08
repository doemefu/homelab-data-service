package ch.furchert.homelab.data.webanalytics.ingest;

import java.util.Map;

/**
 * Counts of one committed batch: the response counters, the enquiry increments and the reported edge drops.
 *
 * @param rejected  rejects per reason (enquiries included)
 * @param edgeDrops furchert-ch's {@code edgeDrops}, keyed by wire name
 */
public record IngestOutcome(int accepted, int duplicates, int enquiries, Map<RejectReason, Integer> rejected,
                     Map<String, Integer> edgeDrops) {

    public int rejectedTotal() {
        return rejected.values().stream().mapToInt(Integer::intValue).sum();
    }
}
