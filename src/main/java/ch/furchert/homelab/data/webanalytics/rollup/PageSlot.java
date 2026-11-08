package ch.furchert.homelab.data.webanalytics.rollup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One counted pageview after the 30 s reload merge (docs/100 §3.4): one or more original pageviews of the same path.
 * {@code engagedMs} is the MAX snapshot per original pageview, summed over the merged originals (null when no
 * snapshot arrived); {@code scrollMax} is the MAX, {@code sectionMask} the OR; {@code fits} is true only when every
 * snapshot reported that {@code <main>} fits the viewport. {@code startedAt} is the first original pageview's time;
 * snapshot-derived facts (section reach, form start) are ordered by it, because snapshots are sent on navigation away
 * or hide, often after the click or goal that followed (docs/100 §8.5, rev 3.5: a documented ordering limit).
 */
public record PageSlot(
        Instant startedAt,
        String path,
        String locale,
        List<UUID> pageviewIds,
        Long engagedMs,
        Integer scrollMax,
        int sectionMask,
        boolean fits,
        boolean formStarted) {

    public boolean hasEngagement() {
        return engagedMs != null;
    }
}
