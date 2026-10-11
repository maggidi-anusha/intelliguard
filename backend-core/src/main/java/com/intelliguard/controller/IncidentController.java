package com.intelliguard.controller;

import com.intelliguard.dto.IncidentDetailResponse;
import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.Incident;
import com.intelliguard.entity.RiskScore;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.entity.enums.RiskLevel;
import com.intelliguard.repository.AnomalyRepository;
import com.intelliguard.repository.IncidentRepository;
import com.intelliguard.repository.RiskScoreRepository;
import com.intelliguard.repository.ServiceRepository;
import com.intelliguard.risk.RiskSnapshot;
import com.intelliguard.risk.RiskSnapshotStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// Read-only incident API. Incidents are written only by IncidentCorrelationJob; there is
// deliberately no write endpoint. Any authenticated role may read (see SecurityConfig).
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    static final int MAX_RESULTS = 100;

    private final IncidentRepository incidentRepository;
    private final AnomalyRepository anomalyRepository;
    private final ServiceRepository serviceRepository;
    private final RiskScoreRepository riskScoreRepository;
    private final RiskSnapshotStore snapshotStore;

    @GetMapping
    public List<Incident> find(@RequestParam(required = false) IncidentStatus status,
                               @RequestParam(defaultValue = "" + MAX_RESULTS) int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_RESULTS));
        return incidentRepository.findFiltered(status, PageRequest.of(0, capped));
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

        return new IncidentDetailResponse(incident.getId(), incident.getTitle(), incident.getStatus(),
                incident.getSeverity(), incident.getPeakSeverity(), incident.getStartedAt(), incident.getLastActivityAt(),
                incident.getResolvedAt(), incident.getCreatedAt(), incident.getMergedIntoId(), incident.getMethod(),
                rootCause(incident, services), serviceEntries, anomalies, timeline(incident, anomalies, services));
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
                    a.getServiceId(), name, what));
            if (a.getEndedAt() != null) {
                entries.add(new IncidentDetailResponse.TimelineEntry(a.getEndedAt(), "ANOMALY_END", a.getId(),
                        a.getServiceId(), name, what));
            }
        }
        for (Map<String, Object> e : incident.getEvents() == null ? List.<Map<String, Object>>of() : incident.getEvents()) {
            Map<String, Object> details = new LinkedHashMap<>(e);
            details.remove("at");
            Object type = details.remove("type");
            entries.add(new IncidentDetailResponse.TimelineEntry(Instant.parse(String.valueOf(e.get("at"))),
                    String.valueOf(type), null, null, null, details.isEmpty() ? null : details.toString()));
        }
        entries.sort(Comparator.comparing(IncidentDetailResponse.TimelineEntry::at)
                .thenComparing(t -> t.anomalyId() == null ? 0L : t.anomalyId()));
        return entries;
    }
}
