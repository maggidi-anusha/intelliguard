package com.intelliguard.controller;

import com.intelliguard.dto.IncidentDetailResponse;
import com.intelliguard.dto.IncidentNoteRequest;
import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.Incident;
import com.intelliguard.entity.IncidentNote;
import com.intelliguard.entity.RiskScore;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.entity.enums.RiskLevel;
import com.intelliguard.repository.AnomalyRepository;
import com.intelliguard.repository.IncidentNoteRepository;
import com.intelliguard.repository.IncidentRepository;
import com.intelliguard.repository.RiskScoreRepository;
import com.intelliguard.repository.ServiceRepository;
import com.intelliguard.risk.RiskSnapshot;
import com.intelliguard.risk.RiskSnapshotStore;
import com.intelliguard.service.IncidentWorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// Incident API. Incidents themselves are created and updated only by IncidentCorrelationJob;
// any authenticated role may read them. The Phase 6A workspace adds two human writes, USER or
// ADMIN only (see SecurityConfig): notes and acknowledgement (IncidentWorkspaceService).
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    static final int MAX_RESULTS = 100;
    static final int MAX_NOTES = 200;

    private final IncidentRepository incidentRepository;
    private final AnomalyRepository anomalyRepository;
    private final ServiceRepository serviceRepository;
    private final RiskScoreRepository riskScoreRepository;
    private final RiskSnapshotStore snapshotStore;
    private final IncidentNoteRepository noteRepository;
    private final IncidentWorkspaceService workspaceService;

    @GetMapping
    public List<Incident> find(@RequestParam(required = false) IncidentStatus status,
                               @RequestParam(defaultValue = "" + MAX_RESULTS) int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_RESULTS));
        return incidentRepository.findFiltered(status, PageRequest.of(0, capped));
    }

    @PostMapping("/{id}/notes")
    public ResponseEntity<IncidentNote> addNote(@PathVariable Long id, @Valid @RequestBody IncidentNoteRequest request,
                                                Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workspaceService.addNote(id, request.getText(), authentication.getName()));
    }

    @PostMapping("/{id}/acknowledge")
    public Incident acknowledge(@PathVariable Long id, Authentication authentication) {
        return workspaceService.acknowledge(id, authentication.getName());
    }

    @GetMapping("/{id}")
    public IncidentDetailResponse get(@PathVariable Long id) {
        Incident incident = incidentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident " + id + " not found"));
        Map<Long, Service> services = serviceRepository.findAll().stream()
                .collect(Collectors.toMap(Service::getId, Function.identity()));
        List<Anomaly> anomalies = anomalyRepository.findByIncidentIdOrderByDetectedAtAscIdAsc(id);

        List<IncidentDetailResponse.ServiceEntry> serviceEntries = new ArrayList<>();
        for (Long serviceId : incident.getServiceIds() == null ? List.<Long>of() : incident.getServiceIds()) {
            Service s = services.get(serviceId);
            Risk risk = currentRisk(serviceId);
            serviceEntries.add(new IncidentDetailResponse.ServiceEntry(serviceId, s == null ? null : s.getName(),
                    s == null ? null : s.getType(), s == null ? null : s.getCriticality(),
                    risk.level(), risk.score(), risk.at()));
        }

        List<IncidentNote> notes = new ArrayList<>(noteRepository.findByIncidentIdOrderByCreatedAtDescIdDesc(
                id, PageRequest.of(0, MAX_NOTES)));
        Collections.reverse(notes);

        return new IncidentDetailResponse(incident.getId(), incident.getTitle(), incident.getStatus(),
                incident.getSeverity(), incident.getPeakSeverity(), incident.getStartedAt(), incident.getLastActivityAt(),
                incident.getResolvedAt(), incident.getCreatedAt(), incident.getMergedIntoId(), incident.getMethod(),
                incident.getAcknowledgedBy(), incident.getAcknowledgedAt(),
                rootCause(incident, services), serviceEntries, anomalies, timeline(incident, anomalies, services),
                notes, noteRepository.countByIncidentId(id));
    }

    private record Risk(RiskLevel level, Double score, Instant at) {
    }

    // Same source as GET /api/risk/current: the latest cycle in memory, else the last persisted row.
    private Risk currentRisk(Long serviceId) {
        Optional<RiskSnapshot> snapshot = snapshotStore.get(serviceId);
        if (snapshot.isPresent()) {
            return new Risk(snapshot.get().level(), snapshot.get().score(), snapshot.get().calculatedAt());
        }
        return riskScoreRepository.findTopByServiceIdOrderByCalculatedAtDesc(serviceId)
                .map((RiskScore r) -> new Risk(r.getLevel(), r.getScore(), r.getCalculatedAt()))
                .orElse(new Risk(null, null, null));
    }

    // Readable text for an incident event (see Incident.events).
    private static String describe(String type, Map<String, Object> e) {
        Object other = e.get("incidentId");
        Object ids = e.get("anomalyIds");
        return switch (type) {
            case "CREATED" -> "incident created";
            case "RESOLVED" -> "resolved: no anomaly activity for the quiet period";
            case "REOPENED" -> "reopened: a new anomaly joined";
            case "MERGED_FROM" -> "incident " + other + " merged into this one";
            case "MERGED_INTO" -> "merged into incident " + other;
            case "SPLIT_FROM" -> "anomalies " + ids + " moved here from incident " + other;
            case "SPLIT_TO" -> "anomalies " + ids + " moved to incident " + other;
            case "NOTE_ADDED" -> "note added by " + e.get("by");
            case "ACKNOWLEDGED" -> "acknowledged by " + e.get("by") + " (status INVESTIGATING)";
            default -> type;
        };
    }

    private static IncidentDetailResponse.RootCause rootCause(Incident incident, Map<Long, Service> services) {
        Map<String, Object> rc = incident.getRootCause() == null ? Map.of() : incident.getRootCause();
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (rc.get("candidates") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    m.forEach((k, v) -> c.put(String.valueOf(k), v));
                    if (c.get("serviceId") instanceof Number n && services.containsKey(n.longValue())) {
                        c.put("serviceName", services.get(n.longValue()).getName());
                    }
                    candidates.add(c);
                }
            }
        }
        Service root = incident.getRootServiceId() == null ? null : services.get(incident.getRootServiceId());
        return new IncidentDetailResponse.RootCause(incident.getRootServiceId(), root == null ? null : root.getName(),
                rc.get("confidence"), rc.get("confidenceMethod"), incident.getRootCauseSuggestion(), candidates);
    }

    private static List<IncidentDetailResponse.TimelineEntry> timeline(Incident incident, List<Anomaly> anomalies,
                                                                       Map<Long, Service> services) {
        List<IncidentDetailResponse.TimelineEntry> entries = new ArrayList<>();
        for (Anomaly a : anomalies) {
            String name = services.containsKey(a.getServiceId()) ? services.get(a.getServiceId()).getName() : null;
            String what = a.getDetector() + " " + a.getMetricType();
            entries.add(new IncidentDetailResponse.TimelineEntry(a.getDetectedAt(), "ANOMALY_START", a.getId(),
                    a.getServiceId(), name, what + " anomaly started", null));
            if (a.getEndedAt() != null) {
                entries.add(new IncidentDetailResponse.TimelineEntry(a.getEndedAt(), "ANOMALY_END", a.getId(),
                        a.getServiceId(), name, what + " anomaly ended", null));
            }
        }
        for (Map<String, Object> e : incident.getEvents() == null ? List.<Map<String, Object>>of() : incident.getEvents()) {
            String type = String.valueOf(e.get("type"));
            Object by = e.get("by");
            entries.add(new IncidentDetailResponse.TimelineEntry(Instant.parse(String.valueOf(e.get("at"))),
                    type, null, null, null, describe(type, e), by == null ? null : String.valueOf(by)));
        }
        entries.sort(Comparator.comparing(IncidentDetailResponse.TimelineEntry::at)
                .thenComparing(t -> t.anomalyId() == null ? 0L : t.anomalyId()));
        return entries;
    }
}
