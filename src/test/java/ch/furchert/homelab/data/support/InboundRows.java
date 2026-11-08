package ch.furchert.homelab.data.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/** Seeds {@code netmon.inbound_request_groups} hours for the Cloudflare-baseline tests. */
public final class InboundRows {

    private static final AtomicInteger IP = new AtomicInteger(1);

    private final JdbcClient jdbc;

    public InboundRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void hour(Instant windowStart, String host, String method, String path, int status, long requests,
                     double sampleInterval) {
        int n = IP.getAndIncrement();
        jdbc.sql("""
                        INSERT INTO netmon.inbound_request_groups (window_start, window_end, is_final, client_ip, host,
                            method, path, status, request_count, sample_interval, source)
                        VALUES (:start, :end, true, CAST(:ip AS inet), :host, :method, :path, :status, :requests,
                            :interval, 'cloudflare')
                        """)
                .param("start", OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC))
                .param("end", OffsetDateTime.ofInstant(windowStart.plusSeconds(3600), ZoneOffset.UTC))
                .param("ip", "198.51." + (n / 250) % 250 + "." + (n % 250 + 1))
                .param("host", host).param("method", method).param("path", path).param("status", status)
                .param("requests", requests).param("interval", sampleInterval)
                .update();
    }

    public void page(Instant windowStart, String path, long requests) {
        hour(windowStart, "furchert.ch", "GET", path, 200, requests, 1.0);
    }

    public void clear() {
        jdbc.sql("DELETE FROM netmon.inbound_request_groups").update();
    }
}
