package com.intelliguard.controller;

import com.intelliguard.dto.GroundTruthEventRequest;
import com.intelliguard.entity.GroundTruthEvent;
import com.intelliguard.service.GroundTruthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Ground-truth injection labels for evaluation. ADMIN-only in both directions (see
// SecurityConfig): writes come from the simulator, reads are for offline evaluation.
@RestController
@RequestMapping("/api/ground-truth")
@RequiredArgsConstructor
public class GroundTruthController {

    private final GroundTruthService groundTruthService;

    @PostMapping
    public ResponseEntity<GroundTruthEvent> record(@Valid @RequestBody GroundTruthEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groundTruthService.record(request));
    }

    @GetMapping
    public List<GroundTruthEvent> find(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Long serviceId) {
        return groundTruthService.find(runId, serviceId);
    }
}
