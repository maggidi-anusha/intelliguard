package com.intelliguard.risk;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// The most recent RiskSnapshot per service, refreshed every scoring cycle. Kept in memory so
// /api/risk/current reflects the latest cycle even when that cycle's row wasn't persisted
// (rows are throttled - see RiskScoringJob). Empty after a restart until the first cycle.
@Component
public class RiskSnapshotStore {

    private final Map<Long, RiskSnapshot> latest = new ConcurrentHashMap<>();

    public void put(RiskSnapshot snapshot) {
        latest.put(snapshot.serviceId(), snapshot);
    }

    public Optional<RiskSnapshot> get(Long serviceId) {
        return Optional.ofNullable(latest.get(serviceId));
    }
}
