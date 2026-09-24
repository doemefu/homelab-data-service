package ch.furchert.homelab.data.netmon.lan;

import ch.furchert.homelab.data.config.LanProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorState;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.collector.CollectorWarning;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.collector.NetmonCollector;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.netmon.enrichment.Sighting;
import ch.furchert.homelab.data.netmon.ip.IpAddresses;
import ch.furchert.homelab.data.netmon.lan.LanRows.Connection;
import ch.furchert.homelab.data.netmon.lan.LanRows.SshAuth;
import ch.furchert.homelab.data.netmon.lan.LanRows.UfwBlock;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * {@code lan} (docs/060 §4.1, §4.6): snapshots the NM-3 node-script metrics from Prometheus into
 * {@code lan_connection_snapshots}, {@code ufw_block_snapshots} and {@code ssh_auth_snapshots}, one
 * completed 15-minute window {@code [T-15m, T)} at a time, oldest first from the high-water mark
 * ({@code last_window_end}), capped at 48 h and at {@code netmon.lan.max-windows-per-run} windows per run.
 *
 * <p>Per window: connections are evaluated at {@code T}; node discovery and the guarded bucket queries at
 * {@code T+4m}. A node exposing {@code homelab_netmon_last_success_timestamp_seconds} is <i>expected</i>; it is
 * <i>published</i> when its {@code homelab_netmon_bucket_end_timestamp_seconds} equals {@code T}. Every node with
 * data gets its {@code (window_start, node)} slice replaced; a published node without bucket series gets 0
 * bucket rows. An expected node that has not published is re-checked once at {@code T+12m} (the bucket is still
 * visible then; a re-evaluation at the same {@code T+4m} could never change the answer). After that the window
 * is final: the mark moves past it and a node that never published gets no bucket rows for it.
 *
 * <p>With no node exposing the metrics (the node role is not rolled out yet) a run still succeeds, but reports
 * {@code upstream} as a warning in {@code /status}. Logs carry node names and counts, never label values.
 */
@Component
public class LanCollector implements NetmonCollector {

    public static final String NAME = "lan";
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration FIRST_EVALUATION = Duration.ofMinutes(4);
    static final Duration RETRY_EVALUATION = Duration.ofMinutes(12);
    static final Duration CATCH_UP = Duration.ofHours(48);
    static final String NO_NODES = "no node exposes the homelab_netmon metrics";

    static final Set<String> PROTOS = Set.of("TCP", "UDP", "ICMP", "OTHER");
    static final Set<String> OUTCOMES = Set.of("accepted", "failed", "invalid_user");
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,62}");
    private static final Pattern STATE = Pattern.compile("[A-Z_]{1,32}");
    private static final Pattern SRC_IP = Pattern.compile("[0-9A-Za-z.:/]{1,64}");

    private static final Logger log = LoggerFactory.getLogger(LanCollector.class);

    private final PrometheusClient prometheus;
    private final LanSnapshotRepository repository;
    private final IpEnrichmentService enrichment;
    private final CollectorStateRepository state;
    private final LanProperties properties;
    private final CollectorRunner runner;
    private final Clock clock;

    public LanCollector(PrometheusClient prometheus, LanSnapshotRepository repository, IpEnrichmentService enrichment,
                        CollectorStateRepository state, LanProperties properties, CollectorRunner runner, Clock clock) {
        this.prometheus = prometheus;
        this.repository = repository;
        this.enrichment = enrichment;
        this.state = state;
        this.properties = properties;
        this.runner = runner;
        this.clock = clock;
    }

    /** :04, :19, :34, :49 — four minutes after each bucket closes, so the node scripts have published it. */
    @Scheduled(cron = "0 4,19,34,49 * * * *", zone = "UTC")
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
        Instant latestEnd = alignDown(now.minus(FIRST_EVALUATION));
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
        Boolean newestHadNodes = null;
        for (Instant end = mark.plus(WINDOW); !end.isAfter(latestEnd); end = end.plus(WINDOW)) {
            if (processed == properties.maxWindowsPerRun()) {
                log.info("[{}] catch-up throttled at {} windows; continuing next run", NAME, processed);
                break;
            }
            processed++;
            WindowResult result = collectWindow(end, now);
            newestHadNodes = result.expectedNodes() > 0;
            if (!result.isFinal()) {
                break;
            }
            state.updateWindowEnd(NAME, end);
        }
        if (Boolean.FALSE.equals(newestHadNodes)) {
            throw new CollectorWarning(ErrorCode.UPSTREAM, NO_NODES);
        }
    }

    /** Collects the window {@code [end-15m, end)}; see the class comment for the evaluation times. */
    private WindowResult collectWindow(Instant end, Instant now) {
        Instant start = end.minus(WINDOW);
        Instant firstEvaluation = end.plus(FIRST_EVALUATION);
        Set<String> expected = nodes(prometheus.query(LanQueries.EXPECTED_NODES, firstEvaluation));
        Set<String> published = publishedNodes(end, firstEvaluation);
        Map<String, List<Connection>> connections = connections(prometheus.query(LanQueries.CONNECTIONS, end));

        Set<String> written = new TreeSet<>(connections.keySet());
        written.addAll(published);
        write(start, end, written, published, connections, firstEvaluation);

        Set<String> pending = new TreeSet<>(expected);
        pending.removeAll(published);
        if (pending.isEmpty()) {
            return new WindowResult(true, expected.size());
        }
        Instant retry = end.plus(RETRY_EVALUATION);
        if (now.isBefore(retry)) {
            log.info("[{}] window end={} waiting for nodes={}", NAME, end, pending);
            return new WindowResult(false, expected.size());
        }
        Set<String> late = publishedNodes(end, retry);
        late.retainAll(pending);
        write(start, end, late, late, connections, retry);
        pending.removeAll(late);
        if (!pending.isEmpty()) {
            log.warn("[{}] window end={} not published by nodes={}; skipped for them", NAME, end, pending);
        }
        return new WindowResult(true, expected.size());
    }

    private Set<String> publishedNodes(Instant end, Instant evaluation) {
        Set<String> published = new TreeSet<>();
        for (PrometheusSample sample : prometheus.query(LanQueries.BUCKET_ENDS, evaluation)) {
            String node = sample.label("node");
            if (node != null && NODE.matcher(node).matches() && sample.value() == end.getEpochSecond()) {
                published.add(node);
            }
        }
        return published;
    }

    /**
     * Replaces the slices of {@code nodes}; the bucket tables only for the {@code published} ones, whose
     * bucket series are evaluated at {@code evaluation} (an absent series means 0 rows).
     */
    private void write(Instant start, Instant end, Set<String> nodes, Set<String> published,
                       Map<String, List<Connection>> connections, Instant evaluation) {
        if (nodes.isEmpty()) {
            return;
        }
        Map<String, List<UfwBlock>> ufw = Map.of();
        Map<String, List<SshAuth>> ssh = Map.of();
        if (!published.isEmpty()) {
            ufw = parse(prometheus.query(LanQueries.ufwBlocks(end), evaluation), LanCollector::ufwBlock);
            ssh = parse(prometheus.query(LanQueries.sshAuth(end), evaluation), LanCollector::sshAuth);
        }
        List<Sighting> sightings = new ArrayList<>();
        for (String node : nodes) {
            boolean isPublished = published.contains(node);
            List<Connection> nodeConnections = connections.getOrDefault(node, List.of());
            List<UfwBlock> nodeUfw = isPublished ? ufw.getOrDefault(node, List.of()) : null;
            List<SshAuth> nodeSsh = isPublished ? ssh.getOrDefault(node, List.of()) : null;
            repository.replace(start, node, nodeConnections, nodeUfw, nodeSsh);
            nodeConnections.forEach(c -> sighting(c.srcIp(), start, end, sightings));
            if (isPublished) {
                nodeUfw.forEach(u -> sighting(u.srcIp(), start, end, sightings));
                nodeSsh.forEach(s -> sighting(s.srcIp(), start, end, sightings));
            }
        }
        enrichment.record("lan", LanSnapshotRepository.SOURCE, sightings);
    }

    /** Only literal IPs can be public; the {@code 10.42.0.0/16} and {@code other} buckets never are. */
    private static void sighting(String srcIp, Instant start, Instant end, List<Sighting> sightings) {
        IpAddresses.canonical(srcIp).ifPresent(ip -> sightings.add(new Sighting(ip, start, end, null, null, null)));
    }

    private static Set<String> nodes(List<PrometheusSample> samples) {
        Set<String> nodes = new TreeSet<>();
        for (PrometheusSample sample : samples) {
            String node = sample.label("node");
            if (node != null && NODE.matcher(node).matches()) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    private Map<String, List<Connection>> connections(List<PrometheusSample> samples) {
        return parse(samples, LanCollector::connection);
    }

    /** Groups valid rows by node; series with unexpected labels or values are dropped and only counted. */
    private static <T> Map<String, List<T>> parse(List<PrometheusSample> samples, Function<PrometheusSample, T> mapper) {
        Map<String, List<T>> byNode = new TreeMap<>();
        int dropped = 0;
        for (PrometheusSample sample : samples) {
            String node = sample.label("node");
            T row = node != null && NODE.matcher(node).matches() ? mapper.apply(sample) : null;
            if (row == null) {
                dropped++;
                continue;
            }
            byNode.computeIfAbsent(node, key -> new ArrayList<>()).add(row);
        }
        if (dropped > 0) {
            log.warn("[{}] dropped {} series with unexpected labels or values", NAME, dropped);
        }
        return byNode;
    }

    static Connection connection(PrometheusSample s) {
        String srcIp = srcIp(s);
        Integer dport = port(s.label("dport"), 1);
        String state = s.label("state");
        Integer peak = count(s.value());
        if (srcIp == null || dport == null || state == null || !STATE.matcher(state).matches() || peak == null) {
            return null;
        }
        return new Connection(srcIp, dport, state, peak);
    }

    static UfwBlock ufwBlock(PrometheusSample s) {
        String srcIp = srcIp(s);
        Integer dport = port(s.label("dport"), 0);
        String proto = s.label("proto");
        Integer blocks = count(s.value());
        if (srcIp == null || dport == null || proto == null || !PROTOS.contains(proto) || blocks == null) {
            return null;
        }
        return new UfwBlock(srcIp, dport, proto, blocks);
    }

    static SshAuth sshAuth(PrometheusSample s) {
        String srcIp = srcIp(s);
        String outcome = s.label("outcome");
        Integer attempts = count(s.value());
        if (srcIp == null || outcome == null || !OUTCOMES.contains(outcome) || attempts == null) {
            return null;
        }
        return new SshAuth(srcIp, outcome, attempts);
    }

    private static String srcIp(PrometheusSample s) {
        String srcIp = s.label("src_ip");
        return srcIp != null && SRC_IP.matcher(srcIp).matches() ? srcIp : null;
    }

    private static Integer port(String raw, int min) {
        if (raw == null) {
            return null;
        }
        try {
            int port = Integer.parseInt(raw);
            return port >= min && port <= 65_535 ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer count(double value) {
        if (Double.isNaN(value) || value < 0 || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) Math.round(value);
    }

    /** The latest 15-minute boundary (UTC) at or before {@code instant}. */
    static Instant alignDown(Instant instant) {
        long seconds = instant.getEpochSecond();
        return Instant.ofEpochSecond(seconds - Math.floorMod(seconds, WINDOW.toSeconds()));
    }

    private record WindowResult(boolean isFinal, int expectedNodes) {
    }
}
