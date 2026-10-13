# Incidents (Phase 5.3)

An **incident** is a group of detected anomaly episodes that are close in time **and** close in
the service dependency graph, with a ranked list of likely root-cause services. backend-core
builds incidents every 15 s from the `anomalies` table (the L0/L1 episodes of Phase 4) by calling
ml-service `POST /correlate`.

> **The grouping and the root-cause ranking are a heuristic, graph-based method — not a learned
> model.** Nothing is trained. Every incident stores this in its `method` field
> ("heuristic graph-based correlation and root-cause ranking (not a learned model)").
> It has been evaluated offline and checked live **on synthetic cascades produced by the project's
> own simulator only** (see [`evaluation/phase5-correlation.md`](evaluation/phase5-correlation.md)).

## Dependency graph

`service_dependencies` holds one row per edge (`serviceId` calls / depends on
`dependsOnServiceId`, unique pair). It replaces the single `services.depends_on_service_id`, which
can hold only 4 of the 7 call-graph edges (api-gateway → auth-service, api-gateway → order-service
and order-service → inventory-service don't fit).

- **Backward compatibility:** `dependsOnServiceId` still works on the services API. At every
  startup the legacy values are copied into `service_dependencies` (idempotent), and service
  create/update also adds its legacy edge there (add only, never fails the service write).
- **Validation:** unknown service id → 404, self-loop → 400, duplicate → 409, an edge that would
  close a cycle → 400. The graph stays acyclic.
- **Simulator:** registers all 7 `CALL_GRAPH` edges after registering the services, on every
  start (409 = already there).

| Endpoint | Roles |
|---|---|
| `GET /api/service-dependencies` | VIEWER, USER, ADMIN |
| `POST /api/service-dependencies` `{serviceId, dependsOnServiceId}` | USER, ADMIN (VIEWER 403) |
| `DELETE /api/service-dependencies/{id}` | USER, ADMIN (VIEWER 403) |

No token → 401 on all of them.

## How incidents form

Every cycle (`IncidentCorrelationJob`, 15 s, fixed delay):

1. **Input.** Anomaly episodes that are OPEN, episodes not yet in an incident that closed within
   the **look-back** (10 min), and *every* episode of an incident that is OPEN or was resolved
   within the look-back. An incident's members enter and leave the input together, so a long
   incident keeps its early members, and a resolved incident can't fall apart as its members age
   out one by one (that produced spurious splits in the first live run; see Limits). Both
   deployed detectors are included. ml-service compares peaks within an incident, so both are put
   on the L1 scale (0–1, 0.5 = threshold): an L1 episode sends its peak score, an L0 episode sends
   0.5 × (value / limit), capped at 1.
2. **Request.** The episodes, every `service_dependencies` edge, each service's criticality and
   the parameter profile `full_call_graph`. Each changed group is then re-ranked in its own call
   with the security events counted per incident window (start − 60 s to last activity).
3. **Grouping (ml-service).** Two episodes are linked when their active windows overlap or are at
   most **240 s** apart **and** their services are the same or at most **1 hop** apart in the graph
   (edges taken as undirected). An open episode counts as active up to the newest timestamp in the
   request. Incidents are the connected components, so simultaneous anomalies on unconnected
   services stay separate.
4. **Persistence with a stable identity.** Each returned group is matched to existing incidents
   through its anomaly ids (`anomalies.incident_id`):
   - no linked anomaly → a **new** incident;
   - existing incidents are processed oldest first; each keeps the group holding most of its
     anomalies, so on a **split** the larger part keeps the old id and the rest becomes a new
     incident (`SPLIT_TO` / `SPLIT_FROM` events);
   - an incident whose anomalies all landed in groups already kept by older incidents is
     **MERGED** into the one holding most of them (`status = MERGED`, `mergedIntoId`, and
     `MERGED_INTO` / `MERGED_FROM` events). Its anomalies are relinked to the older incident.

`anomalies.incident_id` is written only by this job, through a targeted update. JPA treats the
column as read-only, so the scoring job saving the same anomaly row can never overwrite it.

## Lifecycle

| Status | When |
|---|---|
| OPEN | any linked anomaly is OPEN, or all are closed but the **quiet period** (5 min after the last flagged sample) is still running |
| RESOLVED | every linked anomaly has been closed for the quiet period; `resolvedAt` is set |
| MERGED | absorbed into an older incident (see above) |

- A new anomaly joining during the quiet period keeps the incident OPEN (the quiet period restarts
  from its last flagged sample).
- A RESOLVED incident is **frozen**: its ranking is no longer updated, and regrouping alone never
  splits it. A new anomaly that still correlates with its episodes **reopens** it (`REOPENED`
  event, `resolvedAt` cleared). With the defaults (240 s gap, 5 min quiet period) that is rare,
  because the new anomaly must start within 240 s of one of its episodes' end, which is normally
  before the incident resolves. In practice the quiet-period rule above handles a late joiner.
- `INVESTIGATING` exists in the enum for the Phase 6 workspace and is never set automatically.
- **Retention:** RESOLVED incidents resolved more than 7 days ago, and MERGED incidents inactive
  for 7 days, are deleted (their anomalies' links are cleared first).

**Severity.** While an incident is OPEN, `severity` is the highest *current* risk level
(`/api/risk/current`) among its services; `peakSeverity` is the highest value it reached. Once
RESOLVED both stay at their last values.

## Root-cause ranking

Every service in an incident is a candidate, scored as a weighted sum of five inputs, each
normalized to 0–1 within the incident (`ml-service/app/correlation.py`):

| Input | Normalized value | Weight (`full_call_graph`) |
|---|---|---|
| onset | 1 for the earliest onset, 0 for the latest, linear in between | 0.35 |
| dependents | anomalous services in the incident that depend on it (directly or transitively) / (candidates − 1) | 0.30 |
| peak | its highest peak score / the highest in the incident | 0.15 |
| security | 1 if it had any security events in the window | 0.10 |
| criticality | LOW 0.25, MEDIUM 0.5, HIGH 0.75, CRITICAL 1.0 | 0.10 |

Ties are broken by earlier onset, then lower service id. Each candidate carries **evidence
strings** (e.g. "earliest onset in the incident", "3 other anomalous service(s) depend on it:
service 6, service 7, service 8"). **Confidence** = (score of rank 1 − score of rank 2) / score of
rank 1: 0 means a tie, and a single-service incident has 1.0. It describes the margin between
the top two candidates. It is not a probability of being right.

The gap, hop limit and weights were tuned on VALIDATION seeds only. On the TEST seeds (synthetic
cascades), with the full 7-edge graph, the rank-1 service was the true root in **81.4%** of the
183 cascades whose root was detected (earliest-onset-only baseline: 76.8%). Over all 258 cascades
the figures are **57.8%** vs 54.5%, because a root that isn't detected cannot be ranked. The grouping's
pairwise F1 was 75.5% (time-window-only baseline 69.8%). With only the 4 legacy edges the
ranking merely tied the earliest-onset baseline (49.6% vs 49.2% over all cascades), and its
grouping F1 fell to 57.4%. That is why the backend now stores the full graph.

## Failure handling

- **ml-service down, slow (2 s timeout) or returning an error:** the cycle is skipped with a
  warning and nothing is written. Incidents keep their last state (no grouping, no status
  changes) until ml-service answers again; the next successful cycle catches up. The client
  setup is the scoring client's: HTTP/1.1 and the `intelliguard.scoring.ml-timeout` for both
  connect and read.
- The job runs on its own scheduler thread (`spring.task.scheduling.pool.size=2`), so a slow
  ml-service call never delays risk scoring, and an exception is logged without stopping the job.

## API

All read-only, any authenticated role (401 without a token), no write endpoints.

- `GET /api/incidents?status=OPEN|RESOLVED|MERGED&limit=` — newest first (by `startedAt`), capped at 100.
- `GET /api/incidents/{id}` — the incident, its services with their current risk level and score,
  its anomaly episodes, the ranked root-cause candidates (with service names, scores, components
  and evidence), the confidence, and a timeline of anomaly start/end events merged with the
  incident's own events (CREATED, MERGED_FROM/INTO, SPLIT_FROM/TO, RESOLVED, REOPENED).

## Configuration (`intelliguard.incidents.*`)

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`INTELLIGUARD_INCIDENTS_ENABLED`) | run the job |
| `interval-ms` | `15000` (`INTELLIGUARD_INCIDENTS_INTERVAL_MS`) | delay between cycles |
| `look-back` | `10m` | closed episodes that ended within this window are still correlated |
| `quiet-period` | `5m` | RESOLVED after all anomalies have been closed this long |
| `retention` | `7d` | delete RESOLVED/MERGED incidents older than this |
| `profile` | `full_call_graph` | ml-service parameter profile (gap, hops, weights) |
| `max-episodes` | `2000` | cap per request (newest kept, with a warning) |

## Schema changes

- New table `service_dependencies` (`id`, `service_id`, `depends_on_service_id`, `created_at`;
  unique `(service_id, depends_on_service_id)`).
- `incidents`: new nullable columns `started_at`, `last_activity_at`, `service_ids` (jsonb),
  `root_service_id`, `root_cause` (jsonb), `method`, `severity`, `peak_severity`,
  `merged_into_id`, `events` (jsonb). The existing `title`, `status`, `root_cause_suggestion`,
  `created_at` and `resolved_at` are reused. `IncidentSchemaPatch` re-creates the
  `incidents_status_check` constraint at startup so the new `MERGED` value is accepted on databases
  created before Phase 5.3 (Hibernate's `ddl-auto=update` never widens it).
- `anomalies`: new nullable `incident_id` (indexed).

## Live check (synthetic cascades only)

`docker compose up --build` with the simulator on seed 596. First came 12 minutes of `normal` mode,
so the L1 baselines matched the seed; then `cascade` mode, then `demo` mode (same seed). The live
check covers one simulator run per mode, so it says nothing about accuracy in general; the
numbers above come from the offline evaluation.

- **Cascade run (3 EASY cascades, 1 distractor):** each cascade became one incident containing all
  of its detected services (2, 5 and 5 services). The true root was ranked first every time. The
  incident appeared 13–18 s after the root's injection started. The distractor (user-db,
  overlapping cascade-0002) stayed its own incident. No detector false alarms. Every incident
  resolved 5 minutes plus at most one 15 s cycle after its last flagged sample.
- **Demo run:** order-service CPU and order-db LATENCY start at the same tick, so onset can't
  separate them. order-db is ranked first (score 1.00 vs 0.56) on structure alone: order-service
  depends on it, its peak is higher, it had the port-scan events, and it is CRITICAL. The demo has
  no causal root, so this shows what the heuristic prefers, not that it found a cause. The
  separately injected api-gateway network spike 6 minutes later joined the same incident: it is
  1 hop from order-service, whose L1 episode was still open 3 minutes after its spike ended.
- **ml-service stopped for 60 s mid-cascade:** backend-core stayed up and risk scoring fell back to
  L0. Each correlation cycle was skipped with a warning, and no incident changed. In the first
  cycle after the restart, the open incident took in the 4 services that had become anomalous
  meanwhile.

## Limits

- **Heuristic, not learned, and validated only on synthetic data:** the cascades were authored
  by the same project as the method. The topology is tiny (8 services, 7 edges) and the cascades
  few, and only metric anomalies are correlated. Security events only enter the ranking. Logs
  and traces are not used.
- **Distractors merge in:** with a 240 s gap and 1 hop, an unrelated anomaly on a neighbouring
  service at about the same time joins the incident (98 of 233 TEST cascades with ≥ 2 detected
  episodes picked up a distractor). Its presence can change the ranking.
- **Undetected roots can't be ranked:** HARD cascades (subtle drifting root, visible symptoms)
  had their root detected in only 8 of 83 TEST cases. The rank-1 service is then a symptom.
- **Differences from the offline evaluation:** the evaluation used L1 episodes only; live, L0
  episodes are included with the 0.5 × ratio peak scaling (untested offline). Security events
  are counted per incident window (start − 60 s to last activity), as in the evaluation.
- **Episode timing:** L1 end times can run late after long episodes, which delays resolution.
  Every isolated anomaly, including detector false alarms, becomes a single-service incident.
  A simulator restart with a new seed shifts baselines and briefly produces such alarms.
- **Found in the live check and fixed:** at first, closed episodes left the input individually
  after 10 minutes. When the middle service of a resolved chain (inventory-service – order-service –
  api-gateway) aged out first, the two ends (2 hops apart) came back as separate groups. The job
  recorded a split into a new, immediately RESOLVED incident. Members now enter and leave the
  input together, and a RESOLVED incident is never split without a new anomaly.
- **Security events are counted per incident window** (start − 60 s to last activity), so two
  consecutive incidents on the same service no longer share them.
- **Single backend instance assumed:** edge writes are serialized in-process (`synchronized`).
  The database's unique constraint still prevents duplicates, but not two concurrent edges that
  only together form a cycle.
