package com.intelliguard.ml;

import com.intelliguard.config.ScoringProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

// Calls ml-service POST /correlate with the same client setup as MlScoringClient (HTTP/1.1,
// intelliguard.scoring.ml-timeout for connect and read). Never throws: any failure - refused,
// timeout, non-2xx (e.g. 422), unreadable body - is logged and returned as Optional.empty(),
// and the incident job skips that cycle.
@Slf4j
@Component
public class MlCorrelationClient {

    private final RestClient restClient;

    @Autowired
    public MlCorrelationClient(ScoringProperties properties) {
        this(MlScoringClient.buildRestClient(properties.mlUrl(), properties.mlTimeout()));
    }

    MlCorrelationClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public Optional<MlCorrelateResponse> correlate(MlCorrelateRequest request) {
        try {
            MlCorrelateResponse response = restClient.post()
                    .uri("/correlate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(MlCorrelateResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientException e) {
            log.warn("ml-service /correlate unavailable ({}); skipping this incident correlation cycle", e.getMessage());
            return Optional.empty();
        }
    }
}
