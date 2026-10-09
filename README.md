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
- [ ] **Phase 4** — Anomaly detection + risk engine
- [ ] **Phase 5** — Correlation + root cause
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
