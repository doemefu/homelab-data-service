package ch.furchert.homelab.data.netmon.login;

import java.util.List;

/**
 * One outbox page. {@code nextAfter} is the producer's cursor for the next call (the request's {@code after} when the
 * page is empty); {@code hasMore} says to fetch again at once. {@code skipped} counts rows dropped by validation; they
 * are covered by {@code nextAfter} all the same, because the producer never changes a row.
 */
public record LoginEventPage(List<LoginEvent> events, long nextAfter, boolean hasMore, int skipped) {

    public LoginEventPage {
        events = List.copyOf(events);
    }
}
