# Risk engine (Phase 4.4)

The risk score is a **transparent, hand-weighted formula — not machine learning**. It combines
the ML anomaly score with simple operational signals so an engineer can see exactly why a
service is rated the way it is. Code: `backend-core/.../risk/RiskCalculator.java` (a pure
function); settings: `intelliguard.risk.*` in `application.properties`.

## Formula

```
risk = 100 × clamp( Σ weightᵢ × normalizedᵢ , 0, 1 )
```

| Input | Raw value | Normalized to [0, 1] | Weight (default) |
|---|---|---|---|
| `ml` | ml-service L1 `serviceScore` for the service (0.5 = L1 threshold) | the score itself, clamped | 0.40 |
| `l0` | any Phase 3 threshold violated now (CPU/MEMORY/DISK > 90, ERROR_RATE > 5, LATENCY > 500) | 1 or 0 | 0.20 |
| `criticality` | the service's criticality | LOW 0.25, MEDIUM 0.5, HIGH 0.75, CRITICAL 1.0 | 0.10 |
| `errors` | ERROR logs per minute over the last 5 min | rate ÷ 5, capped at 1 | 0.15 |
| `security` | security events for the service in the last 5 min | count ÷ 10, capped at 1 | 0.15 |

Weights must be non-negative and sum to 1; the backend refuses to start otherwise.

**Levels** (0–100, lower bound inclusive, configurable): LOW < 30 ≤ MEDIUM < 55 ≤ HIGH < 75 ≤ CRITICAL.

**Worked example** (from the unit tests): ml 0.5, L0 violated, HIGH criticality, 2.5 errors/min,
3 security events → 0.4·0.5 + 0.2·1 + 0.1·0.75 + 0.15·0.5 + 0.15·0.3 = 0.595 → **59.5 (HIGH)**.

**ML unavailable** (ml-service down or slow, or not enough history): the `ml` term is dropped
and the other weights are divided by (1 − 0.40) so they still sum to 1 — an **L0-only fallback**
on the same 0–100 scale. The result is marked `"mlAvailable": false` with an `mlStatus`.

Every risk result carries its full `breakdown`: for each component the raw `input`, the
`normalized` value, the `weight` actually used and its `contribution` in points, plus
`mlAvailable`, `mlStatus`, `l1FlaggedMetrics`, `l0ViolatedMetrics` and `dataUntil`.
The contributions add up to the score, so the UI can explain it without recomputing anything.

## How it runs

`RiskScoringJob` runs every 10 s (`intelliguard.scoring.interval-ms`) for every registered service:

1. Fetch the newest 72 ticks of metrics. Skip the service if its newest sample is older than
   60 s (no new data) or there are fewer than 61 samples per metric ("insufficient data").
2. **L0** — evaluate the Phase 3 thresholds in backend-core (works without ml-service).
3. **L1** — call ml-service `POST /score` (2 s timeout). On failure, log a warning and skip
   ml-service for the rest of the cycle.
4. **Anomaly episodes** — per (service, metric, detector): open an `anomalies` row when the
   detector first flags, extend it while it keeps flagging (last flagged time, peak score),
   close it after 3 consecutive unflagged cycles (`close-after-cycles`), with the end time =
   the last flagged sample. One row per episode, not per tick. L1 episodes are left untouched
   while ml-service is unavailable.
5. **Risk** — compute the formula, publish it to `/api/risk/current`, and persist a
   `risk_scores` row only when the level changes, the score moves by ≥ 5 points, or 60 s
   have passed (≈ 1 row/service/minute in steady state).

**Retention:** service risk rows older than 24 h and CLOSED anomalies older than 7 days are
deleted (checked every 60 cycles). `ground_truth_events` is never read by any of this.

## API (read-only, any authenticated role; 401 without a token)

- `GET /api/risk/current` — latest risk per service with breakdown (in memory; after a restart,
  the last persisted row until the first cycle runs).
- `GET /api/risk/history?serviceId=&limit=` — persisted rows, newest first, max 500.
- `GET /api/anomalies?serviceId=&status=OPEN|CLOSED&limit=` — anomaly episodes, max 200.

## Limitations

- **The weights and cut-offs are hand-chosen, not learned or validated.** They encode a
  judgement (the ML signal matters most; criticality nudges, it doesn't dominate) and were
  sanity-checked only against the simulator's demo scenarios. Different weights would rank
  services differently, and there is no ground truth for "risk" to fit them against.
- **The inputs are not independent.** An error burst raises the ML score, trips L0
  (ERROR_RATE > 5) *and* adds ERROR logs, so one underlying problem is counted up to three
  times. That's intentional (corroborated signals raise risk) but it inflates scores for
  error-type incidents relative to, say, a disk anomaly.
- **The ML input inherits L1's behaviour**: it reacts to sudden changes against the last
  ~5 minutes, adapts to slow drifts, and flags genuine-but-benign shifts (e.g. a restart that
  changes a baseline) that no label marks as anomalous.
- **Criticality adds a constant floor** (up to 10 points), so a CRITICAL service never scores
  as low as an equally healthy LOW one.
- **Snapshots, not trends.** Each cycle looks at the current state plus a 5-minute window; the
  formula has no memory of earlier cycles beyond what the anomaly episodes record.
- **Synthetic validation only.** All behaviour was checked against simulator data with
  injected anomalies, never real production traffic.
