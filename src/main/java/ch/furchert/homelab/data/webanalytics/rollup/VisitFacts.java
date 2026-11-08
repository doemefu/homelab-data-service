package ch.furchert.homelab.data.webanalytics.rollup;

/**
 * A visit plus the facts that need raw history: new vs returning and the visitor's first-known channel
 * ({@code ''} when no landing pageview is known, docs/100 §7.5).
 */
public record VisitFacts(Visit visit, boolean isNew, String channel, String firstChannel) {
}
