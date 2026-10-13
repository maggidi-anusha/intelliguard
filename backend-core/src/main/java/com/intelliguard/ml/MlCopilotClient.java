package com.intelliguard.ml;

import com.intelliguard.config.ScoringProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

// Calls ml-service POST /copilot/ask with the same client setup as MlScoringClient (HTTP/1.1,
// intelliguard.scoring.ml-timeout). Never throws: any failure is logged and returned as
// Optional.empty(), which CopilotService turns into a 503 for this one request.
@Slf4j
@Component
public class MlCopilotClient {

    private final RestClient restClient;

    @Autowired
    public MlCopilotClient(ScoringProperties properties) {
        this(MlScoringClient.buildRestClient(properties.mlUrl(), properties.mlTimeout()));
    }

    MlCopilotClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public Optional<MlCopilotResponse> ask(MlCopilotRequest request) {
        try {
            return Optional.ofNullable(restClient.post()
                    .uri("/copilot/ask")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(MlCopilotResponse.class));
        } catch (RestClientException e) {
            log.warn("ml-service /copilot/ask unavailable ({})", e.getMessage());
            return Optional.empty();
        }
    }
}
