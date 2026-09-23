package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.web.NetmonApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiParamsTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00.123Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void defaultsToTheLast24h() {
        TimeWindow window = ApiParams.window(null, "", Duration.ofDays(1), clock);
        assertThat(window).isEqualTo(new TimeWindow(Instant.parse("2026-09-22T10:00:00Z"), Instant.parse("2026-09-23T10:00:00Z")));
    }

    @Test
    void acceptsUpTo30Days() {
        assertThat(ApiParams.window("2026-08-24T10:00:00Z", "2026-09-23T10:00:00Z", Duration.ofDays(1), clock).from())
                .isEqualTo(Instant.parse("2026-08-24T10:00:00Z"));
    }

    @Test
    void rejectsBadWindowsAsInvalidWindow() {
        for (String[] w : new String[][]{{"2026-08-24T09:59:59Z", "2026-09-23T10:00:00Z"},
                {"2026-09-23T10:00:00Z", "2026-09-23T10:00:00Z"}, {"2026-09-23T11:00:00Z", "2026-09-23T10:00:00Z"},
                {"yesterday", null}}) {
            assertThatThrownBy(() -> ApiParams.window(w[0], w[1], Duration.ofDays(1), clock))
                    .isInstanceOfSatisfying(NetmonApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_window"));
        }
    }

    @Test
    void limitBounds() {
        assertThat(ApiParams.limit(null, 10, 50)).isEqualTo(10);
        assertThat(ApiParams.limit("50", 10, 50)).isEqualTo(50);
        for (String bad : new String[]{"0", "51", "-1", "ten"}) {
            assertThatThrownBy(() -> ApiParams.limit(bad, 10, 50))
                    .isInstanceOfSatisfying(NetmonApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_parameter"));
        }
    }

    @Test
    void ipMustBeALiteral() {
        assertThat(ApiParams.ip("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThatThrownBy(() -> ApiParams.ip("localhost")).isInstanceOf(NetmonApiException.class);
        assertThatThrownBy(() -> ApiParams.ip("203.0.113.256")).isInstanceOf(NetmonApiException.class);
    }

    @Test
    void cursorRoundTripsAndRejectsGarbage() {
        ApiParams.Cursor cursor = new ApiParams.Cursor(Instant.parse("2026-09-23T10:00:00.123456Z"), 42);
        assertThat(ApiParams.Cursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(ApiParams.Cursor.decode(null)).isNull();
        assertThatThrownBy(() -> ApiParams.Cursor.decode("!!!")).isInstanceOf(NetmonApiException.class);
        assertThatThrownBy(() -> ApiParams.Cursor.decode("bm9waXBl")).isInstanceOf(NetmonApiException.class);
    }
}
