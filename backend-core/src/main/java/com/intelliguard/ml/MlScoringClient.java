package com.intelliguard.ml;

import com.intelliguard.config.ScoringProperties;
import com.intelliguard.entity.MetricRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Calls ml-service POST /score. Never throws and never waits longer than the configured timeout
// (2 s by default): any failure - connection refused, timeout, non-2xx, unreadable body - is
// logged and returned as Optional.empty(), and the caller falls back to L0-only for that cycle.
@Slf4j
@Component
public class MlScoringClient {

    private final RestClient restClient;

    @Autowired
    public MlScoringClient(ScoringProperties properties) {
        this(buildRestClient(properties.mlUrl(), properties.mlTimeout()));
    }

    MlScoringClient(RestClient restClient) {
        this.restClient = restClient;
    }

    static RestClient buildRestClient(String baseUrl, Duration timeout) {
        // HTTP/1.1 explicitly: the JDK client defaults to HTTP/2 and, on plain http://, sends an
        // "Upgrade: h2c" request; uvicorn treats that as a protocol switch and never reads the
        // POST body, so ml-service saw an empty body and answered 422.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Optional<MlScoreResponse> score(Long serviceId, List<MetricRecord> window) {
        List<MlScoreRequest.Sample> samples = window.stream()
                .sorted(Comparator.comparing(MetricRecord::getTimestamp))
                .map(m -> new MlScoreRequest.Sample(m.getTimestamp(), m.getMetricType().name(), m.getValue()))
                .toList();
        try {
            MlScoreResponse response = restClient.post()
                    .uri("/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new MlScoreRequest(serviceId, samples))
                    .retrieve()
                    .body(MlScoreResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientException e) {
            log.warn("ml-service unavailable for service {} ({}); falling back to L0-only this cycle",
                    serviceId, e.getMessage());
            return Optional.empty();
        }
    }
}
