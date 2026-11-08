package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import ch.furchert.homelab.data.web.ProblemDetailsAdvice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A failed batch write: 500 problem+json {@code internal}, logged once with the class name only (review N2). */
@ExtendWith(OutputCaptureExtension.class)
class IngestControllerStorageFailureTest {

    @Test
    void storageFailureIs500AndLoggedOnceWithoutTheDatabaseMessage(CapturedOutput output) throws Exception {
        IngestService service = mock(IngestService.class);
        when(service.ingest(any(), anyString()))
                .thenThrow(new DataIntegrityViolationException("Failing row contains (secret-visitor-id)"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new IngestController(new IngestJson(), service))
                .setControllerAdvice(new ProblemDetailsAdvice())
                .build();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("furchert-ch")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        mockMvc.perform(post(WebAnalyticsFixtures.EVENTS)
                        .principal(new JwtAuthenticationToken(jwt, List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WebAnalyticsFixtures.batch(List.of())))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(jsonPath("$.instance").value(WebAnalyticsFixtures.EVENTS))
                .andExpect(jsonPath("$.code").value("internal"));

        assertThat(output.getAll()).containsOnlyOnce("[webanalytics] batch write failed: DataIntegrityViolationException")
                .doesNotContain("Unhandled exception")
                .doesNotContain("secret-visitor-id");
    }
}
