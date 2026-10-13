# IntelliGuard

[![CI](https://github.com/maggidi-anusha/intelliguard/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/maggidi-anusha/intelliguard/actions/workflows/ci.yml)

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

Copy `infra/.env.example` to `infra/.env` first; it lists every variable with a comment. Public
registration creates read-only VIEWER accounts; USER and ADMIN accounts are created by an ADMIN
(`POST /api/admin/users`). The simulator's ADMIN account is seeded from
`INTELLIGUARD_SIMULATOR_ADMIN_PASSWORD`.

## Deployment

`infra/docker-compose.prod.yml` is a production-style stack. It serves the frontend as an nginx
static build that also proxies `/api`, and publishes only the frontend and the Jaeger UI. It has
healthchecks, restart policies and a named Postgres volume, and backend-core runs with the `prod`
profile, with secrets from `infra/.env` and no insecure defaults. See
[`docs/deployment.md`](docs/deployment.md) for setup, accounts, the simulator, backups,
upgrades and troubleshooting. `scripts/smoke_test.py` checks a running stack end to end.

Documentation: [architecture](docs/architecture.md) (components, data flow, tables, endpoints and
roles, background jobs) and the [evaluation summary](docs/evaluation/summary.md) (Phases 4–6,
including what is and isn't claimed).

## Testing and CI

GitHub Actions (`.github/workflows/ci.yml`) runs on every push and pull request to `master`. It
runs five jobs: backend tests against a Postgres service container, ml-service tests, simulator
tests, the frontend build, and `docker compose build`. To run the same suites locally:

```bash
# backend-core: needs a Postgres, e.g. the compose one on port 5433
cd backend-core && DB_URL=jdbc:postgresql://localhost:5433/intelliguard DB_USERNAME=<user> DB_PASSWORD=<password> ./mvnw test

# ml-service (Python 3.11): uses ../simulator and ../docs/runbooks
cd ml-service && pip install -r requirements-dev.txt && python -m pytest

# simulator (Python 3.11)
cd simulator && pip install -r requirements-dev.txt && python -m pytest tests

# frontend
cd frontend && npm ci && npm run build

# all images
cd infra && docker compose --env-file .env.example build
```

The offline evaluations are reproducible scripts as well: `ml-service/evaluation/run_experiments.py`
(detectors), `run_correlation_experiments.py` (correlation) and `copilot_eval.py` (copilot).

## Roadmap

- [x] **Phase 0** — Repo & architecture decisions
- [x] **Phase 1** — Foundation: DB schema, JWT auth, RBAC, Service registry CRUD
- [x] **Phase 2** — Telemetry pipeline (simulator + ingestion + tracing)
- [x] **Phase 3** — Dashboard v1
- [x] **Phase 4** — Anomaly detection + risk engine
- [x] **Phase 5** — Correlation + root cause
- [x] **Phase 6** — Incident workspace + lite RAG copilot
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

### Phase 6 details

Phase 6 adds the incident workspace (6A) and an incident copilot (6B).

- **What it shows:** an **Incidents** tab lists incidents newest first (status, severity and
  peak severity, title, number of services, likely root cause with its confidence, start and
  last activity; filter by status). Clicking one opens the workspace: the ranked root-cause
  candidates with score bars and their evidence written out, the affected services with their
  current risk, a timeline (anomaly start/end, lifecycle, notes, acknowledgement), the anomaly
  episodes and the notes. The Overview has an "Open incidents" card (OPEN + INVESTIGATING).
- **Who can write:** USER and ADMIN can add notes (1–2000 characters, author taken from the login)
  and acknowledge an OPEN incident, which sets it to INVESTIGATING and records who and when.
  Acknowledging is idempotent. Both actions appear in the timeline. VIEWER is read-only: the API
  answers 403 and the controls are hidden. A MERGED incident rejects both (409). A RESOLVED
  incident still takes notes but can't be acknowledged.
- **Lifecycle:** INVESTIGATING behaves like OPEN. It still auto-resolves 5 minutes after the last
  anomaly activity, and a new anomaly joining a resolved incident reopens it as OPEN. The
  correlation job never writes notes or acknowledgement data. Optimistic locking makes a
  concurrent job update and user action retry instead of overwriting each other.
- **Root-cause evidence:** security events now count only inside each incident's own window, so
  two consecutive incidents on the same service no longer share them.
- **Copilot ("Ask about this incident"):** a **retrieval-and-template assistant**, not a free-form
  chat model and not a source of new facts. The backend sends ml-service `POST /copilot/ask` the
  question and the incident's own data (ranking with evidence and confidence, services with risk,
  anomalies, timeline, notes). The intent is classified by rules, and the answer sentences are
  templated from that data or quoted verbatim from 8 generic operations runbooks
  (`docs/runbooks`). The runbooks are retrieved with TF-IDF plus a boost for runbooks that match the
  incident's metric or security-event types. Every answer cites its sources, and root-cause answers
  state the confidence and that the ranking is a heuristic. Off-topic questions, instruction
  overrides and requests for facts not in the data are refused. Notes are quoted, never followed.
  Any role may ask, VIEWER included; nothing is stored. If ml-service is down the panel shows a
  503 message and the rest of the workspace keeps working. See [`docs/copilot.md`](docs/copilot.md).
- **Copilot evaluation (small, handwritten):** on 6 incidents and 29 questions written by the
  project, 21/21 in-scope questions got the right intent, the expected runbook was in the top 3
  for 14/15, 21/21 answers passed an automated groundedness check (every number, service name and
  citation comes from the incident data or a cited runbook) and 8/8 off-topic or injection
  questions were refused. The set is easy by construction and there was no user study; see
  [`docs/evaluation/phase6-copilot.md`](docs/evaluation/phase6-copilot.md).
- **Known limits:** there is no un-acknowledge, assignment, or editing/deleting of notes. Notes
  and acknowledgement stay on an incident that gets merged into an older one. The list shows the
  newest 100 incidents and the workspace the newest 200 notes. The root cause is still a
  heuristic ranking, checked on synthetic cascades only. The copilot only covers the question types
  its templates know: paraphrases that share no words with its rules or runbooks are refused or
  misclassified, and the runbooks are general guidance, not facts about this system.
