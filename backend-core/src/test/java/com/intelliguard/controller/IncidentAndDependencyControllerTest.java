package com.intelliguard.controller;

import com.intelliguard.config.SecurityConfig;
import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.Incident;
import com.intelliguard.entity.IncidentNote;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.ServiceDependency;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.DetectorType;
import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.entity.enums.ServiceType;
import com.intelliguard.repository.AnomalyRepository;
import com.intelliguard.repository.IncidentNoteRepository;
import com.intelliguard.repository.IncidentRepository;
import com.intelliguard.repository.RiskScoreRepository;
import com.intelliguard.repository.ServiceRepository;
import com.intelliguard.risk.RiskSnapshotStore;
import com.intelliguard.service.IncidentWorkspaceService;
import com.intelliguard.service.ServiceDependencyService;
import com.intelliguard.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// With the real SecurityConfig: both APIs need a token (401 without); incidents are read-only for
// every role; dependency edges are readable by every role and writable by USER/ADMIN only.
@WebMvcTest({IncidentController.class, ServiceDependencyController.class})
@Import(SecurityConfig.class)
class IncidentAndDependencyControllerTest {

    private static final String EDGE = "{\"serviceId\": 7, \"dependsOnServiceId\": 6}";
    private static final String NOTE = "{\"text\": \"checked the order-db disk\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IncidentRepository incidentRepository;
    @MockitoBean
    private AnomalyRepository anomalyRepository;
    @MockitoBean
    private ServiceRepository serviceRepository;
    @MockitoBean
    private RiskScoreRepository riskScoreRepository;
    @MockitoBean
    private RiskSnapshotStore snapshotStore;
    @MockitoBean
    private ServiceDependencyService dependencyService;
    @MockitoBean
    private IncidentNoteRepository noteRepository;
    @MockitoBean
    private IncidentWorkspaceService workspaceService;
    @MockitoBean
    private JwtUtil jwtUtil;

    @Test
    void withoutToken_everyEndpointIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/incidents")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/incidents/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/service-dependencies")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/service-dependencies").contentType(MediaType.APPLICATION_JSON).content(EDGE))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/service-dependencies/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/incidents/1/notes").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/incidents/1/acknowledge")).andExpect(status().isUnauthorized());
        verify(dependencyService, never()).create(any());
        verifyNoInteractions(workspaceService);
    }

    @Test
    void notesAndAcknowledge_viewerIsForbidden_userAndAdminAllowed() throws Exception {
        when(workspaceService.addNote(eq(1L), any(), any())).thenAnswer(inv -> IncidentNote.builder().id(5L)
                .incidentId(1L).text(inv.getArgument(1)).author(inv.getArgument(2)).build());
        when(workspaceService.acknowledge(eq(1L), any())).thenAnswer(inv -> Incident.builder().id(1L).title("t")
                .status(IncidentStatus.INVESTIGATING).acknowledgedBy(inv.getArgument(1)).build());

        mockMvc.perform(post("/api/incidents/1/notes").contentType(MediaType.APPLICATION_JSON).content(NOTE)
                .with(user("v").roles("VIEWER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/incidents/1/acknowledge").with(user("v").roles("VIEWER"))).andExpect(status().isForbidden());
        verifyNoInteractions(workspaceService);

        for (String role : List.of("USER", "ADMIN")) {
            String name = role.toLowerCase() + "-1";
            mockMvc.perform(post("/api/incidents/1/notes").contentType(MediaType.APPLICATION_JSON).content(NOTE)
                            .with(user(name).roles(role)))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.author").value(name));
            mockMvc.perform(post("/api/incidents/1/acknowledge").with(user(name).roles(role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVESTIGATING"))
                    .andExpect(jsonPath("$.version").doesNotExist());
            verify(workspaceService).addNote(1L, "checked the order-db disk", name);
        }
    }

    @Test
    void workspaceErrorsReachTheClient() throws Exception {
        mockMvc.perform(post("/api/incidents/1/notes").contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(user("u").roles("USER"))).andExpect(status().isBadRequest());
        when(workspaceService.addNote(eq(2L), any(), any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "Incident 2 was merged into incident 1; add notes there instead"));
        mockMvc.perform(post("/api/incidents/2/notes").contentType(MediaType.APPLICATION_JSON).content(NOTE)
                        .with(user("u").roles("USER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Incident 2 was merged into incident 1; add notes there instead"));
        when(workspaceService.acknowledge(eq(404L), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident 404 not found"));
        mockMvc.perform(post("/api/incidents/404/acknowledge").with(user("u").roles("USER"))).andExpect(status().isNotFound());
    }

    @Test
    void everyRoleCanReadBoth() throws Exception {
        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident()));
        for (String role : List.of("VIEWER", "USER", "ADMIN")) {
            mockMvc.perform(get("/api/incidents").param("status", "OPEN").with(user("u").roles(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/incidents/1").with(user("u").roles(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/service-dependencies").with(user("u").roles(role))).andExpect(status().isOk());
        }
    }

    @Test
    void dependencyWrites_viewerIsForbidden_userAndAdminAllowed() throws Exception {
        when(dependencyService.create(any())).thenReturn(ServiceDependency.builder().id(9L).serviceId(7L).dependsOnServiceId(6L).build());

        mockMvc.perform(post("/api/service-dependencies").contentType(MediaType.APPLICATION_JSON).content(EDGE)
                .with(user("v").roles("VIEWER"))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/service-dependencies/9").with(user("v").roles("VIEWER"))).andExpect(status().isForbidden());
        verify(dependencyService, never()).create(any());
        verify(dependencyService, never()).delete(any());

        for (String role : List.of("USER", "ADMIN")) {
            mockMvc.perform(post("/api/service-dependencies").contentType(MediaType.APPLICATION_JSON).content(EDGE)
                    .with(user("u").roles(role))).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(9));
            mockMvc.perform(delete("/api/service-dependencies/9").with(user("u").roles(role))).andExpect(status().isNoContent());
        }
    }

    @Test
    void dependencyValidationErrorsReachTheClient() throws Exception {
        mockMvc.perform(post("/api/service-dependencies").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\": 7}")
                .with(user("u").roles("USER"))).andExpect(status().isBadRequest());
        when(dependencyService.create(any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Dependency 7 -> 6 already exists"));
        mockMvc.perform(post("/api/service-dependencies").contentType(MediaType.APPLICATION_JSON).content(EDGE)
                        .with(user("u").roles("USER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Dependency 7 -> 6 already exists"));
    }

    @Test
    void incidentWritesAreNotExposed() throws Exception {
        for (var request : List.of(post("/api/incidents"), put("/api/incidents/1"), delete("/api/incidents/1"))) {
            mockMvc.perform(request.with(user("admin").roles("ADMIN"))).andExpect(status().isMethodNotAllowed());
        }
    }

    @Test
    void incidentListIsCappedAndBadStatusIsRejected() throws Exception {
        mockMvc.perform(get("/api/incidents").param("limit", "100000").with(user("u").roles("VIEWER"))).andExpect(status().isOk());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository).findFiltered(isNull(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(IncidentController.MAX_RESULTS);

        mockMvc.perform(get("/api/incidents").param("status", "MAYBE").with(user("u").roles("VIEWER")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/incidents/404").with(user("u").roles("VIEWER"))).andExpect(status().isNotFound());
    }

    @Test
    void incidentDetailHasRankedCandidatesWithNamesAndATimeline() throws Exception {
        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident()));
        when(serviceRepository.findAll()).thenReturn(List.of(
                Service.builder().id(2L).name("order-db").type(ServiceType.DATABASE).criticality(Criticality.CRITICAL).build(),
                Service.builder().id(6L).name("order-service").type(ServiceType.MICROSERVICE).criticality(Criticality.HIGH).build()));
        Instant t = Instant.parse("2026-10-10T10:00:00Z");
        when(anomalyRepository.findByIncidentIdOrderByDetectedAtAscIdAsc(1L)).thenReturn(List.of(
                Anomaly.builder().id(11L).serviceId(2L).metricType(MetricType.LATENCY).detector(DetectorType.L1)
                        .status(AnomalyStatus.CLOSED).detectedAt(t).endedAt(t.plusSeconds(200)).anomalyScore(0.9).build(),
                Anomaly.builder().id(12L).serviceId(6L).metricType(MetricType.ERROR_RATE).detector(DetectorType.L1)
                        .status(AnomalyStatus.OPEN).detectedAt(t.plusSeconds(30)).anomalyScore(0.7).build()));

        when(noteRepository.findByIncidentIdOrderByCreatedAtDescIdDesc(eq(1L), any())).thenReturn(List.of(
                IncidentNote.builder().id(2L).incidentId(1L).author("bob").text("second").createdAt(t.plusSeconds(90)).build(),
                IncidentNote.builder().id(1L).incidentId(1L).author("alice").text("first").createdAt(t.plusSeconds(60)).build()));
        when(noteRepository.countByIncidentId(1L)).thenReturn(2L);

        mockMvc.perform(get("/api/incidents/1").with(user("u").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0].author").value("alice"))   // oldest first
                .andExpect(jsonPath("$.notes[1].text").value("second"))
                .andExpect(jsonPath("$.notesTotal").value(2))
                .andExpect(jsonPath("$.acknowledgedBy").value("alice"))
                .andExpect(jsonPath("$.timeline[1].detail").value("incident created"))
                .andExpect(jsonPath("$.method").value("heuristic graph-based correlation and root-cause ranking (not a learned model)"))
                .andExpect(jsonPath("$.rootCause.rootServiceName").value("order-db"))
                .andExpect(jsonPath("$.rootCause.candidates[0].serviceName").value("order-db"))
                .andExpect(jsonPath("$.rootCause.candidates[0].evidence[0]").value("earliest onset in the incident"))
                .andExpect(jsonPath("$.services[1].name").value("order-service"))
                .andExpect(jsonPath("$.anomalies.length()").value(2))
                .andExpect(jsonPath("$.timeline[0].type").value("ANOMALY_START"))
                .andExpect(jsonPath("$.timeline[1].type").value("CREATED"))
                .andExpect(jsonPath("$.timeline[3].type").value("ANOMALY_END"));
    }

    private static Incident incident() {
        Instant t = Instant.parse("2026-10-10T10:00:00Z");
        List<Map<String, Object>> events = new ArrayList<>(List.of(Map.of("at", t.plusSeconds(15).toString(), "type", "CREATED")));
        return Incident.builder().id(1L).title("order-db + 1 related service").status(IncidentStatus.INVESTIGATING)
                .acknowledgedBy("alice").acknowledgedAt(t.plusSeconds(70))
                .startedAt(t).lastActivityAt(t.plusSeconds(200)).serviceIds(List.of(2L, 6L)).rootServiceId(2L)
                .method("heuristic graph-based correlation and root-cause ranking (not a learned model)")
                .rootCause(Map.of("confidence", 0.35, "candidates", List.of(
                        Map.of("serviceId", 2, "rank", 1, "score", 1.0, "evidence", List.of("earliest onset in the incident")),
                        Map.of("serviceId", 6, "rank", 2, "score", 0.65, "evidence", List.of("onset 30 s after the earliest anomalous service")))))
                .events(events).build();
    }
}
