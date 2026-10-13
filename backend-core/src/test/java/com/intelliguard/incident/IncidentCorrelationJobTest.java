package com.intelliguard.incident;

import com.intelliguard.config.IncidentProperties;
import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.Incident;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.ServiceDependency;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.DetectorType;
import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.entity.enums.RiskLevel;
import com.intelliguard.entity.enums.ServiceType;
import com.intelliguard.entity.enums.Severity;
import com.intelliguard.entity.enums.SignalType;
import com.intelliguard.ml.MlCorrelateRequest;
import com.intelliguard.ml.MlCorrelateResponse;
import com.intelliguard.ml.MlCorrelationClient;
import com.intelliguard.repository.AnomalyRepository;
import com.intelliguard.repository.IncidentNoteRepository;
import com.intelliguard.repository.IncidentRepository;
import com.intelliguard.repository.SecurityEventRepository;
import com.intelliguard.repository.ServiceDependencyRepository;
import com.intelliguard.repository.ServiceRepository;
import com.intelliguard.risk.RiskSnapshot;
import com.intelliguard.risk.RiskSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The incident job against a mocked ml-service and in-memory repositories: the test scripts which
// anomaly ids ml-service groups together each cycle and checks what the job persists.
class IncidentCorrelationJobTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");

    private final Map<Long, Anomaly> anomalyRows = new LinkedHashMap<>();
    private final Map<Long, Incident> incidentRows = new LinkedHashMap<>();
    private long nextIncidentId = 100;

    private final AnomalyRepository anomalies = mock(AnomalyRepository.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentNoteRepository notes = mock(IncidentNoteRepository.class);
    private final ServiceRepository services = mock(ServiceRepository.class);
    private final ServiceDependencyRepository dependencies = mock(ServiceDependencyRepository.class);
    private final SecurityEventRepository security = mock(SecurityEventRepository.class);
    private final MlCorrelationClient ml = mock(MlCorrelationClient.class);
    private final RiskSnapshotStore risk = new RiskSnapshotStore();
    private IncidentCorrelationJob job;

    // What the mocked ml-service returns next: groups of anomaly ids (first id = rank-1 service).
    // Each call answers with the scripted groups restricted to the episodes it was sent, so the
    // per-incident ranking calls get back exactly their own group.
    private List<List<Long>> nextGroups = List.of();
    // Security events per service (timestamps), counted by the fake repository per window.
    private final Map<Long, List<Instant>> securityEvents = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        when(services.findAll()).thenReturn(List.of(service(1, "user-db", Criticality.CRITICAL),
                service(2, "order-db", Criticality.CRITICAL), service(6, "order-service", Criticality.HIGH),
                service(7, "api-gateway", Criticality.HIGH), service(8, "frontend-web", Criticality.MEDIUM)));
        when(dependencies.findAll()).thenReturn(List.of(edge(6, 2), edge(7, 6), edge(8, 7)));

        when(anomalies.findCorrelationCandidates(any())).thenAnswer(inv -> {
            Instant cutoff = inv.getArgument(0); // same rule as the repository query
            return anomalyRows.values().stream().filter(a -> {
                if (a.getStatus() == AnomalyStatus.OPEN) {
                    return true;
                }
                if (a.getIncidentId() == null) {
                    return a.getEndedAt() != null && !a.getEndedAt().isBefore(cutoff);
                }
                Incident i = incidentRows.get(a.getIncidentId());
                return i.getStatus() == IncidentStatus.OPEN || i.getStatus() == IncidentStatus.INVESTIGATING
                        || (i.getStatus() == IncidentStatus.RESOLVED && !i.getResolvedAt().isBefore(cutoff));
            }).toList();
        });
        when(anomalies.findByIncidentIdOrderByDetectedAtAscIdAsc(anyLong())).thenAnswer(inv -> anomalyRows.values()
                .stream().filter(a -> inv.getArgument(0).equals(a.getIncidentId())).toList());
        when(anomalies.linkToIncident(anyLong(), anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(1);
            ids.forEach(id -> anomalyRows.get(id).setIncidentId(inv.getArgument(0)));
            return ids.size();
        });
        when(incidents.save(any())).thenAnswer(inv -> {
            Incident i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId(nextIncidentId++);
                i.setCreatedAt(T0);
            }
            incidentRows.put(i.getId(), i);
            return i;
        });
        when(incidents.findAllById(any())).thenAnswer(inv -> {
            List<Incident> found = new ArrayList<>();
            ((Iterable<Long>) inv.getArgument(0)).forEach(id -> {
                if (incidentRows.containsKey(id)) {
                    found.add(incidentRows.get(id));
                }
            });
            return found;
        });
        when(incidents.findByStatusIn(any())).thenAnswer(inv -> incidentRows.values().stream()
                .filter(i -> ((Collection<?>) inv.getArgument(0)).contains(i.getStatus())).toList());
        when(incidents.findExpiredIds(any())).thenReturn(List.of());
        when(security.countByServiceIdAndTimestampBetween(anyLong(), any(), any())).thenAnswer(inv -> {
            Instant from = inv.getArgument(1);
            Instant to = inv.getArgument(2);
            return securityEvents.getOrDefault((Long) inv.getArgument(0), List.of()).stream()
                    .filter(t -> !t.isBefore(from) && !t.isAfter(to)).count();
        });
        when(ml.correlate(any())).thenAnswer(inv -> Optional.of(response(inv.getArgument(0))));

        IncidentProperties props = new IncidentProperties(true, 15_000, Duration.ofMinutes(10), Duration.ofMinutes(5),
                Duration.ofDays(7), "full_call_graph", 2000);
        job = new IncidentCorrelationJob(anomalies, incidents, notes, services, dependencies, security, ml, risk, props,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    @Test
    void aNewGroupBecomesAnOpenIncidentWithTheRankingAndLinkedAnomalies() {
        open(1, 2, 0);
        open(2, 6, 30);
        risk.put(snapshot(2, RiskLevel.HIGH));
        risk.put(snapshot(6, RiskLevel.MEDIUM));
        nextGroups = List.of(List.of(1L, 2L));

        assertThat(job.runCycle(T0.plusSeconds(60))).isTrue();

        assertThat(incidentRows).hasSize(1);
        Incident i = incidentRows.values().iterator().next();
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(i.getServiceIds()).containsExactly(2L, 6L);
        assertThat(i.getRootServiceId()).isEqualTo(2L);
        assertThat(i.getTitle()).isEqualTo("order-db + 1 related service");
        assertThat(i.getMethod()).contains("not a learned model");
        assertThat(i.getStartedAt()).isEqualTo(T0);
        assertThat(i.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(i.getPeakSeverity()).isEqualTo(Severity.HIGH);
        assertThat((List<?>) i.getRootCause().get("candidates")).hasSize(2);
        assertThat(i.getRootCause()).containsKeys("confidence", "confidenceMethod", "profile", "params");
        assertThat(i.getEvents()).extracting(e -> e.get("type")).containsExactly("CREATED");
        assertThat(anomalyRows.values()).allMatch(a -> i.getId().equals(a.getIncidentId()));
    }

    @Test
    void identityIsStableOverRepeatedCycles_andANewAnomalyJoinsTheSameIncident() {
        open(1, 2, 0);
        open(2, 6, 30);
        nextGroups = List.of(List.of(1L, 2L));
        job.runCycle(T0.plusSeconds(60));
        Long id = incidentRows.keySet().iterator().next();

        job.runCycle(T0.plusSeconds(75));
        job.runCycle(T0.plusSeconds(90));
        open(3, 7, 80);
        nextGroups = List.of(List.of(1L, 2L, 3L));
        job.runCycle(T0.plusSeconds(105));

        assertThat(incidentRows).containsOnlyKeys(id);
        assertThat(incidentRows.get(id).getServiceIds()).containsExactly(2L, 6L, 7L);
        assertThat(anomalyRows.get(3L).getIncidentId()).isEqualTo(id);
        assertThat(incidentRows.get(id).getEvents()).extracting(e -> e.get("type")).containsExactly("CREATED");
    }

    @Test
    void twoIncidentsThatBecomeConnected_mergeIntoTheOlderOne() {
        open(1, 2, 0);
        open(2, 8, 30);
        nextGroups = List.of(List.of(1L), List.of(2L));
        job.runCycle(T0.plusSeconds(60));
        Long older = anomalyRows.get(1L).getIncidentId();
        Long younger = anomalyRows.get(2L).getIncidentId();
        assertThat(older).isNotEqualTo(younger);

        open(3, 6, 50); // bridges the two
        open(4, 7, 55);
        nextGroups = List.of(List.of(1L, 3L, 4L, 2L));
        job.runCycle(T0.plusSeconds(75));

        Incident kept = incidentRows.get(older);
        Incident merged = incidentRows.get(younger);
        assertThat(kept.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(merged.getStatus()).isEqualTo(IncidentStatus.MERGED);
        assertThat(merged.getMergedIntoId()).isEqualTo(older);
        assertThat(anomalyRows.values()).allMatch(a -> older.equals(a.getIncidentId()));
        assertThat(kept.getServiceIds()).containsExactly(2L, 6L, 7L, 8L);
        assertThat(kept.getEvents()).anySatisfy(e -> {
            assertThat(e.get("type")).isEqualTo("MERGED_FROM");
            assertThat(e.get("incidentId")).isEqualTo(younger);
        });
        assertThat(merged.getEvents()).extracting(e -> e.get("type")).contains("MERGED_INTO");
        assertThat(incidentRows).hasSize(2); // no third incident
    }

    @Test
    void aSplitKeepsTheOldIdentityOnTheLargerPart() {
        open(1, 2, 0);
        open(2, 6, 30);
        open(3, 1, 40);
        nextGroups = List.of(List.of(1L, 2L, 3L));
        job.runCycle(T0.plusSeconds(60));
        Long original = anomalyRows.get(1L).getIncidentId();

        nextGroups = List.of(List.of(3L), List.of(1L, 2L)); // smaller part listed first on purpose
        job.runCycle(T0.plusSeconds(75));

        Long split = anomalyRows.get(3L).getIncidentId();
        assertThat(anomalyRows.get(1L).getIncidentId()).isEqualTo(original);
        assertThat(anomalyRows.get(2L).getIncidentId()).isEqualTo(original);
        assertThat(split).isNotEqualTo(original);
        assertThat(incidentRows.get(original).getServiceIds()).containsExactly(2L, 6L);
        assertThat(incidentRows.get(split).getServiceIds()).containsExactly(1L);
        assertThat(incidentRows.get(split).getEvents()).extracting(e -> e.get("type")).contains("CREATED", "SPLIT_FROM");
        assertThat(incidentRows.get(original).getEvents()).anySatisfy(e -> {
            assertThat(e.get("type")).isEqualTo("SPLIT_TO");
            assertThat(e.get("incidentId")).isEqualTo(split);
        });
    }

    @Test
    void resolvesOnlyAfterAllAnomaliesHaveBeenClosedForTheQuietPeriod() {
        closed(1, 2, 0, 120);
        open(2, 6, 30);
        nextGroups = List.of(List.of(1L, 2L));
        job.runCycle(T0.plusSeconds(150));
        Incident i = incidentRows.values().iterator().next();
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.OPEN); // one anomaly still open

        close(2, 180);
        job.runCycle(T0.plusSeconds(200));
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.OPEN); // all closed, quiet period running
        assertThat(i.getLastActivityAt()).isEqualTo(T0.plusSeconds(180));

        job.runCycle(T0.plusSeconds(180 + 299));
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.OPEN);

        job.runCycle(T0.plusSeconds(180 + 300));
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(i.getResolvedAt()).isEqualTo(T0.plusSeconds(480));
        assertThat(i.getEvents()).extracting(e -> e.get("type")).containsExactly("CREATED", "RESOLVED");
    }

    @Test
    void aResolvedIncidentIsFrozen_untilANewAnomalyJoinsAndReopensIt() {
        closed(1, 2, 0, 120);
        nextGroups = List.of(List.of(1L));
        job.runCycle(T0.plusSeconds(130));
        job.runCycle(T0.plusSeconds(420));
        Incident i = incidentRows.values().iterator().next();
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.RESOLVED);

        clearInvocations(incidents);
        job.runCycle(T0.plusSeconds(435)); // same group, nothing new
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        verify(incidents, never()).save(any());

        open(2, 6, 430);
        nextGroups = List.of(List.of(1L, 2L));
        job.runCycle(T0.plusSeconds(450));

        assertThat(incidentRows).hasSize(1);
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(i.getResolvedAt()).isNull();
        assertThat(i.getServiceIds()).containsExactly(2L, 6L);
        assertThat(i.getEvents()).extracting(e -> e.get("type")).containsExactly("CREATED", "RESOLVED", "REOPENED");
    }

    @Test
    void aResolvedIncidentDoesNotFallApartAsItsAnomaliesAgeOut() {
        // Live case: a chain 2 - 6 - 7 whose middle member ends first. If members left the input
        // one by one, 2 and 7 (2 hops apart) would come back as two groups and look like a split.
        closed(1, 2, 0, 100);
        closed(2, 6, 10, 60);
        closed(3, 7, 20, 110);
        nextGroups = List.of(List.of(1L, 2L, 3L));
        job.runCycle(T0.plusSeconds(120));
        job.runCycle(T0.plusSeconds(410));
        Incident i = incidentRows.values().iterator().next();
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.RESOLVED);

        clearInvocations(ml);
        job.runCycle(T0.plusSeconds(60 + 600 + 1)); // anomaly 2 alone would have left the look-back
        ArgumentCaptor<MlCorrelateRequest> sent = ArgumentCaptor.forClass(MlCorrelateRequest.class);
        verify(ml).correlate(sent.capture());
        assertThat(sent.getValue().episodes()).extracting(MlCorrelateRequest.Episode::id).containsExactly(1L, 2L, 3L);

        nextGroups = List.of(List.of(1L), List.of(3L), List.of(2L)); // even if regrouping splits it
        job.runCycle(T0.plusSeconds(700));
        assertThat(incidentRows).hasSize(1);
        assertThat(anomalyRows.values()).allMatch(a -> i.getId().equals(a.getIncidentId()));
        assertThat(i.getEvents()).extracting(e -> e.get("type")).containsExactly("CREATED", "RESOLVED");

        clearInvocations(ml);
        job.runCycle(T0.plusSeconds(410 + 600 + 1)); // resolved > look-back ago: all members leave together
        verify(ml, never()).correlate(any());
    }

    @Test
    void consecutiveIncidentsOnTheSameRootDoNotShareSecurityEvents() {
        // Live case (cascade-0002 / cascade-0003): an incident on order-db is still in its quiet
        // period when the next incident on order-db starts; only the second one had security events.
        closed(1, 2, 0, 500);
        closed(2, 6, 30, 500);
        nextGroups = List.of(List.of(1L, 2L));
        job.runCycle(T0.plusSeconds(520));
        Incident first = incidentRows.values().iterator().next();

        open(3, 2, 760);
        open(4, 6, 775);
        securityEvents.put(2L, List.of(T0.plusSeconds(755), T0.plusSeconds(765), T0.plusSeconds(770)));
        nextGroups = List.of(List.of(1L, 2L), List.of(3L, 4L));
        job.runCycle(T0.plusSeconds(790)); // first is still OPEN (quiet period until 800)

        Incident second = incidentRows.get(anomalyRows.get(3L).getIncidentId());
        assertThat(second).isNotSameAs(first);
        assertThat(first.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(securityOf(first, 2L)).isZero();   // was 3 when counted per service
        assertThat(securityOf(second, 2L)).isEqualTo(3L);
    }

    @Test
    void investigatingFollowsTheOpenLifecycle_andTheJobNeverTouchesAcknowledgementOrNotes() {
        closed(1, 2, 0, 120);
        open(2, 6, 30);
        nextGroups = List.of(List.of(1L, 2L));
        job.runCycle(T0.plusSeconds(150));
        Incident i = incidentRows.values().iterator().next();
        i.setStatus(IncidentStatus.INVESTIGATING); // acknowledged through the API
        i.setAcknowledgedBy("alice");
        i.setAcknowledgedAt(T0.plusSeconds(160));

        job.runCycle(T0.plusSeconds(165));
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.INVESTIGATING); // anomaly still open
        close(2, 180);
        job.runCycle(T0.plusSeconds(200));
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.INVESTIGATING); // quiet period running
        job.runCycle(T0.plusSeconds(480));
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.RESOLVED);       // still auto-resolves

        open(3, 7, 470);
        nextGroups = List.of(List.of(1L, 2L, 3L));
        job.runCycle(T0.plusSeconds(490));
        assertThat(i.getStatus()).isEqualTo(IncidentStatus.OPEN);           // reopened as OPEN
        assertThat(i.getEvents()).extracting(e -> e.get("type")).containsExactly("CREATED", "RESOLVED", "REOPENED");
        assertThat(i.getAcknowledgedBy()).isEqualTo("alice");
        assertThat(i.getAcknowledgedAt()).isEqualTo(T0.plusSeconds(160));
        verify(notes, never()).save(any());
    }

    @Test
    void afterTheStaleCleanup_aNewAnomalyStartsANewIncident_andTheOldOneResolves() {
        // Restart case: the old incident was still OPEN; its episode was closed at its last flag
        // 16 hours ago by StaleEpisodeCleanup. A new anomaly now must not join it.
        closed(1, 7, 0, 120);
        nextGroups = List.of(List.of(1L));
        job.runCycle(T0.plusSeconds(130));
        Incident old = incidentRows.values().iterator().next();
        assertThat(old.getStatus()).isEqualTo(IncidentStatus.OPEN);

        long later = 16 * 3600;
        open(2, 7, later);
        nextGroups = List.of(List.of(1L), List.of(2L)); // 16 h apart: separate groups
        job.runCycle(T0.plusSeconds(later + 30));

        assertThat(old.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(anomalyRows.get(2L).getIncidentId()).isNotEqualTo(old.getId()).isNotNull();
        assertThat(incidentRows).hasSize(2);
    }

    @Test
    void mlServiceDown_skipsTheCycleWithoutTouchingIncidents() {
        open(1, 2, 0);
        nextGroups = List.of(List.of(1L));
        job.runCycle(T0.plusSeconds(60));
        Incident i = incidentRows.values().iterator().next();
        List<Long> servicesBefore = List.copyOf(i.getServiceIds());

        doReturn(Optional.empty()).when(ml).correlate(any());
        open(2, 6, 70);
        clearInvocations(incidents, anomalies);

        assertThat(job.runCycle(T0.plusSeconds(75))).isFalse();

        verify(incidents, never()).save(any());
        verify(anomalies, never()).linkToIncident(anyLong(), anyCollection());
        assertThat(incidentRows).hasSize(1);
        assertThat(i.getServiceIds()).isEqualTo(servicesBefore);
        assertThat(anomalyRows.get(2L).getIncidentId()).isNull();

        doAnswer(inv -> Optional.of(response(inv.getArgument(0)))).when(ml).correlate(any());
        nextGroups = List.of(List.of(1L, 2L));
        assertThat(job.runCycle(T0.plusSeconds(90))).isTrue(); // resumes
        assertThat(anomalyRows.get(2L).getIncidentId()).isEqualTo(i.getId());
    }

    @Test
    void requestCarriesTheGraphCriticalitySecurityCountsAndPeaksOnOneScale() {
        Anomaly l0 = open(1, 6, 0);
        l0.setDetector(DetectorType.L0);
        l0.setAnomalyScore(1.2); // value / limit -> 0.6 on the L1 scale
        closed(2, 2, 10, 100).setAnomalyScore(0.93);
        securityEvents.put(2L, List.of(T0.plusSeconds(20), T0.plusSeconds(30), T0.plusSeconds(40),
                T0.plusSeconds(500))); // the last one is outside the incident's window
        nextGroups = List.of(List.of(2L, 1L));

        job.runCycle(T0.plusSeconds(120));

        ArgumentCaptor<MlCorrelateRequest> sent = ArgumentCaptor.forClass(MlCorrelateRequest.class);
        verify(ml, times(2)).correlate(sent.capture()); // grouping, then this incident's ranking
        assertThat(sent.getAllValues().get(0).securityEvents()).isEmpty();
        assertThat(sent.getAllValues().get(1).securityEvents()).containsExactly(Map.entry(2L, 3L));
        MlCorrelateRequest r = sent.getAllValues().get(0);
        assertThat(r.profile()).isEqualTo("full_call_graph");
        assertThat(r.dependencies()).containsExactly(new MlCorrelateRequest.Edge(6L, 2L),
                new MlCorrelateRequest.Edge(7L, 6L), new MlCorrelateRequest.Edge(8L, 7L));
        assertThat(r.criticality()).containsEntry(2L, "CRITICAL").containsEntry(6L, "HIGH").hasSize(5);
        assertThat(r.episodes()).satisfiesExactly(
                e -> {
                    assertThat(e.id()).isEqualTo(1L);
                    assertThat(e.detector()).isEqualTo("L0");
                    assertThat(e.peakScore()).isEqualTo(0.6);
                    assertThat(e.end()).isNull(); // still open
                },
                e -> {
                    assertThat(e.id()).isEqualTo(2L);
                    assertThat(e.peakScore()).isEqualTo(0.93);
                    assertThat(e.end()).isEqualTo(T0.plusSeconds(100));
                });
    }

    // -- fixtures --------------------------------------------------------------------------------

    private Anomaly open(long id, long serviceId, long startSeconds) {
        Anomaly a = Anomaly.builder().id(id).serviceId(serviceId).signalType(SignalType.METRIC)
                .metricType(MetricType.LATENCY).detector(DetectorType.L1).status(AnomalyStatus.OPEN)
                .detectedAt(T0.plusSeconds(startSeconds)).lastFlaggedAt(T0.plusSeconds(startSeconds))
                .anomalyScore(0.8).unflaggedCycles(0).build();
        anomalyRows.put(id, a);
        return a;
    }

    private Anomaly closed(long id, long serviceId, long startSeconds, long endSeconds) {
        Anomaly a = open(id, serviceId, startSeconds);
        close(id, endSeconds);
        return a;
    }

    private void close(long id, long endSeconds) {
        Anomaly a = anomalyRows.get(id);
        a.setStatus(AnomalyStatus.CLOSED);
        a.setLastFlaggedAt(T0.plusSeconds(endSeconds));
        a.setEndedAt(T0.plusSeconds(endSeconds));
    }

    private MlCorrelateResponse response(MlCorrelateRequest request) {
        List<Long> sent = request.episodes().stream().map(MlCorrelateRequest.Episode::id).toList();
        List<List<Long>> groups = nextGroups.stream().map(g -> g.stream().filter(sent::contains).toList())
                .filter(g -> !g.isEmpty()).toList();
        List<MlCorrelateResponse.Group> out = new ArrayList<>();
        for (int n = 0; n < groups.size(); n++) {
            List<Long> ids = groups.get(n);
            List<Long> svc = ids.stream().map(id -> anomalyRows.get(id).getServiceId()).distinct().toList();
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (int r = 0; r < svc.size(); r++) {
                long sec = request.securityEvents().getOrDefault(svc.get(r), 0L);
                candidates.add(Map.of("serviceId", svc.get(r), "rank", r + 1, "score", 1.0 - 0.2 * r,
                        "securityEvents", sec, "evidence", List.of(sec + " security event(s) in the window")));
            }
            out.add(new MlCorrelateResponse.Group("incident-" + (n + 1), ids, svc.stream().sorted().toList(),
                    Map.of("confidence", svc.size() == 1 ? 1.0 : 0.2, "candidates", candidates)));
        }
        return new MlCorrelateResponse("heuristic graph-based correlation and root-cause ranking (not a learned model)",
                "full_call_graph", Map.of("gap_seconds", 240.0, "max_hops", 1), "(s1 - s2) / s1", T0.toString(), out);
    }

    private static long securityOf(Incident incident, long serviceId) {
        return ((List<?>) incident.getRootCause().get("candidates")).stream().map(c -> (Map<?, ?>) c)
                .filter(c -> ((Number) c.get("serviceId")).longValue() == serviceId)
                .mapToLong(c -> ((Number) c.get("securityEvents")).longValue()).findFirst().orElseThrow();
    }

    private static Service service(long id, String name, Criticality criticality) {
        return Service.builder().id(id).name(name).type(ServiceType.MICROSERVICE).criticality(criticality).build();
    }

    private static ServiceDependency edge(long service, long dependsOn) {
        return ServiceDependency.builder().serviceId(service).dependsOnServiceId(dependsOn).build();
    }

    private static RiskSnapshot snapshot(long serviceId, RiskLevel level) {
        return new RiskSnapshot(serviceId, "s" + serviceId, Criticality.HIGH, 50.0, level, true, false, null, T0, T0,
                List.of(), List.of(), Map.of());
    }
}
