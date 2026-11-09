package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * {@code webanalytics.*} settings (docs/100 §11).
 *
 * @param api      write-API authorization settings
 * @param timezone day boundary of {@code daily_ingest_stats} and {@code daily_enquiries} (docs/100 §7.2)
 * @param coverage  consent-coverage settings of {@code /api/webanalytics/coverage} (docs/100 §8.2, rev 3.5)
 * @param ingest    WA-5 ingest caps (docs/100 §6.4)
 * @param retention WA-5 raw-event retention (docs/100 §7.7)
 */
@ConfigurationProperties("webanalytics")
public record WebAnalyticsProperties(@DefaultValue Api api, @DefaultValue("Europe/Zurich") ZoneId timezone,
                                     @DefaultValue Coverage coverage, @DefaultValue Ingest ingest,
                                     @DefaultValue Retention retention) {

    @ConstructorBinding
    public WebAnalyticsProperties {
    }

    /** With the shipped ingest caps and retention (for code and tests that do not care about WA-5). */
    public WebAnalyticsProperties(Api api, ZoneId timezone, Coverage coverage) {
        this(api, timezone, coverage, new Ingest(Ingest.DEFAULT_DAILY_CAP, Ingest.DEFAULT_HOURLY_CAP,
                Ingest.DEFAULT_VISITOR_DAILY_CAP), new Retention(Retention.DEFAULT_EVENTS_DAYS));
    }

    /**
     * Caps on accepted analytics events (pageview, engagement, click; goal and enquiry are exempt). 0 is allowed and
     * caps every batch that carries analytics events (data-side stop; goal, enquiry and erasure keep working).
     *
     * @param dailyCap        accepted analytics events per UTC day of {@code received_at}
     * @param hourlyCap       accepted analytics events per UTC hour of {@code received_at}
     * @param visitorDailyCap accepted analytics events per visitor id and UTC day; the excess is rejected
     *                        ({@code visitor_cap})
     */
    public record Ingest(@DefaultValue("10000") int dailyCap, @DefaultValue("2500") int hourlyCap,
                         @DefaultValue("500") int visitorDailyCap) {

        public static final int DEFAULT_DAILY_CAP = 10_000;
        public static final int DEFAULT_HOURLY_CAP = 2_500;
        public static final int DEFAULT_VISITOR_DAILY_CAP = 500;

        public Ingest {
            if (dailyCap < 0 || hourlyCap < 0 || visitorDailyCap < 0) {
                throw new IllegalArgumentException(
                        "webanalytics.ingest.daily-cap, hourly-cap and visitor-daily-cap must be >= 0");
            }
        }
    }

    /** @param eventsDays {@code webanalytics.events} rows older than this many days are deleted; at least 370 */
    public record Retention(@DefaultValue("396") int eventsDays) {

        public static final int DEFAULT_EVENTS_DAYS = 396;
        public static final int MIN_EVENTS_DAYS = 370;

        public Retention {
            if (eventsDays < MIN_EVENTS_DAYS) {
                throw new IllegalArgumentException("webanalytics.retention.events-days must be >= " + MIN_EVENTS_DAYS
                        + ", was " + eventsDays);
            }
        }
    }

    /**
     * @param goLiveDate first day of tracked traffic ({@code baselineFrom}); unset until WA-6, and while unset
     *                   {@code /coverage} compares nothing
     */
    public record Coverage(LocalDate goLiveDate) {
    }

    /**
     * @param writeClients token {@code sub} values allowed to call {@code POST /api/webanalytics/events}
     *                     (default {@code furchert-ch}); an empty list admits nobody
     * @param readClients  token {@code sub} values allowed to call the read API {@code GET /api/webanalytics/*}
     *                     (default {@code furchert-ch}); an empty list admits nobody
     */
    public record Api(@DefaultValue("furchert-ch") List<String> writeClients,
                      @DefaultValue("furchert-ch") List<String> readClients) {

        public Api {
            writeClients = writeClients == null ? List.of() : List.copyOf(writeClients);
            readClients = readClients == null ? List.of() : List.copyOf(readClients);
        }
    }
}
