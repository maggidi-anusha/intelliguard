# IntelliGuard

AI-powered enterprise observability platform: correlates metrics, logs, traces, and
security events, applies ML-based anomaly detection, scores risk, and assists engineers
with root-cause heuristics and an AI copilot. Direct successor to SentinelCore, replacing
its fixed-threshold, single-signal monitoring with ML-driven, multi-signal, correlated
observability.

See [`IntelliGuard_Project_Context.md`](IntelliGuard_Project_Context.md) for the full
problem statement, scope tiers, and architecture decisions.

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
- [ ] **Phase 3** — Dashboard v1
- [ ] **Phase 4** — Anomaly detection + risk engine
- [ ] **Phase 5** — Correlation + root cause
- [ ] **Phase 6** — Incident workspace + lite RAG copilot
- [ ] **Phase 7** — Testing, CI/CD, deployment, evaluation, report
