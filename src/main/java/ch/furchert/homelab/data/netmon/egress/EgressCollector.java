package ch.furchert.homelab.data.netmon.egress;

import ch.furchert.homelab.data.config.EgressProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorState;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.collector.CollectorWarning;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.collector.NetmonCollector;
import ch.furchert.homelab.data.netmon.egress.EgressLabels.Endpoint;
import ch.furchert.homelab.data.netmon.egress.EgressLabels.Identity;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.netmon.enrichment.Sighting;
import ch.furchert.homelab.data.netmon.ip.Cidr;
import ch.furchert.homelab.data.netmon.ip.IpAddresses;
import ch.furchert.homelab.data.netmon.prometheus.PrometheusClient;
import ch.furchert.homelab.data.netmon.prometheus.PrometheusSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * {@code egress} (docs/060 §4.1, §4.6): snapshots the coroot-node-agent TCP counters from Prometheus into
 * {@code egress_flow_snapshots}, one completed hour {@code [T-1h, T)} at a time, evaluated at {@code time = T},
 * oldest first from the high-water mark, capped at 48 h and at {@code netmon.egress.max-windows-per-run} windows.
 *
 * <p>Per window the row set is the union of the keys {@code (node, container_id, destination, actual_destination)}
 * of the bytes, connects and presence queries. Failed connects carry no {@code actual_destination}; they are added
 * to the one row with the same {@code (node, container_id, destination)} when exactly one exists, otherwise they
 * form their own row with {@code actual_destination = ''}. The FQDN comes from {@code ip_to_fqdn} (the
 * lexicographically first name of the post-NAT IP) or, for a destination coroot groups by name, is that name.
 * Rows beyond {@code netmon.egress.max-rows-per-window} are dropped by bytes sent, then connects, and the run
 * reports {@code truncated}. Each window is replaced as a whole.
 *
 * <p>No agent publishing at the latest evaluation point (the DaemonSet is gated off or not scraped) is a successful
 * run with an {@code upstream} warning, like {@code lan}; the mark advances over the empty windows. Logs carry
 * counts only, never label values.
 */
@Component
public class EgressCollector implements NetmonCollector {

    public static final String NAME = "egress";
    static final Duration WINDOW = Duration.ofHours(1);
    /** The cron fires at :07; windows end at least this long before "now" so the last scrape has landed. */
    static final Duration EVALUATION_DELAY = Duration.ofMinutes(5);
    static final Duration CATCH_UP = Duration.ofHours(48);
    static final String NO_AGENTS = "no node exposes the coroot egress metrics";
    static final String TRUNCATED = " window(s) exceeded netmon.egress.max-rows-per-window";

    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,62}");
    private static final Logger log = LoggerFactory.getLogger(EgressCollector.class);

    private final PrometheusClient prometheus;
    private final EgressSnapshotRepository repository;
    private final IpEnrichmentService enrichment;
    private final CollectorStateRepository state;
    private final EgressProperties properties;
    private final CollectorRunner runner;
    private final Clock clock;
    private final Cidr podCidr;
    private final Cidr serviceCidr;
    private final Cidr lanCidr;

    public EgressCollector(PrometheusClient prometheus, EgressSnapshotRepository repository,
                           IpEnrichmentService enrichment, CollectorStateRepository state, EgressProperties properties,
                           CollectorRunner runner, Clock clock) {
        this.prometheus = prometheus;
        this.repository = repository;
        this.enrichment = enrichment;
        this.state = state;
        this.properties = properties;
        this.runner = runner;
        this.clock = clock;
        this.podCidr = properties.pod();
        this.serviceCidr = properties.service();
        this.lanCidr = properties.lan();
    }

    /** Hourly at :07 UTC. */
    @Scheduled(cron = "0 7 * * * *", zone = "UTC")
    public void scheduledRun() {
        runner.run(this);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration cadence() {
        return WINDOW;
    }

    @Override
    public void collect() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant latestEnd = alignDown(now.minus(EVALUATION_DELAY));
        Instant floor = latestEnd.minus(CATCH_UP);
        Instant mark = state.find(NAME).map(CollectorState::lastWindowEnd).orElse(null);
        if (mark == null) {
            mark = floor;
        } else {
            mark = alignDown(mark);
            if (mark.isBefore(floor)) {
                log.info("[{}] gap skipped from={} to={}", NAME, mark, floor);
                state.updateWindowEnd(NAME, floor);
                mark = floor;
            }
        }

        int processed = 0;
        int truncated = 0;
        for (Instant end = mark.plus(WINDOW); !end.isAfter(latestEnd); end = end.plus(WINDOW)) {
            if (processed == properties.maxWindowsPerRun()) {
                log.info("[{}] catch-up throttled at {} windows; continuing next run", NAME, processed);
                break;
            }
            processed++;
            if (collectWindow(end)) {
                truncated++;
            }
            state.updateWindowEnd(NAME, end);
        }
        if (processed == 0) {
            return;
        }
        // Judged at the latest evaluation point, so catch-up windows from before the rollout cannot warn.
        if (prometheus.query(EgressQueries.AGENTS, latestEnd).stream().map(s -> s.label("node"))
                .noneMatch(node -> node != null && NODE.matcher(node).matches())) {
            throw new CollectorWarning(ErrorCode.UPSTREAM, NO_AGENTS);
        }
        if (truncated > 0) {
            throw new CollectorWarning(ErrorCode.TRUNCATED, truncated + TRUNCATED);
        }
    }

    /** Collects and replaces {@code [end-1h, end)}; {@code true} if rows were dropped by the row cap. */
    private boolean collectWindow(Instant end) {
        Instant start = end.minus(WINDOW);
        Map<Key, Counters> flows = new LinkedHashMap<>();
        Dropped dropped = new Dropped();
        add(prometheus.query(EgressQueries.BYTES_SENT, end), flows, dropped, (c, v) -> c.bytesSent += v);
        add(prometheus.query(EgressQueries.BYTES_RECEIVED, end), flows, dropped, (c, v) -> c.bytesReceived += v);
        add(prometheus.query(EgressQueries.CONNECTS, end), flows, dropped, (c, v) -> c.connects += v);
        List<PrometheusSample> failed = prometheus.query(EgressQueries.FAILED_CONNECTS, end);
        Map<String, String> fqdns = fqdns(prometheus.query(EgressQueries.FQDN, end));
        add(prometheus.query(EgressQueries.PRESENCE, end), flows, dropped, (c, v) -> { });
        addFailed(failed, flows, dropped);

        List<EgressFlow> rows = new ArrayList<>(flows.size());
        flows.forEach((key, counters) -> rows.add(row(key, counters, fqdns)));
        rows.sort(Comparator.comparingLong(EgressFlow::bytesSent).reversed()
                .thenComparing(Comparator.comparingLong(EgressFlow::connects).reversed())
                .thenComparing(EgressFlow::node)
                .thenComparing(EgressFlow::containerId)
                .thenComparing(EgressFlow::destination)
                .thenComparing(EgressFlow::actualDestination));
        boolean truncated = rows.size() > properties.maxRowsPerWindow();
        List<EgressFlow> kept = truncated ? rows.subList(0, properties.maxRowsPerWindow()) : rows;
        repository.replace(start, kept);

        List<Sighting> sightings = new ArrayList<>();
        for (EgressFlow flow : kept) {
            if ("external".equals(flow.scope()) && flow.target().ip() != null) {
                sightings.add(new Sighting(flow.target().ip(), start, end, null, null, null));
            }
        }
        enrichment.record("egress", EgressSnapshotRepository.SOURCE, sightings);
        if (dropped.count > 0) {
            log.warn("[{}] window end={} dropped {} series with unexpected labels or values", NAME, end, dropped.count);
        }
        if (truncated) {
            log.warn("[{}] window end={} kept {} of {} rows", NAME, end, kept.size(), rows.size());
        }
        return truncated;
    }

    /** Merges one flow query into {@code flows}; the value is added to the counter chosen by {@code into}. */
    private void add(List<PrometheusSample> samples, Map<Key, Counters> flows, Dropped dropped,
                     BiConsumer<Counters, Long> into) {
        for (PrometheusSample sample : samples) {
            Key key = key(sample, label(sample, "actual_destination"));
            Long value = count(sample.value());
            if (key == null || value == null) {
                dropped.count++;
                continue;
            }
            into.accept(flows.computeIfAbsent(key, k -> new Counters()), value);
        }
    }

    /** Failed connects join on {@code destination} only, and only when that match is unambiguous. */
    private void addFailed(List<PrometheusSample> samples, Map<Key, Counters> flows, Dropped dropped) {
        Map<Key, List<Key>> byDestination = new HashMap<>();
        for (Key key : flows.keySet()) {
            byDestination.computeIfAbsent(key.withoutActual(), k -> new ArrayList<>()).add(key);
        }
        for (PrometheusSample sample : samples) {
            Key key = key(sample, "");
            Long value = count(sample.value());
            if (key == null || value == null) {
                dropped.count++;
                continue;
            }
            List<Key> matches = byDestination.getOrDefault(key, List.of());
            Key target = matches.size() == 1 ? matches.get(0) : key;
            flows.computeIfAbsent(target, k -> new Counters()).failedConnects += value;
        }
    }

    private EgressFlow row(Key key, Counters counters, Map<String, String> fqdns) {
        String fqdn = key.target().name() != null ? key.target().name() : fqdns.get(key.target().ip());
        String scope = EgressLabels.scope(key.target(), podCidr, serviceCidr, lanCidr);
        return new EgressFlow(key.node(), key.containerId(), key.identity(), key.destination(), key.actualDestination(),
                key.target(), scope, fqdn, counters.bytesSent, counters.bytesReceived, counters.connects,
                counters.failedConnects);
    }

    /** A validated flow key, or null when a label is missing or outside the expected shapes. */
    private static Key key(PrometheusSample sample, String actualDestination) {
        String node = sample.label("node");
        String containerId = sample.label("container_id");
        String destination = sample.label("destination");
        if (node == null || !NODE.matcher(node).matches() || destination == null) {
            return null;
        }
        Optional<Identity> identity = EgressLabels.identity(containerId);
        Optional<Endpoint> target = EgressLabels.target(destination, actualDestination);
        if (identity.isEmpty() || target.isEmpty() || EgressLabels.endpoint(destination).isEmpty()) {
            return null;
        }
        return new Key(node, containerId, destination, actualDestination, identity.get(), target.get());
    }

    /** {@code ip -> first name}; IPs in RFC 5952 form so they match the parsed destinations. */
    private static Map<String, String> fqdns(List<PrometheusSample> samples) {
        Map<String, TreeSet<String>> names = new HashMap<>();
        for (PrometheusSample sample : samples) {
            Optional<String> ip = IpAddresses.compressed(sample.label("ip"));
            Optional<String> fqdn = EgressLabels.fqdn(sample.label("fqdn"));
            if (ip.isPresent() && fqdn.isPresent()) {
                names.computeIfAbsent(ip.get(), k -> new TreeSet<>()).add(fqdn.get());
            }
        }
        Map<String, String> first = new HashMap<>();
        names.forEach((ip, set) -> first.put(ip, set.first()));
        return first;
    }

    private static String label(PrometheusSample sample, String name) {
        String value = sample.label(name);
        return value == null ? "" : value;
    }

    /** {@code increase()} results are non-negative doubles; rounded, anything else rejects the series. */
    private static Long count(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
            return null;
        }
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(value);
    }

    /** The latest full hour (UTC) at or before {@code instant}. */
    static Instant alignDown(Instant instant) {
        return instant.truncatedTo(ChronoUnit.HOURS);
    }

    private record Key(String node, String containerId, String destination, String actualDestination,
                       Identity identity, Endpoint target) {

        /** Same flow without the post-NAT part: what a failed-connect series can be matched on. */
        Key withoutActual() {
            return new Key(node, containerId, destination, "", identity, EgressLabels.target(destination, "").orElseThrow());
        }
    }

    private static final class Counters {
        long bytesSent;
        long bytesReceived;
        long connects;
        long failedConnects;
    }

    private static final class Dropped {
        int count;
    }
}
