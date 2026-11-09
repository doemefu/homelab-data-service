package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The capped stats row (WA-5 plan ruling R3): Zurich receipt day, own transaction, failures never escape. */
@ExtendWith(OutputCaptureExtension.class)
class CappedBatchRecorderTest {

    private final WebAnalyticsRepository repository = mock(WebAnalyticsRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    // 23:30Z on 2026-11-09 is already 2026-11-10 in Zurich.
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T23:30:00Z"));
    private final CappedBatchRecorder recorder = new CappedBatchRecorder(repository, transactions, clock,
            IngestCapsTest.properties(10, 10, 10));

    @Test
    void writesTheCappedRowForTheZurichDayOfReceipt() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        recorder.record(new IngestCaps.Capped(IngestCaps.Scope.HOUR, Instant.parse("2026-11-10T00:00:00Z"), 7));
        verify(repository).incrementCapped(LocalDate.parse("2026-11-10"), "hour", 7);
    }

    @Test
    void aFailureIsLoggedWithTheClassNameOnlyAndSwallowed(CapturedOutput output) {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doThrow(new DataAccessResourceFailureException("db gone: secret detail"))
                .when(repository).incrementCapped(any(), anyString(), anyLong());
        assertThatCode(() -> recorder.record(new IngestCaps.Capped(IngestCaps.Scope.DAY,
                Instant.parse("2026-11-10T00:00:00Z"), 3))).doesNotThrowAnyException();
        assertThat(output).contains("capped batch not recorded: DataAccessResourceFailureException")
                .doesNotContain("secret detail");
    }
}
