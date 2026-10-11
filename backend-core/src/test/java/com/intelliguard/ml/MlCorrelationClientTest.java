package com.intelliguard.ml;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// Same guarantees as the scoring client: the request matches ml-service's /correlate contract,
// and every failure (error status, refused connection) is Optional.empty() instead of an exception.
class MlCorrelationClientTest {

    private static final MlCorrelateRequest REQUEST = new MlCorrelateRequest(
            List.of(new MlCorrelateRequest.Episode(11L, 2L, "LATENCY", "L1", Instant.parse("2026-10-10T10:00:00Z"), null, 0.93)),
            List.of(new MlCorrelateRequest.Edge(6L, 2L)), Map.of(2L, "CRITICAL"), Map.of(2L, 3L), "full_call_graph");

    private static final String RESPONSE = """
            {"method": "heuristic graph-based correlation and root-cause ranking (not a learned model)",
             "profile": "full_call_graph", "params": {"gap_seconds": 240.0, "max_hops": 1},
             "confidenceMethod": "(score of rank 1 - score of rank 2) / score of rank 1", "asOf": "2026-10-10T10:00:00Z",
             "incidents": [{"incidentId": "incident-1", "start": "2026-10-10T10:00:00Z", "end": "2026-10-10T10:00:00Z",
                            "openEpisodes": 1, "episodeIds": [11], "services": [2],
                            "rootCause": {"confidence": 1.0, "candidates": [{"serviceId": 2, "score": 0.8, "rank": 1,
                                          "components": {"onset": 1.0}, "evidence": ["earliest onset in the incident"]}]}}]}
            """;

    @Test
    void sendsTheContractAndParsesGroups() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ml-service:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ml-service:8000/correlate")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.profile").value("full_call_graph"))
                .andExpect(jsonPath("$.episodes[0].id").value(11))
                .andExpect(jsonPath("$.episodes[0].start").value("2026-10-10T10:00:00Z"))
                .andExpect(jsonPath("$.episodes[0].end").doesNotExist())
                .andExpect(jsonPath("$.dependencies[0].dependsOn").value(2))
                .andExpect(jsonPath("$.criticality['2']").value("CRITICAL"))
                .andExpect(jsonPath("$.securityEvents['2']").value(3))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        Optional<MlCorrelateResponse> r = new MlCorrelationClient(builder.build()).correlate(REQUEST);

        assertThat(r).isPresent();
        assertThat(r.get().method()).contains("not a learned model");
        MlCorrelateResponse.Group g = r.get().incidents().get(0);
        assertThat(g.episodeIds()).containsExactly(11L);
        assertThat(g.rootCause()).containsEntry("confidence", 1.0);
        server.verify();
    }

    @Test
    void errorStatusFallsBackToEmpty() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ml-service:8000");
        MockRestServiceServer.bindTo(builder).build().expect(requestTo("http://ml-service:8000/correlate"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));

        assertThat(new MlCorrelationClient(builder.build()).correlate(REQUEST)).isEmpty();
    }

    @Test
    void connectionRefusedFallsBackToEmpty() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        RestClient client = MlScoringClient.buildRestClient("http://127.0.0.1:" + port, Duration.ofSeconds(2));
        assertThat(new MlCorrelationClient(client).correlate(REQUEST)).isEmpty();
    }
}
