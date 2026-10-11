"""Phase 5.2: evaluate incident correlation and root-cause ranking on simulated cascades.

    cd ml-service && python evaluation/run_correlation_experiments.py
    python evaluation/run_correlation_experiments.py --val-seeds 501 502 --test-seeds 601 602 603 \
        --train-seeds 101 102 103 --days 1

Pipeline (synthetic data only):
 1. Generate cascade-mode datasets with `python -m simulator.offline --mode cascade`.
 2. Run the deployed L1 detector (settings from app/model_config.json, floors fitted on TRAIN
    normal-mode data).
 3. Form anomaly episodes per (service, metric) the way backend-core does: open on the first
    flagged tick, extend while flagged, close after CLOSE_AFTER_TICKS consecutive unflagged ticks
    (6 ticks = 30 s, matching the backend's 3 unflagged 10-second cycles). start = first flagged
    tick, end = last flagged tick, peakScore = max L1 score (0-1, 0.5 = threshold). Unlike the
    backend, every 5 s tick is evaluated (the backend looks every 10 s).
 4. Match each detected episode to a ground-truth label (same service and metric, overlapping in
    time with the label window extended by MATCH_TOLERANCE_S); unmatched episodes are detector
    false alarms ("noise"), each its own group.
 5. Correlate with app.correlation - the same code as POST /correlate - once with the full 7-edge
    call graph and once with the 4 edges the backend can store. gap/max_hops (grouping F1), then
    the weights (root-cause top-1), are tuned on VALIDATION separately for each graph. TEST is
    evaluated once, with the chosen settings.
"""
from __future__ import annotations

import argparse
import itertools
import json
import os
import sys
import time
from collections import Counter, defaultdict
from datetime import timedelta

import numpy as np
import pandas as pd

ML_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_ROOT = os.path.dirname(ML_ROOT)
sys.path.insert(0, ML_ROOT)

from app.correlation import CorrelateRequest, correlate  # noqa: E402
from app.data import generate, load_run, normal_training_data, simulator_path  # noqa: E402
from app.detectors import METRICS, RobustZDetector  # noqa: E402

sys.path.append(simulator_path())
from topology import CALL_GRAPH, SEED_SERVICES  # noqa: E402

CLOSE_AFTER_TICKS = 6
MATCH_TOLERANCE_S = 60
GAP_GRID = [30, 60, 120, 240]
HOPS_GRID = [1, 2, 3]
WEIGHT_GRID = [  # onset, dependents, peak, security, criticality (each sums to 1)
    (0.50, 0.20, 0.10, 0.10, 0.10),
    (0.35, 0.30, 0.15, 0.10, 0.10),
    (0.20, 0.50, 0.10, 0.10, 0.10),
    (0.25, 0.25, 0.30, 0.10, 0.10),
    (0.40, 0.40, 0.10, 0.05, 0.05),
    (0.30, 0.30, 0.30, 0.05, 0.05),
    (0.45, 0.35, 0.00, 0.10, 0.10),
    (0.25, 0.45, 0.00, 0.15, 0.15),
]
WEIGHT_KEYS = ("onset", "dependents", "peak", "security", "criticality")

SERVICE_ID = {s["name"]: i for i, s in enumerate(SEED_SERVICES, 1)}
NAME = {v: k for k, v in SERVICE_ID.items()}
CRITICALITY = {SERVICE_ID[s["name"]]: s["criticality"] for s in SEED_SERVICES}
GRAPHS = {
    "full_call_graph": [{"service": SERVICE_ID[a], "dependsOn": SERVICE_ID[b]} for a, bs in CALL_GRAPH.items() for b in bs],
    "backend_4_edges": [{"service": SERVICE_ID[s["name"]], "dependsOn": SERVICE_ID[s["depends_on"]]}
                        for s in SEED_SERVICES if s["depends_on"]],
}
# Time-only baseline: every pair of services is 1 hop apart, so only the time gap matters.
COMPLETE_GRAPH = [{"service": a, "dependsOn": b} for a in NAME for b in NAME if a != b]


# -- data, detection, episodes --------------------------------------------------------------------

def load_cascade_run(data_dir, prefix, seed, days):
    path = generate(os.path.join(data_dir, f"{prefix}-cascade-s{seed}"), seed, "cascade", days=days)
    run = load_run(path, seed, "cascade")
    labels = pd.read_csv(os.path.join(path, "labels.csv"))
    for col in ("start_time", "end_time"):
        labels[col] = pd.to_datetime(labels[col], utc=True)
    labels["p"] = [json.loads(p) for p in labels["params"]]
    security = pd.read_csv(os.path.join(path, "security_events.csv"))
    security["timestamp"] = pd.to_datetime(security["timestamp"], utc=True)
    return run, labels, security


def detect_episodes(detector, run):
    episodes = []
    thr = detector.threshold
    for sid, frame in sorted(run.frames.items()):
        idx = frame.index
        for metric in METRICS:
            s, valid = detector.score_series(frame[metric].to_numpy(float), sid, metric)
            flags = valid & (np.nan_to_num(s) > thr)
            start = last = None
            misses = 0
            peak = 0.0
            for i in range(len(idx)):
                if flags[i]:
                    if start is None:
                        start, peak = i, 0.0
                    last, misses = i, 0
                    peak = max(peak, s[i] / (s[i] + thr))
                elif start is not None:
                    misses += 1
                    if misses >= CLOSE_AFTER_TICKS:
                        episodes.append((sid, metric, idx[start], idx[last], peak))
                        start = None
            if start is not None:
                episodes.append((sid, metric, idx[start], idx[last], peak))
    df = pd.DataFrame(episodes, columns=["service_id", "metric", "start", "end", "peak"])
    df["id"] = [f"s{run.seed}-e{i}" for i in range(len(df))]
    return df


def match_truth(episodes, labels):
    """Attach ground truth (group, role, root) to each detected episode."""
    metric_labels = labels[labels["signal_type"] == "METRIC"]
    by_key = defaultdict(list)
    for r in metric_labels.itertuples():
        by_key[(r.service_id, r.metric_type)].append(r)
    tol = timedelta(seconds=MATCH_TOLERANCE_S)
    group, role, root = [], [], []
    for e in episodes.itertuples():
        best, best_overlap = None, None
        for r in by_key.get((e.service_id, e.metric), []):
            overlap = (min(e.end, r.end_time + tol) - max(e.start, r.start_time)).total_seconds()
            if overlap >= 0 and (best is None or overlap > best_overlap):
                best, best_overlap = r, overlap
        if best is None:
            group.append(f"noise:{e.id}")
            role.append("NOISE")
            root.append(None)
        else:
            group.append(best.p["groupId"])
            role.append(best.p["role"])
            root.append(best.p["rootServiceId"])
    episodes = episodes.copy()
    episodes["group"], episodes["role"], episodes["root"] = group, role, root
    return episodes


def prepare(data_dir, prefix, seeds, days, detector):
    runs = []
    for seed in seeds:
        run, labels, security = load_cascade_run(data_dir, prefix, seed, days)
        eps = match_truth(detect_episodes(detector, run), labels)
        cascades = {}
        for r in labels[labels["signal_type"] == "METRIC"].itertuples():
            p = r.p
            if p["role"] in ("ROOT", "SYMPTOM"):
                c = cascades.setdefault(p["groupId"], {"root": p["rootServiceId"], "type": p["cascadeType"], "services": set()})
                c["services"].add(r.service_id)
        runs.append({"seed": seed, "episodes": eps, "cascades": cascades, "security": security})
    return runs


# -- correlation ------------------------------------------------------------------------------

def episodes_payload(eps):
    return [{"id": e.id, "serviceId": int(e.service_id), "metricType": e.metric, "detector": "L1",
             "start": e.start.isoformat(), "end": e.end.isoformat(), "peakScore": float(e.peak)} for e in eps.itertuples()]


def run_correlation(run, edges, gap, hops, weights):
    """Incidents for one run: episode id -> incident index, plus per-incident ranking."""
    params = {"gap_seconds": gap, "max_hops": hops, "weights": dict(zip(WEIGHT_KEYS, weights))}
    eps = run["episodes"]
    if eps.empty:
        return {}, []
    grouped = correlate(CorrelateRequest(episodes=episodes_payload(eps), dependencies=edges, params=params))
    assignment, incidents = {}, []
    sec = run["security"]
    for n, inc in enumerate(grouped["incidents"]):
        ids = set(inc["episodeIds"])
        sub = eps[eps["id"].isin(ids)]
        start, end = sub["start"].min() - timedelta(seconds=60), sub["end"].max()
        window = sec[(sec["timestamp"] >= start) & (sec["timestamp"] <= end)]
        counts = {int(k): int(v) for k, v in window["service_id"].value_counts().items()}
        # Rank once more with the security events inside this incident's window.
        ranked = correlate(CorrelateRequest(episodes=episodes_payload(sub), dependencies=edges, params=params,
                                             criticality=CRITICALITY, securityEvents=counts))
        [only] = ranked["incidents"]
        for i in ids:
            assignment[i] = n
        incidents.append({"ids": ids, "candidates": only["rootCause"]["candidates"], "episodes": sub})
    return assignment, incidents


def pairwise(runs, assignments):
    tp = fp = fn = 0
    for run, assign in zip(runs, assignments):
        eps = run["episodes"]
        ids, groups = list(eps["id"]), list(eps["group"])
        for i, j in itertools.combinations(range(len(ids)), 2):
            same_pred = assign[ids[i]] == assign[ids[j]]
            same_true = groups[i] == groups[j] and not groups[i].startswith("noise:")
            tp += same_pred and same_true
            fp += same_pred and not same_true
            fn += same_true and not same_pred
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    return {"precision": p, "recall": r, "f1": 2 * p * r / (p + r) if p + r else 0.0}


def topm(scores, root, m, higher_is_better=True):
    """P(root within the top m) when ties are broken uniformly at random. scores: service -> value."""
    if root not in scores:
        return 0.0
    vals = np.array(list(scores.values()), dtype=float)
    rv = scores[root]
    better = int((vals > rv).sum() if higher_is_better else (vals < rv).sum())
    tied = int((vals == rv).sum())
    return float(min(1.0, max(0.0, (m - better) / tied)))


def evaluate(runs, edges, gap, hops, weights):
    """Grouping and root-cause metrics for one configuration."""
    assignments, outcome = [], Counter()
    rc = defaultdict(lambda: defaultdict(list))  # method -> (scope, type) -> [hit values]
    for run in runs:
        assign, incidents = run_correlation(run, edges, gap, hops, weights)
        assignments.append(assign)
        eps = run["episodes"]
        for c_id, c in run["cascades"].items():
            g_eps = eps[eps["group"] == c_id]
            ctype = c["type"]
            if g_eps.empty:
                outcome["not detected"] += 1
                for method in ("ranking", "earliest_onset", "highest_peak", "most_dependents", "random"):
                    for m in (1, 3):
                        rc[method][("all", ctype, m)].append(0.0)
                continue
            inc_ids = sorted({assign[i] for i in g_eps["id"]})
            if len(g_eps) >= 2:
                if len(inc_ids) > 1:
                    outcome["split"] += 1
                else:
                    others = incidents[inc_ids[0]]["episodes"]
                    roles = set(others[others["group"] != c_id]["role"])
                    outcome["merged + distractor" if "DISTRACTOR" in roles else
                            "merged + other" if roles else "fully merged (clean)"] += 1
            else:
                outcome["only 1 episode detected"] += 1

            # Primary incident: the one holding most of this cascade's detected episodes.
            primary = Counter(assign[i] for i in g_eps["id"]).most_common()
            primary = min((n for n, k in primary if k == primary[0][1]), key=lambda n: incidents[n]["episodes"]["start"].min())
            inc = incidents[primary]
            ranked = [c_["serviceId"] for c_ in inc["candidates"]]
            ie = inc["episodes"]
            onset = {s: ie[ie["service_id"] == s]["start"].min().timestamp() for s in ranked}
            peak = {s: ie[ie["service_id"] == s]["peak"].max() for s in ranked}
            dep = {s: next(x["components"]["dependents"] for x in inc["candidates"] if x["serviceId"] == s) for s in ranked}
            root = c["root"]
            root_detected = bool((g_eps["service_id"] == root).any())
            hits = {
                "ranking": {m: float(root in ranked[:m]) for m in (1, 3)},
                "earliest_onset": {m: topm(onset, root, m, higher_is_better=False) for m in (1, 3)},
                "highest_peak": {m: topm(peak, root, m) for m in (1, 3)},
                "most_dependents": {m: topm(dep, root, m) for m in (1, 3)},
                "random": {m: (min(m, len(ranked)) / len(ranked) if root in ranked else 0.0) for m in (1, 3)},
            }
            for method, h in hits.items():
                for m in (1, 3):
                    rc[method][("all", ctype, m)].append(h[m])
                    if root_detected:
                        rc[method][("root detected", ctype, m)].append(h[m])
    summary = {}
    for method, cells in rc.items():
        out = {}
        for scope in ("root detected", "all"):
            for m in (1, 3):
                vals = [v for (sc, _, mm), vs in cells.items() if sc == scope and mm == m for v in vs]
                out[f"{scope} top{m}"] = (float(np.mean(vals)) if vals else None, len(vals))
                for ctype in ("EASY", "HARD"):
                    v = cells.get((scope, ctype, m), [])
                    out[f"{scope} top{m} {ctype}"] = (float(np.mean(v)) if v else None, len(v))
        summary[method] = out
    return {"pairwise": pairwise(runs, assignments), "outcomes": dict(outcome), "root_cause": summary}


def tune(runs, edges, label):
    best = None
    for gap, hops in itertools.product(GAP_GRID, HOPS_GRID if edges is not COMPLETE_GRAPH else [1]):
        f1 = evaluate(runs, edges, gap, hops, WEIGHT_GRID[1])["pairwise"]["f1"]
        print(f"  [{label}] gap={gap}s hops={hops}: VAL pairwise F1={f1:.3f}", flush=True)
        if best is None or f1 > best[0] + 1e-12:
            best = (f1, gap, hops)
    _, gap, hops = best
    best_w = None
    for w in WEIGHT_GRID:
        rcm = evaluate(runs, edges, gap, hops, w)["root_cause"]["ranking"]
        key = (rcm["all top1"][0], rcm["all top3"][0])
        print(f"  [{label}] weights={w}: VAL top-1 (all cascades)={key[0]:.3f} top-3={key[1]:.3f}", flush=True)
        if best_w is None or key > best_w[0]:
            best_w = (key, w)
    return {"gap_seconds": gap, "max_hops": hops, "weights": dict(zip(WEIGHT_KEYS, best_w[1])),
            "val_f1": best[0], "val_top1": best_w[0][0], "val_top3": best_w[0][1]}


# -- report ---------------------------------------------------------------------------------------

def pct(x):
    return "n/a" if x is None else f"{100 * x:.1f}%"


def cell(v):
    value, n = v
    return f"{pct(value)} (n={n})"


def write_report(path, ctx):
    a, t, chosen, sets = ctx["args"], ctx["test"], ctx["chosen"], ctx["sets"]
    graphs = ["full_call_graph", "backend_4_edges"]
    gl = {"full_call_graph": "Full call graph (7 edges)", "backend_4_edges": "Backend graph (4 edges)"}

    set_rows = "\n".join(
        f"| {s['set']} | {', '.join(map(str, s['seeds']))} | — | — | — | — | — | — |" if not s["cascades"] else
        f"| {s['set']} | {', '.join(map(str, s['seeds']))} | {s['cascades']} ({s['easy']} EASY / {s['hard']} HARD) | "
        f"{s['distractors']} | {s['episodes']} | {s['noise']} | {s['cascades_detected']} | {s['roots_detected']} |" for s in sets)
    param_rows = "\n".join(f"| {gl[g]} | {chosen[g]['gap_seconds']} s | {chosen[g]['max_hops']} | {json.dumps(chosen[g]['weights'])} | "
                           f"{pct(chosen[g]['val_f1'])} | {pct(chosen[g]['val_top1'])} |" for g in graphs)
    param_rows += f"\n| Time-only baseline | {chosen['time_only']['gap_seconds']} s | (graph ignored) | — | {pct(chosen['time_only']['val_f1'])} | — |"

    def grouping_row(name, r):
        o = r["outcomes"]
        multi = sum(o.get(k, 0) for k in ("fully merged (clean)", "merged + distractor", "merged + other", "split"))
        return (f"| {name} | {pct(r['pairwise']['precision'])} | {pct(r['pairwise']['recall'])} | {pct(r['pairwise']['f1'])} | "
                f"{o.get('fully merged (clean)', 0)}/{multi} | {o.get('merged + distractor', 0)} | {o.get('merged + other', 0)} | {o.get('split', 0)} |")

    grouping = "\n".join([grouping_row(gl[g], t[g]) for g in graphs] + [grouping_row("Time-only baseline (no graph)", t["time_only"])])

    methods = [("ranking", "Weighted ranking (this method)"), ("earliest_onset", "Earliest onset only"),
               ("highest_peak", "Highest peak only"), ("most_dependents", "Most dependents (graph only)"),
               ("random", "Random (expected)")]

    def rc_table(scope):
        rows = []
        for key, label in methods:
            cells = [cell(t[g]["root_cause"][key][f"{scope} top{m}"]) for g in graphs for m in (1, 3)]
            rows.append(f"| {label} | " + " | ".join(cells) + " |")
        return "\n".join(rows)

    def rc_type_table():
        rows = []
        for key, label in methods[:4]:
            cells = [pct(t[g]["root_cause"][key][f"all top1 {ty}"][0]) for g in graphs for ty in ("EASY", "HARD")]
            rows.append(f"| {label} | " + " | ".join(cells) + " |")
        n = [t["full_call_graph"]["root_cause"]["ranking"][f"all top1 {ty}"][1] for ty in ("EASY", "HARD")]
        return "\n".join(rows), n

    type_rows, type_n = rc_type_table()
    doc = f"""# Phase 5.2 — incident correlation and root-cause evaluation

> **Scope:** measured on **synthetic cascades injected by the project's own simulator**, with
> labels written by the simulator. Not real-world accuracy. The correlation and root-cause method
> is a **heuristic, graph-based ranking — not a learned model**.

Generated by `ml-service/evaluation/run_correlation_experiments.py` ({ctx['generated_at']}, {ctx['runtime_s']:.0f} s). Reproduce from `ml-service/`:

```
python evaluation/run_correlation_experiments.py --val-seeds {' '.join(map(str, a.val_seeds))} --test-seeds {' '.join(map(str, a.test_seeds))} \\
    --train-seeds {' '.join(map(str, a.train_seeds))} --days {a.days}
```

## Setup

- **Data:** `python -m simulator.offline --mode cascade`, {a.days} simulated day(s) per seed, 8 services × 6 metrics, 5 s ticks.
  A cascade is a ROOT anomaly plus SYMPTOM anomalies on the services that call it (transitively,
  along the 7-edge call graph), each starting 10–60 s after the service it calls. EASY cascades
  have a strong root and weaker symptoms; HARD cascades have a subtle, slowly drifting root and
  clearly visible symptoms. 0–2 independent DISTRACTOR anomalies on unrelated services overlap
  each cascade in time.
- **Detector:** the deployed L1 (window {ctx['l1']['window']}, alpha {ctx['l1']['alpha']}, threshold {ctx['l1']['threshold']}),
  MAD floors fitted on TRAIN normal-mode seeds {', '.join(map(str, a.train_seeds))}.
- **Episodes:** per (service, metric), opened on the first flagged 5 s tick, extended while flagged,
  closed after {CLOSE_AFTER_TICKS} unflagged ticks (30 s ≈ the backend's 3 unflagged 10 s cycles); start/end =
  first/last flagged tick; peakScore = max L1 score. A detected episode belongs to a ground-truth
  label when service and metric match and it overlaps the label window (+{MATCH_TOLERANCE_S} s);
  otherwise it is a detector false alarm ("noise").
- **Correlation:** `app/correlation.py`, the same code as `POST /correlate`. The security events
  passed to the ranking are those in each incident's window (start − 60 s to end).
- **Graphs:** full call graph (7 edges) vs the 4 edges the backend's single `dependsOnServiceId`
  can store (missing api-gateway→auth-service, api-gateway→order-service, order-service→inventory-service).
- **Tuning (VALIDATION only, separately per graph):** gap ∈ {GAP_GRID} s and max_hops ∈ {HOPS_GRID}
  by pairwise F1 (with fixed default weights), then the weights from {len(WEIGHT_GRID)} candidate
  vectors by top-1 accuracy over all cascades. The time-only baseline's gap is tuned the same way.

| Set | Seeds | Cascades | Distractors | Detected episodes | Of which noise | Cascades with ≥1 detected episode | Cascades whose root was detected |
|---|---|---|---|---|---|---|---|
{set_rows}

Chosen parameters (VALIDATION):

| Graph | gap | max_hops | weights | VAL pairwise F1 | VAL top-1 (all) |
|---|---|---|---|---|---|
{param_rows}

## TEST results

### Grouping

Pairwise metrics over all detected episodes ("same incident" vs same ground-truth group; noise
episodes are singletons). Cascade outcomes count cascades with ≥ 2 detected episodes.

| Method | Pairwise precision | Pairwise recall | Pairwise F1 | Fully merged, clean | Merged with a distractor | Merged with other (noise / another cascade) | Split |
|---|---|---|---|---|---|---|---|
{grouping}

### Root cause — cascades whose root was detected

| Method | Full graph top-1 | Full graph top-3 | Backend graph top-1 | Backend graph top-3 |
|---|---|---|---|---|
{rc_table("root detected")}

### Root cause — all cascades (missed root or undetected cascade counted as wrong)

| Method | Full graph top-1 | Full graph top-3 | Backend graph top-1 | Backend graph top-3 |
|---|---|---|---|---|
{rc_table("all")}

### Root cause top-1 by cascade type (all cascades; EASY n={type_n[0]}, HARD n={type_n[1]})

| Method | Full EASY | Full HARD | Backend EASY | Backend HARD |
|---|---|---|---|---|
{type_rows}

Baselines are scored on the same incidents and candidates. Ties in a baseline are broken uniformly
at random (expected accuracy); the weighted ranking uses its own deterministic tie-break.

## Findings

{ctx['findings']}

## Limitations

- **Synthetic data only, cascade shapes authored by us.** The propagation rule (callers, 10–60 s
  delay per hop, weaker symptoms) was written by the same people who designed the ranking, so the
  ranking's onset and dependents inputs match the generator's assumptions by construction.
- **Tiny topology.** 8 services and 7 edges; almost every pair is within 2–3 hops via api-gateway,
  which limits how much the graph can separate unrelated anomalies.
- **Small number of cascades** (about {sets[2]['cascades']} on TEST); per-type cells hold a few dozen cascades.
- **Metric anomalies only** — no traces or logs are correlated; security events only enter the ranking.
- **Detector-dependent.** Results include L1's misses and false alarms; subtle (HARD) roots are often not detected at all.
- **One dependency per service in the backend.** The 4-edge results show what that costs today.
- **Coarse tuning grid.** The best gap for both graphs (240 s) is the largest value in the grid; a
  wider grid might do better. It was not extended, to avoid changing the method after seeing TEST.

## Anything changed after seeing TEST

{ctx['post_test_note']}
"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(doc)


# -- main ---------------------------------------------------------------------------------------

def describe(name, runs):
    cascades = [c for r in runs for c in r["cascades"].values()]
    eps = pd.concat([r["episodes"] for r in runs])
    detected = sum(1 for r in runs for g in r["cascades"] if (r["episodes"]["group"] == g).any())
    roots = sum(1 for r in runs for g, c in r["cascades"].items()
                if ((r["episodes"]["group"] == g) & (r["episodes"]["service_id"] == c["root"])).any())
    info = {"set": name, "seeds": [r["seed"] for r in runs], "cascades": len(cascades),
            "easy": sum(c["type"] == "EASY" for c in cascades), "hard": sum(c["type"] == "HARD" for c in cascades),
            "distractors": int(sum((r["episodes"]["role"] == "DISTRACTOR").sum() for r in runs)),
            "episodes": len(eps), "noise": int((eps["role"] == "NOISE").sum()),
            "cascades_detected": detected, "roots_detected": roots}
    print(f"[{name}] {info}", flush=True)
    return info


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--train-seeds", type=int, nargs="+", default=[101, 102, 103])
    p.add_argument("--val-seeds", type=int, nargs="+", default=[501, 502])
    p.add_argument("--test-seeds", type=int, nargs="+", default=[601, 602, 603])
    p.add_argument("--days", type=int, default=1)
    p.add_argument("--data-dir", default=os.path.join(REPO_ROOT, "datasets", "phase5"))
    p.add_argument("--doc-out", default=os.path.join(REPO_ROOT, "docs", "evaluation", "phase5-correlation.md"))
    p.add_argument("--json-out", default=os.path.join(REPO_ROOT, "docs", "evaluation", "phase5-correlation-results.json"))
    p.add_argument("--config-out", default=os.path.join(ML_ROOT, "app", "correlation_config.json"))
    a = p.parse_args(argv)
    if set(a.val_seeds) & set(a.test_seeds) or (set(a.val_seeds) | set(a.test_seeds)) & set(a.train_seeds):
        raise SystemExit("TRAIN / VALIDATION / TEST seeds must be disjoint")

    t0 = time.time()
    with open(os.path.join(ML_ROOT, "app", "model_config.json")) as f:
        l1 = json.load(f)["detectors"]["L1"]
    train = [load_run(generate(os.path.join(a.data_dir, f"train-normal-s{s}"), s, "normal", days=a.days), s, "normal")
             for s in a.train_seeds]
    detector = RobustZDetector(**l1).fit(normal_training_data(train))
    val = prepare(a.data_dir, "val", a.val_seeds, a.days, detector)
    test = prepare(a.data_dir, "test", a.test_seeds, a.days, detector)
    train_info = {"set": "TRAIN (normal mode; L1 floors only)", "seeds": a.train_seeds, "cascades": 0, "easy": 0, "hard": 0,
                  "distractors": 0, "episodes": 0, "noise": 0, "cascades_detected": 0, "roots_detected": 0}
    sets = [train_info, describe("VALIDATION", val), describe("TEST", test)]

    chosen = {g: tune(val, edges, g) for g, edges in GRAPHS.items()}
    chosen["time_only"] = tune(val, COMPLETE_GRAPH, "time_only")

    results = {g: evaluate(test, GRAPHS[g], chosen[g]["gap_seconds"], chosen[g]["max_hops"],
                           tuple(chosen[g]["weights"][k] for k in WEIGHT_KEYS)) for g in GRAPHS}
    results["time_only"] = evaluate(test, COMPLETE_GRAPH, chosen["time_only"]["gap_seconds"], 1,
                                    tuple(chosen["backend_4_edges"]["weights"][k] for k in WEIGHT_KEYS))

    findings = []
    for g, label in (("full_call_graph", "full 7-edge graph"), ("backend_4_edges", "backend 4-edge graph")):
        rk = results[g]["root_cause"]["ranking"]["all top1"][0]
        eo = results[g]["root_cause"]["earliest_onset"]["all top1"][0]
        rkd = results[g]["root_cause"]["ranking"]["root detected top1"][0]
        eod = results[g]["root_cause"]["earliest_onset"]["root detected top1"][0]
        n_all = results[g]["root_cause"]["ranking"]["all top1"][1]
        diff = round((rk - eo) * n_all)
        verdict = ("beats" if diff > 2 else "**does not beat**" if diff < -2 else
                   f"**is effectively tied with** (a difference of {abs(diff)} cascade{'s' if abs(diff) != 1 else ''})")
        findings.append(f"- **{label}:** the weighted ranking {verdict} the earliest-onset baseline on TEST top-1 over all cascades "
                        f"({pct(rk)} vs {pct(eo)}; {pct(rkd)} vs {pct(eod)} where the root was detected).")
    hard = results["full_call_graph"]["root_cause"]["ranking"]
    hard_n = hard["all top1 HARD"][1]
    hard_detected = hard["root detected top1 HARD"][1]
    findings.append(f"- **HARD cascades are mostly unsolved:** top-1 is {pct(hard['all top1 HARD'][0])} with the full graph, because "
                    f"L1 detected the subtle root in only {hard_detected} of {hard_n} HARD cascades; when the root is never "
                    f"detected, no ranking of the detected services can be right.")
    o = {g: results[g]["outcomes"] for g in ("full_call_graph", "backend_4_edges", "time_only")}
    multi = sum(o["full_call_graph"].get(k, 0) for k in ("fully merged (clean)", "merged + distractor", "merged + other", "split"))
    findings.append(f"- **Grouping errors differ by graph:** with the full graph {o['full_call_graph'].get('merged + distractor', 0)} of "
                    f"{multi} multi-episode cascades absorbed a distractor (almost every service is within one hop of another "
                    f"in this 8-service topology); with the backend's 4 edges {o['backend_4_edges'].get('split', 0)} of {multi} were "
                    f"split (the missing edges disconnect api-gateway and inventory-service); time alone merged "
                    f"{o['time_only'].get('merged + distractor', 0)} with a distractor.")
    f_full, f_back, f_time = (results[g]["pairwise"]["f1"] for g in ("full_call_graph", "backend_4_edges", "time_only"))
    findings.append(f"- **Grouping:** pairwise F1 {pct(f_full)} with the full graph, {pct(f_back)} with the backend's 4 edges, "
                    f"{pct(f_time)} with time alone - so with only the backend's 4 edges, adding the graph makes grouping "
                    f"{'worse' if f_back < f_time else 'better'} than ignoring it.")
    d1 = results["full_call_graph"]["root_cause"]["ranking"]["all top1"][0] - results["backend_4_edges"]["root_cause"]["ranking"]["all top1"][0]
    findings.append(f"- **Cost of the single-dependency limitation:** top-1 over all cascades is {abs(100 * d1):.1f} points "
                    f"{'lower' if d1 > 0 else 'higher'} with the backend's 4 edges than with the full graph; pairwise F1 differs by "
                    f"{100 * (f_full - f_back):+.1f} points (full − backend).")

    # Deployed default: the 4-edge profile, because that is the graph backend-core can send today.
    config = {"default_profile": "backend_4_edges",
              "profiles": {g: {k: chosen[g][k] for k in ("gap_seconds", "max_hops", "weights")} for g in GRAPHS},
              "selected_by": f"VALIDATION seeds {a.val_seeds}: gap/max_hops by pairwise F1, then weights by top-1 over all cascades, per graph",
              "evaluation": "docs/evaluation/phase5-correlation.md"}
    ctx = {"args": a, "sets": sets, "chosen": chosen, "test": results, "l1": l1, "findings": "\n".join(findings),
           "post_test_note": ("No parameter, grid, objective or episode rule was changed after the TEST run. After seeing TEST, only "
                               "the wording of the findings was revised: differences of 2 cascades or fewer are now described as "
                               "ties (the first draft called a 1-cascade difference a win), and the HARD-cascade and "
                               "grouping-outcome findings plus the grid-edge limitation were added. Re-running produced identical numbers."),
           "generated_at": time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime()), "runtime_s": time.time() - t0}
    write_report(a.doc_out, ctx)
    with open(a.json_out, "w") as f:
        json.dump({"sets": sets, "chosen": chosen, "test": results, "args": vars(a)}, f, indent=2, default=str)
    with open(a.config_out, "w") as f:
        json.dump(config, f, indent=2)
        f.write("\n")
    print("\n".join(findings))
    print(f"wrote {a.doc_out}, {a.json_out}, {a.config_out} ({time.time() - t0:.0f} s)")


if __name__ == "__main__":
    main()
