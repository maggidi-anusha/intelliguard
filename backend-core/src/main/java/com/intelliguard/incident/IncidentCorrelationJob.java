package com.intelliguard.incident;

import com.intelliguard.config.IncidentProperties;
import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.Incident;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.DetectorType;
import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.entity.enums.Severity;
import com.intelliguard.ml.MlCorrelateRequest;
import com.intelliguard.ml.MlCorrelateResponse;
import com.intelliguard.ml.MlCorrelationClient;
import com.intelliguard.repository.AnomalyRepository;
import com.intelliguard.repository.IncidentRepository;
import com.intelliguard.repository.SecurityEventRepository;
import com.intelliguard.repository.ServiceDependencyRepository;
import com.intelliguard.repository.ServiceRepository;
import com.intelliguard.risk.RiskSnapshot;
import com.intelliguard.risk.RiskSnapshotStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

// Every cycle (15 s by default, fixed delay):
//   1. collect anomaly episodes that are OPEN, not yet linked and ended within the look-back
//      (10 min), or belong to an incident that is OPEN or was resolved within the look-back
//   2. send them to ml-service POST /correlate with the dependency graph (service_dependencies),
//      service criticality and per-service security-event counts; if ml-service doesn't answer
//      (down, slow, error) the cycle is skipped and nothing is written
//   3. map each returned group onto a persistent Incident with a stable identity:
//        - a group whose anomalies belong to no incident yet -> a new incident
//        - incidents are matched by overlapping anomaly ids, oldest incident first; each claims
//          the group holding most of its anomalies (the larger part keeps the identity on a split;
//          the other part becomes a new incident, recorded as SPLIT_TO / SPLIT_FROM)
//        - an incident whose groups were all claimed by older incidents is MERGED into the one
//          holding most of its anomalies (recorded as MERGED_INTO / MERGED_FROM)
//   4. lifecycle: OPEN while any linked anomaly is OPEN or its quiet period (5 min after the
//      last flagged sample) is running; RESOLVED after that; a RESOLVED incident is frozen until
//      a new anomaly joins it, which reopens it (REOPENED)
// The grouping and root-cause ranking are ml-service's heuristic (not a learned model); this job
// only persists them. Ground truth is never read here.
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentCorrelationJob {

    private static final int RETENTION_EVERY_N_CYCLES = 40;
    // Security events this long before a service's first anomaly in the window still count for it.
    private static final Duration SECURITY_LEAD = Duration.ofSeconds(60);

    private final AnomalyRepository anomalyRepository;
    private final IncidentRepository incidentRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceDependencyRepository dependencyRepository;
    private final SecurityEventRepository securityEventRepository;
    private final MlCorrelationClient mlClient;
    private final RiskSnapshotStore riskSnapshots;
    private final IncidentProperties props;
    private final TransactionTemplate transactionTemplate;

    private int cycles;

    @Scheduled(initialDelayString = "${intelliguard.incidents.initial-delay-ms:20000}",
            fixedDelayString = "${intelliguard.incidents.interval-ms:15000}")
    public void scheduledCycle() {
        if (!props.enabled()) {
            return;
        }
        try {
            runCycle(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Incident correlation cycle failed: {}", e.toString());
        }
    }

    /** One cycle. Returns false when it was skipped because ml-service did not answer. */
    public boolean runCycle(Instant now) {
        if (cycles++ % RETENTION_EVERY_N_CYCLES == 0) {
            applyRetention(now);
        }
        List<Anomaly> candidates = loadCandidates(now);
        if (candidates.isEmpty()) {
            return true;
        }
        Map<Long, Service> services = serviceRepository.findAll().stream()
                .collect(Collectors.toMap(Service::getId, Function.identity()));
        Optional<MlCorrelateResponse> response = mlClient.correlate(buildRequest(candidates, services));
        if (response.isEmpty() || response.get().incidents() == null) {
            log.warn("Incident correlation skipped this cycle: no answer from ml-service ({} episodes pending)",
                    candidates.size());
            return false;
        }
        transactionTemplate.executeWithoutResult(status -> apply(response.get(), candidates, services, now));
        return true;
    }

    // -- input ---------------------------------------------------------------------------------

    private List<Anomaly> loadCandidates(Instant now) {
        List<Anomaly> all = anomalyRepository.findCorrelationCandidates(now.minus(props.lookBack())).stream()
                .filter(a -> a.getServiceId() != null && a.getDetectedAt() != null && a.getStatus() != null
                        && a.getDetector() != null)
                .sorted(Comparator.comparing(Anomaly::getDetectedAt).reversed().thenComparing(Anomaly::getId))
                .toList();
        if (all.size() > props.maxEpisodes()) {
            log.warn("Incident correlation: {} episodes in the window, only the newest {} are sent",
                    all.size(), props.maxEpisodes());
            all = all.subList(0, props.maxEpisodes());
        }
        List<Anomaly> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(Anomaly::getDetectedAt).thenComparing(Anomaly::getId));
        return sorted;
    }

    MlCorrelateRequest buildRequest(List<Anomaly> candidates, Map<Long, Service> services) {
        List<MlCorrelateRequest.Episode> episodes = candidates.stream().map(a -> new MlCorrelateRequest.Episode(
                a.getId(), a.getServiceId(), a.getMetricType() == null ? null : a.getMetricType().name(),
                a.getDetector().name(), a.getDetectedAt(), a.getStatus() == AnomalyStatus.CLOSED ? endOf(a) : null,
                peakScore(a))).toList();
        List<MlCorrelateRequest.Edge> edges = dependencyRepository.findAll().stream()
                .map(d -> new MlCorrelateRequest.Edge(d.getServiceId(), d.getDependsOnServiceId())).toList();
        Map<Long, String> criticality = new TreeMap<>();
        services.values().forEach(s -> criticality.put(s.getId(), s.getCriticality().name()));

        // Security events of each anomalous service since (its first anomaly in the window - 60 s).
        Map<Long, Instant> firstOnset = new TreeMap<>();
        candidates.forEach(a -> firstOnset.merge(a.getServiceId(), a.getDetectedAt(), (x, y) -> x.isBefore(y) ? x : y));
        Map<Long, Long> security = new TreeMap<>();
        firstOnset.forEach((serviceId, onset) -> {
            long count = securityEventRepository.countByServiceIdAndTimestampAfter(serviceId, onset.minus(SECURITY_LEAD));
            if (count > 0) {
                security.put(serviceId, count);
            }
        });
        return new MlCorrelateRequest(episodes, edges, criticality, security, props.profile());
    }

    // ml-service compares peaks within an incident, so both detectors are put on the L1 scale
    // (0-1, 0.5 = threshold): L0 stores value/limit (1.0 = threshold), so L0 -> 0.5 x ratio, max 1.
    static double peakScore(Anomaly a) {
        double score = a.getAnomalyScore() == null || !Double.isFinite(a.getAnomalyScore()) ? 0.0 : a.getAnomalyScore();
        double scaled = a.getDetector() == DetectorType.L0 ? 0.5 * score : score;
        return Math.max(0.0, Math.min(1.0, scaled));
    }

    private static Instant endOf(Anomaly a) {
        Instant end = a.getEndedAt() != null ? a.getEndedAt() : lastSeen(a);
        return end.isBefore(a.getDetectedAt()) ? a.getDetectedAt() : end;
    }

    private static Instant lastSeen(Anomaly a) {
        if (a.getLastFlaggedAt() != null) {
            return a.getLastFlaggedAt();
        }
        return a.getEndedAt() != null ? a.getEndedAt() : a.getDetectedAt();
    }

    // -- persistence -----------------------------------------------------------------------------

    void apply(MlCorrelateResponse response, List<Anomaly> candidates, Map<Long, Service> services, Instant now) {
        Map<Long, Anomaly> byId = candidates.stream().collect(Collectors.toMap(Anomaly::getId, Function.identity()));
        List<List<Long>> groups = new ArrayList<>();
        List<MlCorrelateResponse.Group> groupInfo = new ArrayList<>();
        for (MlCorrelateResponse.Group g : response.incidents()) {
            List<Long> ids = g.episodeIds() == null ? List.of() : g.episodeIds().stream().filter(byId::containsKey).toList();
            if (!ids.isEmpty()) {
                groups.add(ids);
                groupInfo.add(g);
            }
        }

        // Which incident each anomaly belonged to before this cycle.
        Map<Long, Long> previous = new HashMap<>();
        candidates.forEach(a -> {
            if (a.getIncidentId() != null) {
                previous.put(a.getId(), a.getIncidentId());
            }
        });
        Map<Long, Incident> existing = new HashMap<>();
        incidentRepository.findAllById(new HashSet<>(previous.values())).stream()
                .filter(i -> i.getStatus() != IncidentStatus.MERGED)
                .forEach(i -> existing.put(i.getId(), i));

        // How many of each incident's anomalies landed in each group.
        Map<Long, Map<Integer, Integer>> share = new HashMap<>();
        for (int g = 0; g < groups.size(); g++) {
            for (Long id : groups.get(g)) {
                Long incidentId = previous.get(id);
                if (incidentId != null && existing.containsKey(incidentId)) {
                    share.computeIfAbsent(incidentId, k -> new HashMap<>()).merge(g, 1, Integer::sum);
                }
            }
        }

        // Oldest incident first: claim the group holding most of its anomalies (ties: larger
        // group, then earlier group). Nothing left to claim -> merged into an older incident.
        Map<Integer, Incident> owner = new HashMap<>();
        List<Incident> byAge = existing.values().stream()
                .sorted(Comparator.comparing(IncidentCorrelationJob::ageKey).thenComparing(Incident::getId)).toList();
        for (Incident incident : byAge) {
            Map<Integer, Integer> mine = share.getOrDefault(incident.getId(), Map.of());
            if (mine.isEmpty()) {
                continue; // none of its anomalies are in this cycle's input
            }
            Comparator<Integer> best = Comparator.<Integer>comparingInt(mine::get)
                    .thenComparingInt(g -> groups.get(g).size())
                    .thenComparing(g -> -g);
            Optional<Integer> free = mine.keySet().stream().filter(g -> !owner.containsKey(g)).max(best);
            if (free.isPresent()) {
                owner.put(free.get(), incident);
            } else {
                Incident keeper = owner.get(mine.keySet().stream().max(best).orElseThrow());
                merge(incident, keeper, now);
            }
        }

        Set<Long> refreshed = new HashSet<>();
        for (int g = 0; g < groups.size(); g++) {
            Incident incident = owner.get(g);
            if (incident == null && ownedByOneResolvedIncident(groups.get(g), previous, existing)) {
                continue; // a RESOLVED incident is never split by regrouping alone: nothing new joined
            }
            if (incident == null) {
                incident = incidentRepository.save(Incident.builder()
                        .title("new incident").status(IncidentStatus.OPEN).startedAt(now).lastActivityAt(now)
                        .events(new ArrayList<>(List.of(event(now, "CREATED", Map.of())))).build());
            }
            Long incidentId = incident.getId();
            List<Long> joining = groups.get(g).stream().filter(id -> !incidentId.equals(previous.get(id))).toList();
            recordMoves(incident, joining, previous, existing, now);
            if (!joining.isEmpty()) {
                anomalyRepository.linkToIncident(incidentId, joining);
            }
            if (incident.getStatus() == IncidentStatus.RESOLVED && joining.isEmpty()) {
                continue; // frozen: nothing new joined
            }
            refresh(incident, groupInfo.get(g), response, services, now);
            refreshed.add(incidentId);
        }

        // Defensive: an OPEN incident that got no group this cycle still has its status re-derived.
        for (Incident open : incidentRepository.findByStatus(IncidentStatus.OPEN)) {
            if (!refreshed.contains(open.getId())) {
                updateLifecycle(open, anomalyRepository.findByIncidentIdOrderByDetectedAtAscIdAsc(open.getId()), now);
                incidentRepository.save(open);
            }
        }
    }

    private static boolean ownedByOneResolvedIncident(List<Long> group, Map<Long, Long> previous,
                                                      Map<Long, Incident> existing) {
        Set<Long> owners = new HashSet<>();
        for (Long id : group) {
            owners.add(previous.get(id));
        }
        if (owners.size() != 1 || owners.contains(null)) {
            return false;
        }
        Incident only = existing.get(owners.iterator().next());
        return only != null && only.getStatus() == IncidentStatus.RESOLVED;
    }

    private void merge(Incident absorbed, Incident keeper, Instant now) {
        absorbed.setStatus(IncidentStatus.MERGED);
        absorbed.setMergedIntoId(keeper.getId());
        addEvent(absorbed, event(now, "MERGED_INTO", Map.of("incidentId", keeper.getId())));
        addEvent(keeper, event(now, "MERGED_FROM", Map.of("incidentId", absorbed.getId())));
        // Members outside this cycle's input (if any) follow it too; the rest are relinked per group.
        List<Long> all = anomalyRepository.findByIncidentIdOrderByDetectedAtAscIdAsc(absorbed.getId()).stream()
                .map(Anomaly::getId).toList();
        if (!all.isEmpty()) {
            anomalyRepository.linkToIncident(keeper.getId(), all);
        }
        incidentRepository.save(absorbed);
        log.info("Incident {} merged into older incident {}", absorbed.getId(), keeper.getId());
    }

    // Anomalies moving here from another live incident (not one just merged into this one) = a split.
    private void recordMoves(Incident receiver, List<Long> joining, Map<Long, Long> previous,
                             Map<Long, Incident> existing, Instant now) {
        Map<Long, List<Long>> byDonor = new TreeMap<>();
        for (Long id : joining) {
            Long donor = previous.get(id);
            if (donor != null && existing.containsKey(donor)) {
                byDonor.computeIfAbsent(donor, k -> new ArrayList<>()).add(id);
            }
        }
        byDonor.forEach((donorId, ids) -> {
            Incident donor = existing.get(donorId);
            if (donor.getStatus() == IncidentStatus.MERGED && receiver.getId().equals(donor.getMergedIntoId())) {
                return;
            }
            addEvent(receiver, event(now, "SPLIT_FROM", Map.of("incidentId", donorId, "anomalyIds", ids)));
            addEvent(donor, event(now, "SPLIT_TO", Map.of("incidentId", receiver.getId(), "anomalyIds", ids)));
            incidentRepository.save(donor);
            log.info("Incident {} split: anomalies {} moved to incident {}", donorId, ids, receiver.getId());
        });
    }

    private void refresh(Incident incident, MlCorrelateResponse.Group group, MlCorrelateResponse response,
                         Map<Long, Service> services, Instant now) {
        List<Anomaly> linked = anomalyRepository.findByIncidentIdOrderByDetectedAtAscIdAsc(incident.getId());
        List<Long> serviceIds = linked.stream().map(Anomaly::getServiceId).distinct().sorted().toList();
        incident.setServiceIds(new ArrayList<>(serviceIds));
        linked.stream().map(Anomaly::getDetectedAt).filter(Objects::nonNull).min(Comparator.naturalOrder())
                .ifPresent(incident::setStartedAt);

        Map<String, Object> rootCause = new LinkedHashMap<>(group.rootCause() == null ? Map.of() : group.rootCause());
        rootCause.put("confidenceMethod", response.confidenceMethod());
        rootCause.put("profile", response.profile());
        rootCause.put("params", response.params());
        rootCause.put("rankedAt", now.toString());
        incident.setRootCause(rootCause);
        incident.setMethod(response.method());

        Optional<Map<?, ?>> top = firstCandidate(rootCause);
        Long rootId = top.map(c -> c.get("serviceId")).filter(Number.class::isInstance)
                .map(n -> ((Number) n).longValue()).orElse(null);
        incident.setRootServiceId(rootId);
        String rootName = name(services, rootId);
        incident.setTitle(serviceIds.size() <= 1 ? rootName
                : rootName + " + " + (serviceIds.size() - 1) + " related service" + (serviceIds.size() > 2 ? "s" : ""));
        incident.setRootCauseSuggestion(top.map(c -> String.format("%s ranked first of %d (score %s, confidence %s)",
                rootName, candidateCount(rootCause), c.get("score"), rootCause.get("confidence"))).orElse(null));

        updateLifecycle(incident, linked, now);
        incidentRepository.save(incident);
    }

    private void updateLifecycle(Incident incident, List<Anomaly> linked, Instant now) {
        boolean anyOpen = linked.stream().anyMatch(a -> a.getStatus() == AnomalyStatus.OPEN);
        Instant last = linked.stream().map(IncidentCorrelationJob::lastSeen).max(Comparator.naturalOrder())
                .orElse(incident.getLastActivityAt() != null ? incident.getLastActivityAt() : now);
        incident.setLastActivityAt(last);
        IncidentStatus before = incident.getStatus();

        if (anyOpen || now.isBefore(last.plus(props.quietPeriod()))) {
            incident.setStatus(IncidentStatus.OPEN);
            incident.setResolvedAt(null);
            if (before == IncidentStatus.RESOLVED) {
                addEvent(incident, event(now, "REOPENED", Map.of()));
                log.info("Incident {} reopened: a new anomaly joined", incident.getId());
            }
            Severity current = currentSeverity(incident.getServiceIds());
            if (current != null) {
                incident.setSeverity(current);
                if (incident.getPeakSeverity() == null || current.compareTo(incident.getPeakSeverity()) > 0) {
                    incident.setPeakSeverity(current);
                }
            }
        } else if (before != IncidentStatus.RESOLVED) {
            incident.setStatus(IncidentStatus.RESOLVED);
            incident.setResolvedAt(now);
            addEvent(incident, event(now, "RESOLVED", Map.of("quietSince", last.toString())));
            log.info("Incident {} resolved: all anomalies closed since {}", incident.getId(), last);
        }
    }

    private Severity currentSeverity(List<Long> serviceIds) {
        if (serviceIds == null) {
            return null;
        }
        return serviceIds.stream().map(riskSnapshots::get).flatMap(Optional::stream).map(RiskSnapshot::level)
                .filter(Objects::nonNull).max(Comparator.naturalOrder())
                .map(level -> Severity.valueOf(level.name())).orElse(null);
    }

    private void applyRetention(Instant now) {
        transactionTemplate.executeWithoutResult(status -> {
            List<Long> expired = incidentRepository.findExpiredIds(now.minus(props.retention()));
            if (!expired.isEmpty()) {
                anomalyRepository.unlinkIncidents(expired);
                incidentRepository.deleteAllById(expired);
                log.info("Retention: deleted {} resolved/merged incidents", expired.size());
            }
        });
    }

    // -- helpers ---------------------------------------------------------------------------------

    private static Instant ageKey(Incident i) {
        if (i.getStartedAt() != null) {
            return i.getStartedAt();
        }
        return i.getCreatedAt() != null ? i.getCreatedAt() : Instant.MAX;
    }

    private static Map<String, Object> event(Instant at, String type, Map<String, Object> details) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at.toString());
        e.put("type", type);
        e.putAll(details);
        return e;
    }

    private static void addEvent(Incident incident, Map<String, Object> event) {
        List<Map<String, Object>> events = new ArrayList<>(incident.getEvents() == null ? List.of() : incident.getEvents());
        events.add(event);
        incident.setEvents(events);
    }

    private static Optional<Map<?, ?>> firstCandidate(Map<String, Object> rootCause) {
        if (rootCause.get("candidates") instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
            return Optional.of(m);
        }
        return Optional.empty();
    }

    private static int candidateCount(Map<String, Object> rootCause) {
        return rootCause.get("candidates") instanceof List<?> list ? list.size() : 0;
    }

    private static String name(Map<Long, Service> services, Long id) {
        Service s = id == null ? null : services.get(id);
        return s != null ? s.getName() : (id == null ? "unknown service" : "service " + id);
    }
}
