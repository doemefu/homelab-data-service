package ch.furchert.homelab.data.webanalytics.rollup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Rows of the WA-3a aggregate tables (V7, docs/100 §7.8); key columns use {@code ''} for "none". */
public final class RollupRows {

    private RollupRows() {
    }

    public record VisitsRow(String entryLocale, String deviceClass, int visits, int newVisits, int returningVisits,
                            int engagedVisits, int offerVisits, int engagedOfferVisits, int contactIntentVisits,
                            int formStartVisits, int convertingVisits, int conversions, int pageviews,
                            int excludedVisits) {
    }

    public record VisitorsRow(int visitors, int newVisitors, int returningVisitors, int visitors7d, int visitors28d,
                              int repeatOfferViewers28d) {
    }

    public record MonthlyRow(LocalDate month, int visitors, int newVisitors, int returningVisitors,
                             int repeatOfferViewers) {
    }

    /** {@code histogram} has the eight buckets {@code eng_h0..eng_h7}; {@code scroll} the five thresholds 25..100. */
    public record PageRow(String path, String entryLocale, String deviceClass, int pageviews, int pageviewsDe,
                          int pageviewsEn, int visitsViewed, int entries, int exits, int landingEngagedVisits,
                          int landingConvertingVisits, int engagementSamples, long engagedMsSum, int[] histogram,
                          int scrollSamples, int[] scroll) {
    }

    public record SectionRow(String entryLocale, String deviceClass, String section, int reachedPageviews,
                             int deepestAtExitVisits) {
    }

    public record ClickRow(String path, String entryLocale, String deviceClass, String kind, String target,
                           String area, int clicks, int visits, int convertingVisits) {
    }

    public record SourceRow(String entryLocale, String deviceClass, String channel, String referrerDomain,
                            String utmSource, String utmMedium, String utmCampaign, int visits, int newVisits,
                            int engagedVisits, int offerVisits, int convertingVisits) {
    }

    public record ConversionRow(String entryLocale, String deviceClass, String entryPath, String channel,
                                String firstChannel, int convertingVisits, int conversions) {
    }

    public record RollupDayRow(LocalDate day, int rawEvents, int rawPageviews, int visits, int orphanVisits,
                               int definitionVersion, boolean fin, Instant rolledUpAt) {
    }

    public record TransitionRow(String entryLocale, String deviceClass, String fromPath, String toPath,
                                int transitions) {
    }

    public record FunnelRow(String funnelId, int step, String entryLocale, String deviceClass, int visits) {
    }

    public record GroupPathRow(String pathKey, int visits, int convertingVisits) {
    }

    public record EntryExitRow(String entryPath, String exitPath, int visits) {
    }

    public record IntentSeenRow(String seen, int visits) {
    }

    /** Visits and engaged visits; no conversions per audience attribute (docs/100 §7.8, rev 3.5). */
    public record DimensionRow(String dimension, String value, int visits, int engagedVisits) {
    }

    /**
     * Everything the visit model writes for one day except {@code daily_visitors} and {@code rollup_day}; WA-3b adds
     * the journey and audience tables.
     */
    public record DayRows(List<VisitsRow> visits, List<PageRow> pages, List<SectionRow> sections,
                          List<ClickRow> clicks, List<SourceRow> sources, List<ConversionRow> conversions,
                          List<TransitionRow> transitions, List<FunnelRow> funnels, List<GroupPathRow> groupPaths,
                          List<EntryExitRow> entryExits, List<IntentSeenRow> intentSeen,
                          List<DimensionRow> dimensions) {
    }
}
