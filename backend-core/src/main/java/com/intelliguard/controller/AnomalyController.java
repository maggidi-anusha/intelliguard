package com.intelliguard.controller;

import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.repository.AnomalyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Read-only view of DETECTED anomaly episodes (L0 thresholds and ml-service L1). Written only
// by RiskScoringJob; there is deliberately no write endpoint.
@RestController
@RequestMapping("/api/anomalies")
@RequiredArgsConstructor
public class AnomalyController {

    static final int MAX_RESULTS = 200;

    private final AnomalyRepository anomalyRepository;

    @GetMapping
    public List<Anomaly> find(@RequestParam(required = false) Long serviceId,
                              @RequestParam(required = false) AnomalyStatus status,
                              @RequestParam(defaultValue = "" + MAX_RESULTS) int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_RESULTS));
        return anomalyRepository.findFiltered(serviceId, status, PageRequest.of(0, capped));
    }
}
