package ch.furchert.homelab.data.netmon.login;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;

/** {@code netmon.login_events}; write mode "upsert, do nothing on {@code event_id}" (docs/060 §3.3). */
@Repository
public class LoginEventRepository {

    /** Both the §3.2 {@code source} and the §3.3 {@code source_service} column carry the producer's name. */
    static final String SOURCE = "auth-service";

    private static final String INSERT = """
            INSERT INTO netmon.login_events
                (event_id, occurred_at, outcome, client_ip, ip_source, username_hmac, subject, user_agent,
                 source_service, source)
            VALUES (:eventId, :occurredAt, :outcome, CAST(:clientIp AS inet), :ipSource, :usernameHmac, :subject,
                    :userAgent, :source, :source)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate named;

    public LoginEventRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /** Inserts the events; replayed pages are absorbed by {@code event_id}. */
    @Transactional
    public void insertAll(Collection<LoginEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = events.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("eventId", e.eventId())
                        .addValue("occurredAt", OffsetDateTime.ofInstant(e.occurredAt(), ZoneOffset.UTC))
                        .addValue("outcome", e.outcome())
                        .addValue("clientIp", e.clientIp())
                        .addValue("ipSource", e.ipSource())
                        .addValue("usernameHmac", e.usernameHmac())
                        .addValue("subject", e.subject())
                        .addValue("userAgent", e.userAgent())
                        .addValue("source", SOURCE))
                .toArray(SqlParameterSource[]::new);
        named.batchUpdate(INSERT, batch);
    }
}
