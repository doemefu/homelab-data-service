package ch.furchert.homelab.data.webanalytics.rollup;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Day arithmetic in the configured analytics zone (Europe/Zurich, docs/100 §7.5). A day D is closed at
 * {@code start(D + 1) + 7 h 15 min} of elapsed time: the 6 h visit cap plus 60 min forward-queue age plus 15 min
 * clock skew. That is 07:15 local on normal days, 06:15 on fall-back days and 08:15 on spring-forward days.
 */
public final class ZurichDays {

    static final Duration CLOSE_AFTER = Duration.ofHours(7).plusMinutes(15);

    private final ZoneId zone;

    public ZurichDays(ZoneId zone) {
        this.zone = zone;
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate dayOf(Instant instant) {
        return LocalDate.ofInstant(instant, zone);
    }

    public Instant start(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    public Instant close(LocalDate day) {
        return start(day.plusDays(1)).plus(CLOSE_AFTER);
    }

    public boolean isClosed(LocalDate day, Instant now) {
        return !now.isBefore(close(day));
    }
}
