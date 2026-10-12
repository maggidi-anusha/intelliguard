package com.intelliguard.service;

import com.intelliguard.entity.Incident;
import com.intelliguard.entity.IncidentNote;
import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.repository.IncidentNoteRepository;
import com.intelliguard.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

// The human side of an incident (Phase 6A): notes and acknowledgement. Each write also appends
// a NOTE_ADDED / ACKNOWLEDGED event (with the username) to the incident's history. The
// correlation job writes the same incident row, so a write that loses the optimistic-lock race
// (Incident.version) is retried a few times instead of overwriting the job's changes.
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class IncidentWorkspaceService {

    public static final int MAX_NOTE_LENGTH = 2000;
    private static final int ATTEMPTS = 3;

    private final IncidentRepository incidentRepository;
    private final IncidentNoteRepository noteRepository;
    private final TransactionTemplate transactionTemplate;

    public IncidentNote addNote(Long incidentId, String rawText, String author) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Note text must not be blank");
        }
        if (text.length() > MAX_NOTE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Note text must be at most " + MAX_NOTE_LENGTH + " characters (got " + text.length() + ")");
        }
        return withRetry(() -> {
            Incident incident = load(incidentId);
            rejectMerged(incident, "add notes");
            IncidentNote note = noteRepository.save(IncidentNote.builder()
                    .incidentId(incidentId).author(author).text(text).build());
            addEvent(incident, "NOTE_ADDED", Map.of("by", author, "noteId", note.getId()));
            incidentRepository.save(incident);
            return note;
        });
    }

    /** OPEN -> INVESTIGATING with who/when. Idempotent: an INVESTIGATING incident is returned unchanged. */
    public Incident acknowledge(Long incidentId, String username) {
        return withRetry(() -> {
            Incident incident = load(incidentId);
            rejectMerged(incident, "acknowledge it");
            if (incident.getStatus() == IncidentStatus.INVESTIGATING) {
                return incident;
            }
            if (incident.getStatus() == IncidentStatus.RESOLVED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Incident " + incidentId + " is already resolved; there is nothing to acknowledge");
            }
            Instant now = Instant.now();
            incident.setStatus(IncidentStatus.INVESTIGATING);
            incident.setAcknowledgedBy(username);
            incident.setAcknowledgedAt(now);
            addEvent(incident, "ACKNOWLEDGED", Map.of("by", username));
            return incidentRepository.save(incident);
        });
    }

    private Incident load(Long incidentId) {
        return incidentRepository.findById(incidentId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident " + incidentId + " not found"));
    }

    private static void rejectMerged(Incident incident, String action) {
        if (incident.getStatus() == IncidentStatus.MERGED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Incident " + incident.getId()
                    + " was merged into incident " + incident.getMergedIntoId() + "; " + action + " there instead");
        }
    }

    private static void addEvent(Incident incident, String type, Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("at", Instant.now().toString());
        event.put("type", type);
        event.putAll(details);
        List<Map<String, Object>> events = new ArrayList<>(incident.getEvents() == null ? List.of() : incident.getEvents());
        events.add(event);
        incident.setEvents(events);
    }

    private <T> T withRetry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> work.get());
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= ATTEMPTS) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The incident was being updated at the same time; please try again");
                }
            }
        }
    }
}
