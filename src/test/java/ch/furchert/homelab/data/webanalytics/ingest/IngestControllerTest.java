package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import ch.furchert.homelab.data.web.NetmonApiException;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.EdgeDrops;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.EventDto;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.IngestRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Body cap and batch-level rules (docs/100 §6.2). */
class IngestControllerTest {

    private static final EdgeDrops ZERO = new EdgeDrops(0, 0, 0, 0, 0, 0, 0, 0, 0);

    @Test
    void declaredContentLengthOverCapIsRejectedWithoutReadingTheBody() throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(IngestLimits.MAX_BODY_BYTES + 1L);
        assertThatThrownBy(() -> IngestController.readBody(request))
                .isInstanceOfSatisfying(NetmonApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
        verify(request, never()).getInputStream();
    }

    @Test
    void undeclaredBodyOverCapIsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(new byte[IngestLimits.MAX_BODY_BYTES + 1]);
        request.removeHeader("Content-Length");
        assertThatThrownBy(() -> IngestController.readBody(new UnknownLengthRequest(request)))
                .isInstanceOfSatisfying(NetmonApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
    }

    @Test
    void bodyAtCapIsRead() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(new byte[IngestLimits.MAX_BODY_BYTES]);
        assertThat(IngestController.readBody(request)).hasSize(IngestLimits.MAX_BODY_BYTES);
    }

    @Test
    void moreThan200EventsIs413() {
        EventDto event = WebAnalyticsFixtures.toDto(WebAnalyticsFixtures.event("enquiry", Instant.now()));
        IngestController.checkBatch(new IngestRequest(Collections.nCopies(200, event), ZERO));
        assertThatThrownBy(() -> IngestController.checkBatch(new IngestRequest(Collections.nCopies(201, event), ZERO)))
                .isInstanceOfSatisfying(NetmonApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
    }

    @Test
    void envelopeNullsAndNullEventsAre400() {
        assertBadRequest(new IngestRequest(null, ZERO));
        assertBadRequest(new IngestRequest(List.of(), null));
        List<EventDto> withNull = new ArrayList<>();
        withNull.add(null);
        assertBadRequest(new IngestRequest(withNull, ZERO));
    }

    @Test
    void edgeDropsOutOfRangeOrNullAre400() {
        IngestController.checkBatch(new IngestRequest(List.of(), new EdgeDrops(1_000_000, 0, 0, 0, 0, 0, 0, 0, 0)));
        assertBadRequest(new IngestRequest(List.of(), new EdgeDrops(1_000_001, 0, 0, 0, 0, 0, 0, 0, 0)));
        assertBadRequest(new IngestRequest(List.of(), new EdgeDrops(0, -1, 0, 0, 0, 0, 0, 0, 0)));
        assertBadRequest(new IngestRequest(List.of(), new EdgeDrops(0, 0, 0, 0, 0, 0, 0, 0, null)));
    }

    private static void assertBadRequest(IngestRequest request) {
        assertThatThrownBy(() -> IngestController.checkBatch(request))
                .isInstanceOfSatisfying(NetmonApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** A chunked request: no declared length. */
    private static final class UnknownLengthRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

        UnknownLengthRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }
    }
}
