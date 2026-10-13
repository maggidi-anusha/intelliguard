package com.intelliguard.service;

import com.intelliguard.dto.IncidentDetailResponse;
import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.IncidentNote;
import com.intelliguard.ml.MlCopilotClient;
import com.intelliguard.ml.MlCopilotRequest;
import com.intelliguard.ml.MlCopilotResponse;
import com.intelliguard.repository.SecurityEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The incident copilot (Phase 6B): validates the question, builds this incident's context from the
// incident detail (IncidentDetailService) plus its security events, and asks ml-service, a
// retrieval-and-template assistant over that context and the runbooks. Nothing is stored. If
// ml-service doesn't answer, this request gets a 503; the rest of the workspace is unaffected.
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class CopilotService {

    public static final int MAX_QUESTION = 500;
    static final int MAX_TIMELINE = 40;
    static final int MAX_ANOMALIES = 100;
    static final int MAX_NOTES = 20;
    static final Duration SECURITY_LEAD = Duration.ofSeconds(60);
    static final String UNAVAILABLE = "The incident copilot is unavailable right now (ml-service did not answer). "
            + "The rest of the incident workspace still works.";

    private final IncidentDetailService detailService;
    private final SecurityEventRepository securityEventRepository;
    private final MlCopilotClient client;

    public MlCopilotResponse ask(Long incidentId, String rawQuestion) {
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        if (question.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question must not be blank");
        }
        if (question.length() > MAX_QUESTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Question must be at most " + MAX_QUESTION + " characters (got " + question.length() + ")");
        }
        IncidentDetailResponse detail = detailService.get(incidentId); // 404 for an unknown incident
        return client.ask(new MlCopilotRequest(question, buildContext(detail)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE));
    }

    MlCopilotRequest.Context buildContext(IncidentDetailResponse d) {
        Map<Long, String> names = new HashMap<>();
        List<MlCopilotRequest.ServiceCtx> services = new ArrayList<>();
        for (IncidentDetailResponse.ServiceEntry s : d.services()) {
            names.put(s.serviceId(), s.name());
            services.add(new MlCopilotRequest.ServiceCtx(s.serviceId(), s.name(), str(s.type()), str(s.criticality()),
                    str(s.currentRiskLevel()), s.currentRiskScore()));
        }

        IncidentDetailResponse.RootCause rc = d.rootCause();
        List<MlCopilotRequest.Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> c : rc.candidates()) {
            candidates.add(new MlCopilotRequest.Candidate(asLong(c.get("serviceId")),
                    c.get("serviceName") == null ? null : String.valueOf(c.get("serviceName")),
                    c.get("rank") instanceof Number n ? n.intValue() : null,
                    c.get("score") instanceof Number n ? n.doubleValue() : null,
                    c.get("evidence") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of()));
        }

        List<MlCopilotRequest.AnomalyCtx> anomalies = new ArrayList<>();
        for (Anomaly a : d.anomalies().subList(0, Math.min(d.anomalies().size(), MAX_ANOMALIES))) {
            anomalies.add(new MlCopilotRequest.AnomalyCtx(a.getId(), names.getOrDefault(a.getServiceId(), "service " + a.getServiceId()),
                    str(a.getMetricType()), str(a.getDetector()), str(a.getStatus()), a.getDetectedAt(), a.getEndedAt(),
                    a.getAnomalyScore()));
        }

        List<IncidentDetailResponse.TimelineEntry> tl = d.timeline();
        List<MlCopilotRequest.TimelineCtx> timeline = tl.subList(Math.max(0, tl.size() - MAX_TIMELINE), tl.size()).stream()
                .map(t -> new MlCopilotRequest.TimelineCtx(t.at(), t.type(), t.serviceName(), t.detail(), t.by())).toList();

        List<IncidentNote> allNotes = d.notes();
        List<MlCopilotRequest.NoteCtx> notes = allNotes.subList(Math.max(0, allNotes.size() - MAX_NOTES), allNotes.size())
                .stream().map(n -> new MlCopilotRequest.NoteCtx(n.getAuthor(), n.getText(), n.getCreatedAt())).toList();

        List<MlCopilotRequest.SecurityCtx> security = new ArrayList<>();
        if (!names.isEmpty() && d.startedAt() != null) {
            Instant to = d.lastActivityAt() != null ? d.lastActivityAt() : Instant.now();
            for (Object[] row : securityEventRepository.countByServiceAndTypeInWindow(names.keySet(),
                    d.startedAt().minus(SECURITY_LEAD), to)) {
                Long serviceId = asLong(row[0]);
                security.add(new MlCopilotRequest.SecurityCtx(names.get(serviceId), String.valueOf(row[1]),
                        ((Number) row[2]).longValue()));
            }
        }

        return new MlCopilotRequest.Context(
                new MlCopilotRequest.Summary(d.id(), d.title(), str(d.status()), str(d.severity()), str(d.peakSeverity()),
                        d.startedAt(), d.lastActivityAt(), d.resolvedAt(), d.acknowledgedBy()),
                new MlCopilotRequest.RootCause(rc.confidence() instanceof Number n ? n.doubleValue() : null, candidates),
                services, anomalies, timeline, notes, security);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
