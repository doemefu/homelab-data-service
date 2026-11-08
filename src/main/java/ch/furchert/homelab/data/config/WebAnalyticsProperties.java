package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;
import java.util.List;

/**
 * {@code webanalytics.*} settings (docs/100 §11).
 *
 * @param api      write-API authorization settings
 * @param timezone day boundary of {@code daily_ingest_stats} and {@code daily_enquiries} (docs/100 §7.2)
 */
@ConfigurationProperties("webanalytics")
public record WebAnalyticsProperties(@DefaultValue Api api, @DefaultValue("Europe/Zurich") ZoneId timezone) {

    /**
     * @param writeClients token {@code sub} values allowed to call {@code POST /api/webanalytics/events}
     *                     (default {@code furchert-ch}); an empty list admits nobody
     */
    public record Api(@DefaultValue("furchert-ch") List<String> writeClients) {

        public Api {
            writeClients = writeClients == null ? List.of() : List.copyOf(writeClients);
        }
    }
}
