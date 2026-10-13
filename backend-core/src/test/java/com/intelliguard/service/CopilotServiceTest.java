package com.intelliguard.service;

import com.intelliguard.dto.IncidentDetailResponse;
import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.IncidentNote;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.DetectorType;
import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.entity.enums.RiskLevel;
import com.intelliguard.entity.enums.ServiceType;
import com.intelliguard.entity.enums.Severity;
import com.intelliguard.ml.MlCopilotClient;
import com.intelliguard.ml.MlCopilotRequest;
import com.intelliguard.ml.MlCopilotResponse;
import com.intelliguard.repository.SecurityEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The copilot request: question validation, 404 / 503 behaviour, and the context it builds from the
// incident detail (ranking with names, services with current risk, trimmed timeline and notes,
// security events counted inside the incident's window).
class CopilotServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-11T18:23:36Z");
    private static final MlCopilotResponse ANSWER = new MlCopilotResponse("answer", List.of(), "extractive", "root_cause",
            true, false, null);

    private final IncidentDetailService details = mock(IncidentDetailService.class);
    private final SecurityEventRepository security = mock(SecurityEventRepository.class);
    private final MlCopilotClient client = mock(MlCopilotClient.class);
    private final CopilotService service = new CopilotService(details, security, client);

    @BeforeEach
    void setUp() {
        when(details.get(3L)).thenReturn(detail());
        when(details.get(404L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident 404 not found"));
        when(client.ask(any())).thenReturn(Optional.of(ANSWER));
        when(security.countByServiceAndTypeInWindow(any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{3L, "PORT_SCAN", 14L}));
    }

    @Test
    void theContextComesFromTheIncidentDetail() {
        assertThat(service.ask(3L, "  Why is this the likely root cause?  ")).isEqualTo(ANSWER);

        ArgumentCaptor<MlCopilotRequest> sent = ArgumentCaptor.forClass(MlCopilotRequest.class);
        verify(client).ask(sent.capture());
        MlCopilotRequest r = sent.getValue();
        assertThat(r.question()).isEqualTo("Why is this the likely root cause?");
        MlCopilotRequest.Context c = r.context();
        assertThat(c.incident().title()).isEqualTo("inventory-db + 1 related service");
        assertThat(c.incident().status()).isEqualTo("OPEN");
        assertThat(c.rootCause().confidence()).isEqualTo(0.2862);
        assertThat(c.rootCause().candidates()).extracting(MlCopilotRequest.Candidate::serviceName)
                .containsExactly("inventory-db", "inventory-service");
        assertThat(c.rootCause().candidates().get(0).evidence()).containsExactly("earliest onset in the incident");
        assertThat(c.services()).extracting(MlCopilotRequest.ServiceCtx::currentRiskLevel).containsExactly("CRITICAL", "MEDIUM");
        assertThat(c.anomalies()).singleElement().satisfies(a -> {
            assertThat(a.serviceName()).isEqualTo("inventory-db");
            assertThat(a.metricType()).isEqualTo("CPU");
        });
        assertThat(c.timeline()).hasSize(CopilotService.MAX_TIMELINE);
        assertThat(c.timeline().get(c.timeline().size() - 1).detail()).isEqualTo("event 49"); // the newest are kept
        assertThat(c.notes()).hasSize(CopilotService.MAX_NOTES);
        assertThat(c.notes().get(0).author()).isEqualTo("alice");
        assertThat(c.notes().get(c.notes().size() - 1).text()).isEqualTo("note 24");
        assertThat(c.securityEvents()).singleElement().satisfies(s -> {
            assertThat(s.serviceName()).isEqualTo("inventory-db");
            assertThat(s.eventType()).isEqualTo("PORT_SCAN");
            assertThat(s.count()).isEqualTo(14L);
        });
        verify(security).countByServiceAndTypeInWindow(any(), eq(T0.minusSeconds(60)), eq(T0.plusSeconds(580)));
    }

    @Test
    void blankOrTooLongQuestionsAreRejected() {
        for (String bad : new String[]{null, "", "   "}) {
            assertStatus(() -> service.ask(3L, bad), HttpStatus.BAD_REQUEST);
        }
        assertStatus(() -> service.ask(3L, "x".repeat(501)), HttpStatus.BAD_REQUEST);
        verify(client, never()).ask(any());
        assertThat(service.ask(3L, "x".repeat(500))).isEqualTo(ANSWER);
    }

    @Test
    void unknownIncidentIsNotFound() {
        assertStatus(() -> service.ask(404L, "What happened?"), HttpStatus.NOT_FOUND);
        verify(client, never()).ask(any());
    }

    @Test
    void mlServiceDownIsServiceUnavailableWithAClearMessage() {
        when(client.ask(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.ask(3L, "What happened?")).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getReason()).contains("copilot is unavailable").contains("rest of the incident workspace still works");
        });
    }

    private static IncidentDetailResponse detail() {
        List<IncidentDetailResponse.TimelineEntry> timeline = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            timeline.add(new IncidentDetailResponse.TimelineEntry(T0.plusSeconds(i), "ANOMALY_START", (long) i, 3L,
                    "inventory-db", "event " + i, null));
        }
        List<IncidentNote> notes = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            notes.add(IncidentNote.builder().id((long) i).incidentId(3L).author(i < 5 ? "bob" : "alice").text("note " + i)
                    .createdAt(T0.plusSeconds(i)).build());
        }
        Anomaly anomaly = Anomaly.builder().id(25L).serviceId(3L).metricType(MetricType.CPU).detector(DetectorType.L1)
                .status(AnomalyStatus.CLOSED).detectedAt(T0).endedAt(T0.plusSeconds(180)).anomalyScore(0.868).build();
        return new IncidentDetailResponse(3L, "inventory-db + 1 related service", IncidentStatus.OPEN, Severity.HIGH,
                Severity.HIGH, T0, T0.plusSeconds(580), null, T0, null, "heuristic (not a learned model)", null, null,
                new IncidentDetailResponse.RootCause(3L, "inventory-db", 0.2862, "(s1 - s2) / s1", "inventory-db ranked first",
                        List.of(Map.of("serviceId", 3, "serviceName", "inventory-db", "rank", 1, "score", 0.8558,
                                        "evidence", List.of("earliest onset in the incident")),
                                Map.of("serviceId", 5, "serviceName", "inventory-service", "rank", 2, "score", 0.6109,
                                        "evidence", List.of()))),
                List.of(new IncidentDetailResponse.ServiceEntry(3L, "inventory-db", ServiceType.DATABASE, Criticality.HIGH,
                                RiskLevel.CRITICAL, 76.8, T0),
                        new IncidentDetailResponse.ServiceEntry(5L, "inventory-service", ServiceType.MICROSERVICE,
                                Criticality.MEDIUM, RiskLevel.MEDIUM, 40.8, T0)),
                List.of(anomaly), timeline, notes, notes.size());
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
