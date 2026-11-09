package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The erasure bound (WA-5 review of #66, fixes 1 and 2): checked before every batch, each batch limited to the remaining
 * time (at most 2 s, at least 100 ms), the per-visitor key dropped after the deletes, also when incomplete.
 */
class VisitorEraseServiceTest {

    private final WebAnalyticsRepository repository = mock(WebAnalyticsRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T12:00:00Z"));
    private final IngestCaps caps = new IngestCaps(clock, IngestCapsTest.properties(100, 100, 50));
    private final VisitorEraseService service = new VisitorEraseService(repository, new Tombstones(clock), caps,
            transactions);

    @Test
    void everyBatchGetsOnlyTheRemainingTimeAndTheBoundIsCheckedBeforeEachBatch() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        List<Long> timeouts = new ArrayList<>();
        UUID visitor = UUID.randomUUID();
        when(repository.deleteVisitorBatch(eq(visitor), anyInt(), anyLong())).thenAnswer(invocation -> {
            timeouts.add(invocation.getArgument(2));
            TimeUnit.MILLISECONDS.sleep(900); // a slow full batch every time
            return VisitorEraseService.BATCH_SIZE;
        });

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.erase(visitor)).isInstanceOf(VisitorEraseIncompleteException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(timeouts).hasSize(3); // 0 ms, ~900 ms, ~1800 ms elapsed; at ~2700 ms the bound stops it
        assertThat(timeouts.get(0)).isEqualTo(VisitorEraseService.MAX_STATEMENT_TIMEOUT_MS);
        assertThat(timeouts.get(1)).isBetween(1_000L, 1_700L);
        assertThat(timeouts.get(2)).isBetween(VisitorEraseService.MIN_STATEMENT_TIMEOUT_MS, 800L);
        assertThat(elapsed).isLessThan(Duration.ofMillis(3_000));
    }

    @Test
    void thePerVisitorKeyIsDroppedAfterTheDeletes() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        UUID visitor = UUID.randomUUID();
        caps.seedStarted();
        caps.seedCompleted(Map.of());
        when(repository.deleteVisitorBatch(eq(visitor), anyInt(), anyLong())).thenAnswer(invocation -> {
            caps.reserve(List.of(), Map.of(visitor, 3L)); // a batch charged the visitor while the erasure ran
            return 0;
        });
        service.erase(visitor);
        assertThat(caps.unseenVisitors(List.of(visitor))).containsExactly(visitor);
    }
}
