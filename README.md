# IntelliGuard

AI-powered enterprise observability platform: correlates metrics, logs, traces, and
security events, applies ML-based anomaly detection, scores risk, and assists engineers
with root-cause heuristics and an AI copilot. Direct successor to SentinelCore, replacing
its fixed-threshold, single-signal monitoring with ML-driven, multi-signal, correlated
observability.

## Project context

- **Problem**: SentinelCore's monitoring was fixed-threshold and single-signal. IntelliGuard
  replaces it with ML-driven, multi-signal, correlated observability across metrics, logs,
  traces, and security events.
- **Scope**: delivered in phases, from foundation and telemetry through anomaly detection,
  correlation, an incident workspace with a copilot, and evaluation — see [Roadmap](#roadmap).
- **Architecture decisions**: the frontend talks only to `backend-core`; `ml-service` is an
  internal service for anomaly detection, risk scoring, correlation, and the copilot;
  PostgreSQL is the database; tracing uses OpenTelemetry + Jaeger — see [Stack](#stack).

## Stack

- **Frontend**: React (Vite)
- **backend-core**: Java 21, Spring Boot, Spring Security + JWT, Spring Data JPA — the
  only service the frontend talks to
- **ml-service**: Python, FastAPI — internal service for anomaly detection, risk
  scoring, correlation, and the copilot
- **Database**: PostgreSQL
- **Tracing**: OpenTelemetry + Jaeger

## Running locally

```bash
cd infra
docker compose up --build
```

- Frontend: http://localhost:5173
- backend-core: http://localhost:8080
- ml-service: http://localhost:8000
- Jaeger UI: http://localhost:16686

## Roadmap

- [x] **Phase 0** — Repo & architecture decisions
- [x] **Phase 1** — Foundation: DB schema, JWT auth, RBAC, Service registry CRUD
- [x] **Phase 2** — Telemetry pipeline (simulator + ingestion + tracing)
- [x] **Phase 3** — Dashboard v1
- [x] **Phase 4** — Anomaly detection + risk engine
- [x] **Phase 5** — Correlation + root cause
- [ ] **Phase 6** — Incident workspace + lite RAG copilot
- [ ] **Phase 7** — Testing, CI/CD, deployment, evaluation, report

### Phase 3 details

Dashboard v1 is a pure visualization/aggregation layer over what Phase 2 already collects -
no ML, no correlation, nothing from Phase 4-6 is implemented here.

- **Overview tab** — aggregate cards (total/healthy/degraded/unknown services, recent log
  and security-event counts) from a single `GET /api/dashboard/summary`; a service list with
  a derived health-status badge per service; a service detail panel with latest metric
  values and per-metric-type history charts (Recharts); a recent security-events panel.
- **Logs tab** — a dedicated, filterable view (`GET /api/logs?serviceId=&level=`) across all
  services, independent of which service is selected on the Overview tab.
- **Health status is a fixed-threshold rule, not ML**: a service is `DEGRADED` if its latest
  CPU/MEMORY/DISK is above 90%, ERROR_RATE above 5%, or LATENCY above 500ms; `UNKNOWN` if it
  has no metrics yet; otherwise `HEALTHY`. This is deliberately simple and deterministic —
  learned/ML-based anomaly detection is Phase 4's job, not Phase 3's.
- **Known limitations (deferred, not gaps)**: no trace view yet (Jaeger's own UI is the
  trace backend for now — see `http://localhost:16686`); no sidebar/full navigation
  restructure (a lightweight Overview/Logs tab switcher covers this for now).

### Phase 4 details

Phase 4 adds anomaly detection, a risk score and the dashboard views for both. All detection
results below were measured on **synthetic anomalies injected by the project's own simulator**
— they are not real-world accuracy figures.

- **Ground truth** — the simulator (seeded, `SCENARIO_MODE=demo|continuous|normal`) records
  every injected anomaly in `ground_truth_events` (ADMIN-only API). It is used only for
  evaluation and is never read by the detection or risk code. `python -m simulator.offline`
  generates reproducible labeled CSV datasets.
- **Detectors** — **L0** is the Phase 3 fixed-threshold rule (evaluated in backend-core, so it
  works without ml-service). **L1** is a per-service, per-metric rolling robust z-score
  (median/MAD of the previous 60 samples, EWMA-smoothed), served by ml-service `POST /score`;
  it is the deployed detector. **L2** is a per-service Isolation Forest; it was evaluated but
  not deployed.
- **Anomaly episodes** — backend-core scores every service every 10 s and stores one
  `anomalies` row per episode per (metric, detector): opened on the first flag, extended
  while flagged, closed after 3 unflagged cycles. Start times are accurate; L1 end times can
  run late after long episodes.
- **Risk score** — a transparent weighted formula, not a learned model:
  `risk = 100 × clamp(0.40·ML score + 0.20·L0 violated + 0.10·criticality + 0.15·error-log rate + 0.15·security events)`,
  each input normalized to 0–1, banded LOW < 30 ≤ MEDIUM < 55 ≤ HIGH < 75 ≤ CRITICAL. If
  ml-service is unavailable the ML term is dropped and the other weights are rescaled
  (`mlAvailable: false`). Every score carries its component breakdown. See
  [`docs/risk-engine.md`](docs/risk-engine.md).
- **Dashboard** — a Risk column and an "At risk" card on the Overview tab, a Risk section in
  the service detail (score, visible component breakdown, history chart, ML-unavailable
  notice) and an Anomalies tab (`GET /api/risk/current`, `/api/risk/history`, `/api/anomalies`;
  read-only, any authenticated role).
- **Evaluation (synthetic, TEST seeds only)** — at the deployed operating point (≤ 1 false
  alarm/hour on validation normal data), L1 detected 57.7% of 194 test episodes (a random
  flagger at the same rate: 4.5%) with 1.58 false alarms/hour on a normal-mode run; L0
  detected 36.1% with none; L2 27.3%. On SUBTLE anomalies L1 detected 24.1% and L0 0%.
  L0 has the best row-level F1 (31.9% vs 21.4% for L1). Full tables, seeds and
  hyperparameters: [`docs/evaluation/phase4-experiments.md`](docs/evaluation/phase4-experiments.md).
- **Known limitations** — synthetic data only, with anomaly shapes written by the same author
  as the detectors and unrealistically clean normal data; a small number of test episodes;
  risk weights and cut-offs are hand-chosen, not learned or validated; L1 adapts to slow
  drifts and also flags benign baseline shifts (e.g. a simulator restart with a new seed);
  the 5-minute look-back keeps risk raised for a few minutes after an incident ends.

### Phase 5 details

Phase 5 groups detected anomaly episodes into incidents and ranks the likely root-cause service.
The grouping and the ranking are a **heuristic, graph-based method, not a learned model**. All
results below come from **synthetic cascades injected by the project's own simulator**. The
live integration has also been checked on synthetic cascades only.

- **Cascade scenario:** `SCENARIO_MODE=cascade` injects a ROOT anomaly on one service and SYMPTOM
  anomalies on its callers (10–60 s later, along the call graph), plus DISTRACTOR anomalies on
  unrelated services at overlapping times. EASY cascades have a strong root; HARD ones have a
  subtle, drifting root with visible symptoms. Labels (`groupId`, `role`, `rootServiceId`) are in
  the ground-truth params. The demo, continuous and normal output is unchanged.
- **Dependency graph:** the new `service_dependencies` table holds every edge of the 7-edge call
  graph. The legacy single `dependsOnServiceId` could hold only 4 of them; it still works, and is
  copied in at startup. Unknown id → 404, self-loop → 400, duplicate → 409, cycle → 400. The
  simulator registers all 7 edges on start (`GET/POST/DELETE /api/service-dependencies`).
- **Correlation and root cause:** ml-service `POST /correlate` (stateless). Two episodes are linked
  when they are at most 240 s apart and at most 1 hop apart in the graph, and incidents are the
  connected components. Each service in an incident is scored as
  `0.35·earliest onset + 0.30·dependents + 0.15·peak + 0.10·security events + 0.10·criticality`
  (each normalized within the incident). Every candidate carries evidence strings, and the
  incident gets a confidence = (score #1 − score #2) / score #1.
- **Incidents:** backend-core correlates OPEN and recently closed episodes every 15 s and stores
  incidents with a stable identity (matched by anomaly ids; merges keep the older incident, and
  a split keeps it on the larger part). An incident is OPEN while any anomaly is open, and
  RESOLVED after a 5-minute quiet period; a new anomaly reopens it. Severity is the highest current
  risk level of its services. If ml-service is down the cycle is skipped and nothing changes.
  Read-only `GET /api/incidents` and `GET /api/incidents/{id}` (services with current risk,
  anomalies, ranked candidates with evidence, timeline) are open to any authenticated role. See
  [`docs/incidents.md`](docs/incidents.md).
- **Evaluation (synthetic, TEST seeds only):** with the full 7-edge graph the rank-1 service was
  the true root in 81.4% of the 183 test cascades whose root was detected; the earliest-onset-only
  baseline got 76.8% and random choice 37.3%. Over all 258 cascades the rate was 57.8% vs 54.5%, since
  an undetected root can't be ranked. The grouping's pairwise F1 was 75.5% vs 69.8% for a
  time-window-only baseline. With only the 4 legacy edges the ranking merely tied the earliest-onset
  baseline (49.6% vs 49.2%) and the grouping F1 fell to 57.4%. Full tables, seeds and parameters:
  [`docs/evaluation/phase5-correlation.md`](docs/evaluation/phase5-correlation.md).
- **Known limitations:** synthetic cascades authored by the same project as the method; a tiny
  topology (8 services) and few cascades; metric anomalies only. Distractors on a neighbouring
  service merge into the incident (98 of 233 test cascades). HARD roots were detected in only 8
  of 83 cases, so the rank-1 service is then a symptom. The weights and gap were tuned on
  validation seeds, and the 240 s gap is the largest value tried.
