package com.intelliguard.ml;

import com.intelliguard.entity.MetricRecord;
import com.intelliguard.entity.enums.MetricType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// The backend must survive any ml-service failure: every failure mode returns Optional.empty()
// (-> L0-only fallback) instead of throwing, and a hung ml-service can't block past the timeout.
class MlScoringClientTest {

    private static final List<MetricRecord> WINDOW = List.of(
            MetricRecord.builder().serviceId(4L).metricType(MetricType.CPU).value(31.0).timestamp(Instant.parse("2026-10-10T10:00:05Z")).build(),
            MetricRecord.builder().serviceId(4L).metricType(MetricType.CPU).value(30.0).timestamp(Instant.parse("2026-10-10T10:00:00Z")).build());

    private static final String RESPONSE = """
            {"serviceId": 4, "detector": "L1", "threshold": 2.1532, "minSamplesPerMetric": 61,
             "insufficientData": false, "serviceScore": 0.83, "isAnomalous": true,
             "metrics": {"CPU": {"detector": "L1", "samples": 90, "insufficientData": false,
                                 "score": 0.83, "rawScore": 10.5, "isAnomalous": true}},
             "baseline": {"detector": "L0", "isAnomalous": true, "metrics": {}}}
            """;

    @Test
    void parsesAScoreAndSendsSamplesOldestFirst() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ml-service:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ml-service:8000/score")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.serviceId").value(4))
                .andExpect(jsonPath("$.samples[0].value").value(30.0))
                .andExpect(jsonPath("$.samples[1].metricType").value("CPU"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        Optional<MlScoreResponse> r = new MlScoringClient(builder.build()).score(4L, WINDOW);

        assertThat(r).isPresent();
        assertThat(r.get().serviceScore()).isEqualTo(0.83);
        assertThat(r.get().anomalous()).isTrue();
        assertThat(r.get().metrics().get("CPU").anomalous()).isTrue();
        assertThat(r.get().metrics().get("CPU").rawScore()).isEqualTo(10.5);
        server.verify();
    }

    @Test
    void realHttpCall_usesHttp11WithTheFullJsonBody() throws Exception {
        // Regression: the JDK client's default HTTP/2 upgrade made uvicorn drop the body (422).
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> upgrade = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/score", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = RESPONSE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            RestClient client = MlScoringClient.buildRestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2));
            assertThat(new MlScoringClient(client).score(4L, WINDOW)).isPresent();
            assertThat(upgrade.get()).isNull();
            assertThat(body.get()).contains("\"serviceId\":4").contains("\"metricType\":\"CPU\"")
                    .contains("\"timestamp\":\"2026-10-10T10:00:00Z\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverErrorFallsBackToEmpty() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ml-service:8000");
        MockRestServiceServer.bindTo(builder).build()
                .expect(requestTo("http://ml-service:8000/score")).andRespond(withServerError());

        assertThat(new MlScoringClient(builder.build()).score(4L, WINDOW)).isEmpty();
    }

    @Test
    void connectionRefusedFallsBackToEmpty() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort(); // closed again: nothing listens there
        }
        RestClient client = MlScoringClient.buildRestClient("http://127.0.0.1:" + port, Duration.ofSeconds(2));
        assertThat(new MlScoringClient(client).score(4L, WINDOW)).isEmpty();
    }

    @Test
    void aHungMlServiceTimesOutInsteadOfBlocking() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) { // accepts the connection, never answers
            Thread acceptor = new Thread(() -> {
                try (Socket ignored = silent.accept()) {
                    Thread.sleep(5_000);
                } catch (Exception ignored) {
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            RestClient client = MlScoringClient.buildRestClient("http://127.0.0.1:" + silent.getLocalPort(), Duration.ofMillis(300));
            long start = System.nanoTime();
            Optional<MlScoreResponse> r = new MlScoringClient(client).score(4L, WINDOW);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(r).isEmpty();
            assertThat(elapsedMs).isLessThan(2_000);
        }
    }
}
