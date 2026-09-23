package ch.furchert.homelab.data.netmon.blocklist;

import ch.furchert.homelab.data.config.BlocklistProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.collector.NetmonCollector;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.netmon.ip.Cidr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * {@code blocklists} (docs/060 §4.1, §4.4): daily refresh of Spamhaus DROP v4 and FireHOL level1.
 * <ul>
 *   <li>{@code If-None-Match} with the stored ETag; a 304 or an unchanged sha256 is recorded as
 *       {@code unchanged} and keeps the current entries (and their snapshot).</li>
 *   <li>A failed fetch or a failed/empty parse is recorded as {@code failed} and keeps the previous entries.</li>
 *   <li>After any {@code applied} list, {@code ip_enrichment} rows seen within 30 d are re-matched.</li>
 * </ul>
 * One list failing does not stop the other; the run then fails with {@code upstream}.
 */
@Component
public class BlocklistCollector implements NetmonCollector {

    public static final String NAME = "blocklists";
    static final Duration REMATCH_WINDOW = Duration.ofDays(30);
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(BlocklistCollector.class);

    private final RestClient restClient;
    private final BlocklistRepository repository;
    private final IpEnrichmentService enrichment;
    private final BlocklistProperties properties;
    private final BlocklistParser parser;
    private final CollectorRunner runner;
    private final Clock clock;

    public BlocklistCollector(RestClient netmonRestClient, BlocklistRepository repository,
                              IpEnrichmentService enrichment, BlocklistProperties properties, JsonMapper jsonMapper,
                              CollectorRunner runner, Clock clock) {
        this.restClient = netmonRestClient;
        this.repository = repository;
        this.enrichment = enrichment;
        this.properties = properties;
        this.parser = new BlocklistParser(jsonMapper);
        this.runner = runner;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 5 * * *", zone = "UTC")
    public void scheduledRun() {
        runner.run(this);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration cadence() {
        return Duration.ofDays(1);
    }

    @Override
    public void collect() {
        boolean applied = false;
        List<String> failed = new ArrayList<>();
        for (BlocklistList list : BlocklistList.values()) {
            String url = url(list);
            Instant fetchedAt = clock.instant();
            try {
                applied |= refresh(list, url, fetchedAt);
            } catch (RefreshFailure e) {
                repository.recordFailed(list, fetchedAt, url, e.getMessage());
                log.warn("[{}] {} failed: {}", NAME, list.listName(), e.getMessage());
                failed.add(list.listName());
            }
        }
        if (applied) {
            int rematched = enrichment.recomputeBlocklistHits(REMATCH_WINDOW);
            log.info("[{}] re-matched ip_enrichment rows changed={}", NAME, rematched);
        }
        if (!failed.isEmpty()) {
            throw new CollectorException(ErrorCode.UPSTREAM, "refresh failed for " + String.join(", ", failed));
        }
    }

    /** @return {@code true} if the list's entries were replaced */
    private boolean refresh(BlocklistList list, String url, Instant fetchedAt) {
        Optional<BlocklistRepository.CurrentSnapshot> current = repository.current(list);
        Fetch fetch;
        try {
            fetch = restClient.get()
                    .uri(URI.create(url))
                    .headers(headers -> current.map(BlocklistRepository.CurrentSnapshot::etag)
                            .ifPresent(headers::setIfNoneMatch))
                    .exchange((request, response) -> {
                        byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
                        return new Fetch(response.getStatusCode().value(), response.getHeaders().getETag(), body);
                    });
        } catch (ResourceAccessException e) {
            throw new RefreshFailure("unreachable");
        }

        if (fetch.status() == 304 && current.isPresent()) {
            repository.recordUnchanged(list, fetchedAt, url, current.get().etag(), current.get().sha256());
            log.info("[{}] {} unchanged (304)", NAME, list.listName());
            return false;
        }
        if (fetch.status() != 200) {
            throw new RefreshFailure("HTTP " + fetch.status());
        }
        if (fetch.body().length > MAX_BODY_BYTES) {
            throw new RefreshFailure("body larger than " + MAX_BODY_BYTES + " bytes");
        }
        String sha256 = sha256(fetch.body());
        if (current.isPresent() && sha256.equals(current.get().sha256())) {
            repository.recordUnchanged(list, fetchedAt, url, fetch.etag(), sha256);
            log.info("[{}] {} unchanged (sha256)", NAME, list.listName());
            return false;
        }
        List<Cidr> entries;
        try {
            entries = parser.parse(list, fetch.body());
        } catch (BlocklistParser.BlocklistParseException e) {
            throw new RefreshFailure("parse error: " + e.getMessage());
        }
        if (entries.isEmpty()) {
            throw new RefreshFailure("no entries after filtering");
        }
        repository.apply(list, fetchedAt, url, fetch.etag(), sha256, entries);
        log.info("[{}] {} applied entries={}", NAME, list.listName(), entries.size());
        return true;
    }

    private String url(BlocklistList list) {
        return switch (list) {
            case SPAMHAUS_DROP_V4 -> properties.spamhausDropV4Url();
            case FIREHOL_LEVEL1 -> properties.fireholLevel1Url();
        };
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Fetch(int status, String etag, byte[] body) {
    }

    /** A per-list failure with a short, collector-authored reason (stored in the snapshot's {@code error}). */
    private static final class RefreshFailure extends RuntimeException {
        RefreshFailure(String message) {
            super(message);
        }
    }
}
