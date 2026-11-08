package ch.furchert.homelab.data.webanalytics.api;

import ch.furchert.homelab.data.netmon.collector.CollectorStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Response shapes of the web analytics read API (docs/100 §8.1, §8.2, rev 3.4); every listed field is present. */
final class ReadDtos {

    private ReadDtos() {
    }

    /** {@code from}/{@code to}/{@code previous} null and {@code days} 0 in the empty state (rev 3.4). */
    record Range(LocalDate from, LocalDate to, int days, LocalDate lastCompleteDay, Period previous) {
    }

    record Period(LocalDate from, LocalDate to) {
    }

    record Filters(Map<String, String> applied, List<String> ignored) {
    }

    // /status

    record Ingest(Instant lastEventAt, long eventsToday, Long dailyCap, Boolean capped) {
    }

    record StatusResponse(List<CollectorStatus> collectors, LocalDate lastCompleteDay, LocalDate provisionalDay,
                          LocalDate rawRetentionStart, int definitionVersion, Ingest ingest, Filters filters) {
    }

    // /overview

    record Counts(long visits, long newVisits, long returningVisits, long engagedVisits, long offerVisits,
                  long engagedOfferVisits, long contactIntentVisits, long formStartVisits, long convertingVisits,
                  long conversions, long pageviews, long excludedVisits, long enquiriesTotal) {

        static final Counts ZERO = new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    record ValueBasis(Long value, String basis) {
    }

    record Visitors(ValueBasis current, ValueBasis previous) {
    }

    record DailyPoint(LocalDate day, long visits, long offerVisits, long engagedVisits, long contactIntentVisits,
                      long convertingVisits, long pageviews, long enquiries) {
    }

    record OverviewResponse(Range range, Filters filters, Counts current, Counts previous, Visitors visitors,
                            List<DailyPoint> daily) {
    }

    // /pages, /pages/detail

    record ByLocale(long de, long en) {
    }

    record ScrollReached(long p25, long p50, long p75, long p90, long p100) {
    }

    record PageItem(String path, long pageviews, ByLocale pageviewsByLocale, long visitsViewed, long entries,
                    long exits, long landingEngagedVisits, long landingConvertingVisits, long engagementSamples,
                    long engagedMsSum, List<Long> engagedHistogram, long scrollSamples, ScrollReached scrollReached) {
    }

    record PagesResponse(Range range, Filters filters, List<PageItem> items) {
    }

    record PathCount(String path, long count) {
    }

    record DetailClick(String kind, String target, String area, long clicks, long visits, long convertingVisits) {
    }

    record SectionReached(String section, long pageviews) {
    }

    record SectionExit(String section, long visits) {
    }

    record Sections(List<SectionReached> reached, List<SectionExit> deepestAtExit) {
    }

    record FaqOpen(String faq, long opens, long visits) {
    }

    record PageDaily(LocalDate day, long pageviews, long visitsViewed) {
    }

    record PageDetailResponse(Range range, Filters filters, PageItem summary, List<PathCount> previous,
                              List<PathCount> next, List<DetailClick> clicks, Sections sections,
                              List<FaqOpen> faqOpens, List<PageDaily> daily) {
    }

    // /clicks

    record ClickItem(String kind, String target, String path, String area, long clicks, long visits,
                     long convertingVisits, Long pageVisits) {
    }

    record ClicksResponse(Range range, Filters filters, List<ClickItem> items) {
    }

    // /sources

    record ChannelItem(String channel, long visits, long newVisits, long engagedVisits, long offerVisits,
                       long convertingVisits) {
    }

    record ReferrerItem(String domain, String channel, long visits, long convertingVisits) {
    }

    record CampaignItem(String source, String medium, String campaign, long visits, long convertingVisits) {
    }

    record ChannelConversions(String channel, long convertingVisits, long conversions) {
    }

    record PathConversions(String path, long convertingVisits, long conversions) {
    }

    record Converters(List<ChannelConversions> byLandingChannel, List<ChannelConversions> byFirstChannel,
                      List<PathConversions> byEntryPath) {
    }

    record SourcesResponse(Range range, Filters filters, List<ChannelItem> channels, List<ReferrerItem> referrers,
                           List<CampaignItem> campaigns, Converters converters) {
    }
}
