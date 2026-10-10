package com.intelliguard.controller;

import com.intelliguard.entity.RiskScore;
import com.intelliguard.entity.Service;
import com.intelliguard.repository.RiskScoreRepository;
import com.intelliguard.repository.ServiceRepository;
import com.intelliguard.risk.RiskSnapshot;
import com.intelliguard.risk.RiskSnapshotStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

// Read-only risk API. /current = the latest scoring cycle per service (in memory), falling back
// to the last persisted row after a restart; /history = persisted rows, newest first, capped.
@RestController
@RequestMapping("/api/risk")
@RequiredArgsConstructor
public class RiskController {

    static final int MAX_HISTORY = 500;

    private final ServiceRepository serviceRepository;
    private final RiskScoreRepository riskScoreRepository;
    private final RiskSnapshotStore snapshotStore;

    @GetMapping("/current")
    public List<RiskSnapshot> current() {
        return serviceRepository.findAll().stream()
                .sorted(Comparator.comparing(Service::getId))
                .map(this::currentFor)
                .toList();
    }

    @GetMapping("/history")
    public List<RiskScore> history(@RequestParam(required = false) Long serviceId,
                                   @RequestParam(defaultValue = "" + MAX_HISTORY) int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_HISTORY));
        return riskScoreRepository.findServiceHistory(serviceId, PageRequest.of(0, capped));
    }

    private RiskSnapshot currentFor(Service s) {
        return snapshotStore.get(s.getId())
                .or(() -> riskScoreRepository.findTopByServiceIdOrderByCalculatedAtDesc(s.getId())
                        .map(r -> fromRow(s, r)))
                .orElseGet(() -> new RiskSnapshot(s.getId(), s.getName(), s.getCriticality(), null, null, false, true,
                        "not scored yet", null, null, List.of(), List.of(), null));
    }

    @SuppressWarnings("unchecked")
    private static RiskSnapshot fromRow(Service s, RiskScore r) {
        Map<String, Object> b = r.getBreakdown() == null ? Map.of() : r.getBreakdown();
        return new RiskSnapshot(s.getId(), s.getName(), s.getCriticality(), r.getScore(), r.getLevel(),
                Boolean.TRUE.equals(b.get("mlAvailable")), false, "from last persisted row (no cycle since restart)",
                r.getCalculatedAt(), null,
                (List<String>) b.getOrDefault("l1FlaggedMetrics", List.of()),
                (List<String>) b.getOrDefault("l0ViolatedMetrics", List.of()), b);
    }
}
