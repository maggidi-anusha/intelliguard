package com.intelliguard.controller;

import com.intelliguard.dto.CopilotAskRequest;
import com.intelliguard.dto.IncidentDetailResponse;
import com.intelliguard.dto.IncidentNoteRequest;
import com.intelliguard.entity.Incident;
import com.intelliguard.entity.IncidentNote;
import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.ml.MlCopilotResponse;
import com.intelliguard.repository.IncidentRepository;
import com.intelliguard.service.CopilotService;
import com.intelliguard.service.IncidentDetailService;
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

import java.util.List;

// Incident API. Incidents themselves are created and updated only by IncidentCorrelationJob;
// any authenticated role may read them. The Phase 6A workspace adds two human writes, USER or
// ADMIN only (see SecurityConfig): notes and acknowledgement (IncidentWorkspaceService). The
// Phase 6B copilot (CopilotService) answers questions about one incident for any role.
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    static final int MAX_RESULTS = 100;

    private final IncidentRepository incidentRepository;
    private final IncidentWorkspaceService workspaceService;
    private final IncidentDetailService detailService;
    private final CopilotService copilotService;

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
        return detailService.get(id);
    }

    // The incident copilot: any authenticated role may ask; it reads only, nothing is stored.
    @PostMapping("/{id}/copilot/ask")
    public MlCopilotResponse ask(@PathVariable Long id, @RequestBody CopilotAskRequest request) {
        return copilotService.ask(id, request.getQuestion());
    }
}
