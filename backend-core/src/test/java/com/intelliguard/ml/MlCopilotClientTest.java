package com.intelliguard.ml;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MlCopilotClientTest {

    private static final MlCopilotRequest REQUEST = new MlCopilotRequest("What happened?", new MlCopilotRequest.Context(
            new MlCopilotRequest.Summary(3L, "inventory-db", "OPEN", null, null, null, null, null, null),
            new MlCopilotRequest.RootCause(null, List.of()), List.of(), List.of(), List.of(), List.of(), List.of()));

    @Test
    void sendsTheQuestionAndContextAndParsesTheAnswer() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ml-service:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ml-service:8000/copilot/ask")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.question").value("What happened?"))
                .andExpect(jsonPath("$.context.incident.title").value("inventory-db"))
                .andRespond(withSuccess("""
                        {"answer": "Incident #3 is OPEN.", "citations": [{"type": "INCIDENT_DATA", "id": "incident",
                         "title": "Incident summary", "excerpt": "inventory-db - OPEN"}], "mode": "extractive",
                         "intent": "timeline", "grounded": true, "refused": false, "reason": null, "retrieved": []}
                        """, MediaType.APPLICATION_JSON));

        MlCopilotResponse r = new MlCopilotClient(builder.build()).ask(REQUEST).orElseThrow();
        assertThat(r.answer()).isEqualTo("Incident #3 is OPEN.");
        assertThat(r.citations()).singleElement().satisfies(c -> assertThat(c.id()).isEqualTo("incident"));
        assertThat(r.grounded()).isTrue();
        server.verify();
    }

    @Test
    void connectionRefusedIsEmpty() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        assertThat(new MlCopilotClient(MlScoringClient.buildRestClient("http://127.0.0.1:" + port, Duration.ofSeconds(2)))
                .ask(REQUEST)).isEmpty();
    }
}
