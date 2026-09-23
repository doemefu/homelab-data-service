package ch.furchert.homelab.data.netmon.api;

import java.time.Instant;

/** A half-open query window {@code [from, to)}; also the {@code window} member of the summary. */
public record TimeWindow(Instant from, Instant to) {
}
