package ch.furchert.homelab.data.netmon.login;

import java.time.Instant;
import java.util.UUID;

/**
 * One validated outbox row from auth-service (docs/060 §7.6), ready for {@code netmon.login_events}.
 *
 * @param clientIp  canonical IP literal, or {@code null}
 * @param subject   plaintext username; only ever set for {@code success}
 * @param userAgent truncated to 512 characters, or {@code null}
 */
public record LoginEvent(long id, UUID eventId, Instant occurredAt, String outcome, String clientIp, String ipSource,
                         String usernameHmac, String subject, String userAgent) {
}
