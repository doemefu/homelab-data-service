package ch.furchert.homelab.data.netmon.cloudflare;

import java.util.List;

/**
 * One upstream result page.
 *
 * @param items parsed rows (rows without a valid IP, timestamp or ray id are dropped)
 * @param full  the upstream returned {@code limit} rows, counted before dropping, so the window may be truncated
 */
public record Page<T>(List<T> items, boolean full) {

    public Page {
        items = List.copyOf(items);
    }
}
