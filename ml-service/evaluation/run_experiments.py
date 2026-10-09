"""Phase 4.1/4.2 experiments: compare L0 / L1 / L2 on the simulator's labeled synthetic data.

    cd ml-service && python evaluation/run_experiments.py            # defaults below
    python evaluation/run_experiments.py --train-seeds 101 102 103 --val-seeds 201 202 \
        --val-normal-seed 203 --test-seeds 301 302 303 --test-normal-seed 304 --days 1 --alarm-budget 1.0

Data comes from `python -m simulator.offline` (generated if missing; deterministic per seed).
TRAIN = normal-mode runs (no anomalies) used to fit L1 floors and L2 forests.
VALIDATION = labeled continuous runs (+ one normal run for the false-alarm budget), used ONLY
to pick hyperparameters/thresholds - at two operating points: best row F1, and best episode
recall within a false-alarm budget.
TEST = labeled continuous runs (+ one normal run for false alarms), never used for tuning.
All results are on synthetic injected anomalies - not real-world accuracy.

Evaluation unit ("row"): one service at one tick (its 6 metric values together). A row is
anomalous if any of that service's metrics was injected at that tick (labels.csv METRIC rows).
"""
from __future__ import annotations

import argparse
import copy
import itertools
import json
import os
import sys
import time
from collections import Counter

import numpy as np
from sklearn.metrics import precision_recall_curve

ML_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_ROOT = os.path.dirname(ML_ROOT)
sys.path.insert(0, ML_ROOT)

from app.data import generate, load_run, normal_training_data, simulator_path  # noqa: E402
from app.detectors import IsolationForestDetector, RobustZDetector, ThresholdDetector  # noqa: E402

TICK_SECONDS = 5
SCENARIOS = ["SPIKE", "LEVEL_SHIFT", "DRIFT", "VARIANCE", "CORRELATED"]
L1_GRID = {"window": [20, 40, 60], "alpha": [0.2, 0.4, 1.0]}
L2_GRID = {"window": [20, 60]}


# -- data ------------------------------------------------------------------------------------

def load_set(data_dir, name, seeds, mode, days):
    runs = []
    for seed in seeds:
        path = generate(os.path.join(data_dir, f"{name}-{mode}-s{seed}"), seed, mode, days=days)
        runs.append(load_run(path, seed, mode))
    return runs


def describe(name, runs):
    combos = Counter()
    for r in runs:
        combos.update(zip(r.episodes["scenario_type"], r.episodes["difficulty"]))
    info = {
        "set": name, "seeds": [r.seed for r in runs], "mode": runs[0].mode,
        "metric_rows": sum(r.metric_rows for r in runs),
        "anomalous_metric_rows": sum(r.injected_metric_rows for r in runs),
        "service_ticks": sum(len(f) for r in runs for f in r.frames.values()),
        "anomalous_service_ticks": int(sum(t.sum() for r in runs for t in r.truth.values())),
        "episodes": int(sum(len(r.episodes) for r in runs)),
        "episodes_by_type": {f"{s}/{d}": combos[(s, d)] for s in SCENARIOS for d in ("EASY", "SUBTLE")},
    }
    print(f"[{name}] seeds={info['seeds']} rows={info['metric_rows']:,} anomalous_rows={info['anomalous_metric_rows']:,} "
          f"episodes={info['episodes']}  missing combos={[k for k, v in info['episodes_by_type'].items() if v == 0] or 'none'}")
    return info


# -- scoring ---------------------------------------------------------------------------------

def score_runs(detector, runs):
    """Per run and service: (raw score with invalid rows = 0, flag with invalid rows = False)."""
    out = []
    for run in runs:
        per = {}
        for sid, frame in run.frames.items():
            res = detector.score(frame, sid)
            valid = res.valid.to_numpy()
            per[sid] = (np.where(valid, res.raw.to_numpy(), 0.0), res.service_flag.to_numpy() & valid)
        out.append(per)
    return out


def best_threshold(scored, runs):
    """Threshold on the raw service score that maximises row-level F1 (VALIDATION only)."""
    scores = np.concatenate([s[sid][0] for s, r in zip(scored, runs) for sid in sorted(r.frames)])
    truth = np.concatenate([r.truth[sid].to_numpy() for r in runs for sid in sorted(r.frames)])
    precision, recall, thresholds = precision_recall_curve(truth, scores)
    f1 = 2 * precision * recall / np.maximum(precision + recall, 1e-12)
    i = int(np.nanargmax(f1[:-1]))
    # precision_recall_curve flags score >= t; detectors flag score > threshold.
    return float(np.nextafter(thresholds[i], -np.inf)), float(f1[i]), float(precision[i]), float(recall[i])


# -- metrics ---------------------------------------------------------------------------------

def alarm_count(raws, threshold):
    """Alarms (runs of consecutive rows above threshold) summed over per-service score arrays."""
    total = 0
    for r in raws:
        f = r > threshold
        total += int(f[0]) + int((f[1:] & ~f[:-1]).sum())
    return total


def budget_threshold(scored_normal, runs_normal, budget_per_hour):
    """Smallest threshold whose alarm rate on normal-mode VALIDATION data is within the budget."""
    raws = [s[sid][0] for s, r in zip(scored_normal, runs_normal) for sid in sorted(r.frames)]
    hours = sum(len(next(iter(r.frames.values()))) * TICK_SECONDS / 3600 for r in runs_normal)
    candidates = np.unique(np.concatenate(raws))
    lo, hi = 0, len(candidates) - 1
    while lo < hi:  # alarm count falls (near-monotonically) as the threshold rises
        mid = (lo + hi) // 2
        if alarm_count(raws, candidates[mid]) / hours <= budget_per_hour:
            hi = mid
        else:
            lo = mid + 1
    return float(candidates[lo]), alarm_count(raws, candidates[lo]) / hours


def evaluate(flags_per_run, runs, chance_rate=None):
    """Row and episode metrics overall and per slice (difficulty, scenario type).
    chance_rate: a detector's flag rate on normal data; adds the episode recall a random
    detector flagging rows at that rate would get (1 - (1-p)^episode_length)."""
    flag, truth, ep_key = [], [], []
    episodes = {}
    for flags, run in zip(flags_per_run, runs):
        for sid in sorted(run.frames):
            flag.append(flags[sid])
            truth.append(run.truth[sid].to_numpy())
            ep_key.append(np.array([f"{run.seed}:{e}" if e is not None else "" for e in run.episode_of[sid]], dtype=object))
        for e in run.episodes.itertuples():
            episodes[f"{run.seed}:{e.episode_id}"] = (e.scenario_type, e.difficulty)
    flag, truth, ep_key = np.concatenate(flag), np.concatenate(truth), np.concatenate(ep_key)

    # Detection per episode: any flagged row in its window; delay = ticks from its first injected row.
    detected, delay, length = {}, {}, {}
    order = np.argsort(ep_key, kind="stable")  # rows of one episode stay in time order
    keys, starts = np.unique(ep_key[order], return_index=True)
    bounds = list(starts) + [len(order)]
    for k, a, b in zip(keys, bounds[:-1], bounds[1:]):
        if k == "":
            continue
        length[k] = b - a
        hits = np.flatnonzero(flag[order[a:b]])
        detected[k] = hits.size > 0
        if hits.size:
            delay[k] = int(hits[0])

    false_pos = int((flag & ~truth).sum())
    slices = {"ALL": lambda s, d: True, "EASY": lambda s, d: d == "EASY", "SUBTLE": lambda s, d: d == "SUBTLE"}
    slices.update({sc: (lambda s, d, sc=sc: s == sc) for sc in SCENARIOS})
    result = {}
    for name, pred in slices.items():
        members = {k for k, (s, d) in episodes.items() if pred(s, d)}
        pos = truth & np.isin(ep_key, list(members))
        tp, fn = int((flag & pos).sum()), int((~flag & pos).sum())
        precision = tp / (tp + false_pos) if tp + false_pos else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        found = [k for k in members if detected.get(k)]
        result[name] = {
            "episodes": len(members), "episodes_detected": len(found),
            "episode_recall": len(found) / len(members) if members else None,
            "row_precision": precision, "row_recall": recall,
            "row_f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
            "mean_delay_ticks": float(np.mean([delay[k] for k in found])) if found else None,
            "chance_episode_recall": (float(np.mean([1 - (1 - chance_rate) ** length[k] for k in members]))
                                      if chance_rate is not None and members else None),
        }
    return result


def false_alarms(flags_per_run, runs):
    """On normal-mode runs every flag is false. An alarm = a run of consecutive flagged rows on one service."""
    alarms, flagged, hours = 0, 0, 0.0
    for flags, run in zip(flags_per_run, runs):
        for sid, f in flags.items():
            f = f.astype(int)
            alarms += int(((f[1:] == 1) & (f[:-1] == 0)).sum() + f[0])
            flagged += int(f.sum())
        hours += len(next(iter(run.frames.values()))) * TICK_SECONDS / 3600
    return {"hours": hours, "alarms": alarms, "alarms_per_hour": alarms / hours,
            "flagged_service_ticks_per_hour": flagged / hours}


# -- report ----------------------------------------------------------------------------------

def pct(x):
    return "n/a" if x is None else f"{100 * x:.1f}%"


def fmt_delay(x):
    return "n/a" if x is None else f"{x:.1f}"


def results_table(metrics, names, slices):
    lines = ["| Slice | Detector | Episodes detected | Episode recall | Chance episode recall* | Row precision | Row recall | Row F1 | Mean delay (ticks) |",
             "|---|---|---|---|---|---|---|---|---|"]
    for sl in slices:
        for name in names:
            r = metrics[name][sl]
            lines.append(f"| {sl} | {name} | {r['episodes_detected']}/{r['episodes']} | {pct(r['episode_recall'])} | "
                         f"{pct(r['chance_episode_recall'])} | {pct(r['row_precision'])} | {pct(r['row_recall'])} | "
                         f"{pct(r['row_f1'])} | {fmt_delay(r['mean_delay_ticks'])} |")
    return "\n".join(lines)


def fa_table(fa, names):
    return "\n".join(["| Detector | Alarms | Alarms per hour | Flagged service-ticks per hour |", "|---|---|---|---|"]
                     + [f"| {n} | {fa[n]['alarms']} | {fa[n]['alarms_per_hour']:.2f} | {fa[n]['flagged_service_ticks_per_hour']:.1f} |"
                        for n in names])


def comparisons(metrics, learned, slices):
    out = []
    for name in learned:
        for sl in slices:
            l0, d = metrics["L0"][sl], metrics[name][sl]
            worse = []
            if (d["episode_recall"] or 0) < (l0["episode_recall"] or 0):
                worse.append(f"episode recall {pct(d['episode_recall'])} vs L0 {pct(l0['episode_recall'])}")
            if d["row_f1"] < l0["row_f1"]:
                worse.append(f"row F1 {pct(d['row_f1'])} vs L0 {pct(l0['row_f1'])}")
            if worse:
                out.append(f"- **{name} does not beat L0 on {sl}:** " + "; ".join(worse) + ".")
    return "\n".join(out) or "- Every learned detector matches or beats L0 on every slice for episode recall and row F1."


SLICES = ["ALL", "EASY", "SUBTLE"] + SCENARIOS


def write_report(path, ctx):
    a, v, t = ctx["args"], ctx["validation"], ctx["test"]
    sets = "\n".join(f"| {s['set']} | {s['mode']} | {', '.join(map(str, s['seeds']))} | {s['metric_rows']:,} | "
                     f"{s['anomalous_metric_rows']:,} | {s['anomalous_service_ticks']:,} / {s['service_ticks']:,} | {s['episodes']} |"
                     for s in ctx["sets"])
    labeled = [s for s in ctx["sets"] if s["mode"] == "continuous"]
    combos = "\n".join(f"| {k} | " + " | ".join(str(s["episodes_by_type"][k]) for s in labeled) + " |"
                       for k in labeled[0]["episodes_by_type"])
    l1_grid = "\n".join(f"| {g['window']} | {g['alpha']} | {g['threshold']:.3f} | {pct(g['f1'])} | {g['budget_threshold']:.3f} | "
                        f"{g['budget_alarms_per_hour']:.2f} | {pct(g['budget_episode_recall'])} | {pct(g['budget_f1'])} |" for g in v["L1_grid"])
    l2_grid = "\n".join(f"| {g['window']} | {g['threshold']:.4f} | {pct(g['f1'])} | {g['budget_threshold']:.4f} | "
                        f"{g['budget_alarms_per_hour']:.2f} | {pct(g['budget_episode_recall'])} | {pct(g['budget_f1'])} |" for g in v["L2_grid"])
    names_f1, names_b = ["L0", "L1-F1", "L2-F1"], ["L0", "L1-budget", "L2-budget"]
    val_rows = "\n".join(f"| {n} | {pct(v['metrics'][n]['ALL']['row_precision'])} | {pct(v['metrics'][n]['ALL']['row_recall'])} | "
                         f"{pct(v['metrics'][n]['ALL']['row_f1'])} | {pct(v['metrics'][n]['ALL']['episode_recall'])} |"
                         for n in ["L0", "L1-F1", "L2-F1", "L1-budget", "L2-budget"])
    chosen = ctx["chosen"]
    doc = f"""# Phase 4.1/4.2 — anomaly detector experiments

> **Scope:** every number here is measured on **synthetic anomalies injected by the project's
> own simulator**, against labels the simulator wrote. It is not real-world detection accuracy.

Generated by `ml-service/evaluation/run_experiments.py` on {ctx['generated_at']} (wall time {ctx['runtime_s']:.0f} s).
Reproduce from `ml-service/`:

```
python evaluation/run_experiments.py --train-seeds {' '.join(map(str, a.train_seeds))} --val-seeds {' '.join(map(str, a.val_seeds))} \\
    --val-normal-seed {a.val_normal_seed} --test-seeds {' '.join(map(str, a.test_seeds))} --test-normal-seed {a.test_normal_seed} \\
    --days {a.days} --alarm-budget {a.alarm_budget}
```

## Data

Each run is {a.days} simulated day(s) from `python -m simulator.offline` (5 s ticks, 8 services × 6 metrics).
Seeds are disjoint across sets. TEST was never used to fit or choose any parameter.

| Set | Mode | Seeds | Metric rows | Anomalous metric rows | Anomalous rows (service-ticks) | Episodes |
|---|---|---|---|---|---|---|
{sets}

Episodes per scenario/difficulty:

| Scenario/difficulty | VALIDATION | TEST |
|---|---|---|
{combos}

**Units.** A *row* is one service at one tick (its six metric values together); it is anomalous if
any of that service's metrics was injected at that tick. *Episode recall*: an episode counts as
detected if any flagged row of its service falls inside its label window. *Delay*: ticks (5 s)
from the episode's first injected row to the first flagged row, over detected episodes only.
*Per-slice precision*: the slice's anomalous rows plus all normal rows (a false alarm belongs to
no episode, so false positives are shared across slices). *Chance episode recall*: what a
detector flagging rows completely at random, at the same rate this detector flags normal
rows (TEST normal run), would score — `1 − (1 − p)^length` averaged over the slice's episodes.
Long episodes get "detected" by chance at high flag rates, so episode recall is only
meaningful next to this column and the false-alarm rate.

## Detectors

- **L0** — the exact Phase 3 rule: CPU/MEMORY/DISK > 90, ERROR_RATE > 5, LATENCY > 500; NETWORK has no rule. No tuning.
- **L1** — per service and metric, robust z-score of each value against the median/MAD of the previous *window* rows, |z| smoothed with an EWMA (*alpha*), flagged above *threshold*. MAD floor = 0.25 × the metric's robust sigma on TRAIN.
- **L2** — one Isolation Forest per service (200 trees, max_samples 256, random_state 0) on 18 features (value, value − rolling mean, short std − rolling std, per metric), fitted on TRAIN normal data only; flagged above *threshold* on the anomaly score.

Each learned detector is evaluated at two operating points, both chosen on VALIDATION only:

1. **F1** (the original plan) — the threshold maximising row-level F1 on VALIDATION; the grid point with the best F1.
2. **budget** — the lowest threshold that keeps false alarms ≤ {a.alarm_budget} per hour (all 8 services together) on a
   VALIDATION normal-mode run (seed {a.val_normal_seed}); the grid point with the best VALIDATION episode recall at that threshold.
   *This operating point was added after the F1 results on TEST showed F1-tuned thresholds produce
   tens to hundreds of false alarms per hour. Its thresholds still come only from VALIDATION, but
   the decision to report it was informed by TEST, so read its TEST numbers with that in mind.*

L1 grid (VALIDATION):

| window | alpha | F1 threshold | F1 | budget threshold | VAL normal alarms/h | budget episode recall | budget F1 |
|---|---|---|---|---|---|---|---|
{l1_grid}

L2 grid (VALIDATION):

| window | F1 threshold | F1 | budget threshold | VAL normal alarms/h | budget episode recall | budget F1 |
|---|---|---|---|---|---|---|
{l2_grid}

**Chosen:** L1-F1 `{json.dumps(chosen['L1-F1'])}`, L2-F1 `{json.dumps(chosen['L2-F1'])}`,
L1-budget `{json.dumps(chosen['L1-budget'])}`, L2-budget `{json.dumps(chosen['L2-budget'])}`.

VALIDATION, all episodes, at the chosen settings:

| Detector | Row precision | Row recall | Row F1 | Episode recall |
|---|---|---|---|---|
{val_rows}

## TEST results — F1 operating point (the original plan)

### Table 1 — by difficulty

{results_table(t['metrics'], names_f1, ['ALL', 'EASY', 'SUBTLE'])}

### Table 2 — by scenario type

{results_table(t['metrics'], names_f1, SCENARIOS)}

### Table 3 — false alarms on a normal-mode TEST run (seed {a.test_normal_seed}, {t['false_alarms']['L0']['hours']:.0f} h, no anomalies; an alarm = a run of consecutive flagged rows on one service)

{fa_table(t['false_alarms'], names_f1)}

## TEST results — budget operating point (≤ {a.alarm_budget} false alarm/hour on VALIDATION normal data)

### Table 4 — by difficulty and scenario type

{results_table(t['metrics'], names_b, SLICES)}

### Table 5 — false alarms on the normal-mode TEST run

{fa_table(t['false_alarms'], names_b)}

## Where a learned detector does not beat L0 (TEST)

{comparisons(t['metrics'], ['L1-F1', 'L2-F1', 'L1-budget', 'L2-budget'], SLICES)}

## Default detector for the scoring service

{ctx['choice_text']}

## Limitations

- **Synthetic data only.** Every anomaly was injected by our own simulator, and the shapes
  (spike, level shift, drift, variance, correlated) and their magnitudes were written by the
  same author who designed the detectors. Results say how well each detector finds *these*
  shapes, not how it would do on real production telemetry.
- **The normal data is unrealistically clean.** Baselines are stationary Gaussian noise with no
  daily cycles, deploys, traffic bursts or slow trends, so false-alarm rates here are likely
  optimistic for L1 and L2 — and L0's zero false alarms holds only because normal values can
  never reach its thresholds in this simulator.
- **Small number of episodes.** {ctx['sets'][2]['episodes']} TEST episodes, 14–26 per scenario/difficulty cell; a
  single episode moves a cell's recall by 4–7 percentage points. Treat small differences as noise.
- **EASY anomalies are separable by construction** (values above the normal ceiling), so high
  EASY numbers are expected and are not evidence of a strong detector.
- **Episode recall can be inflated by chance** at high flag rates (see the chance column); the
  F1-tuned L2 in particular detects most episodes largely because it flags ~6% of normal rows.
- **Row-level metrics favour short episodes.** Rolling baselines (L1, and L2's mean/std
  features) adapt to long level shifts and drifts, so later rows of long episodes are often
  missed even when the episode itself is detected.
- **Metrics only.** Security events and logs are not used, and security labels are not evaluated.
- **One post-hoc analysis choice** (the budget operating point) was made after seeing TEST
  results, as stated above.
"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(doc)


# -- main ------------------------------------------------------------------------------------

def flags_at(scored, threshold):
    return [{sid: (raw > threshold) for sid, (raw, _) in per.items()} for per in scored]


def flag_rate(flags_per_run):
    flagged = sum(int(f.sum()) for per in flags_per_run for f in per.values())
    total = sum(len(f) for per in flags_per_run for f in per.values())
    return flagged / total


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--train-seeds", type=int, nargs="+", default=[101, 102, 103])
    p.add_argument("--val-seeds", type=int, nargs="+", default=[201, 202])
    p.add_argument("--val-normal-seed", type=int, default=203)
    p.add_argument("--test-seeds", type=int, nargs="+", default=[301, 302, 303])
    p.add_argument("--test-normal-seed", type=int, default=304)
    p.add_argument("--days", type=int, default=1, help="simulated days per run")
    p.add_argument("--alarm-budget", type=float, default=1.0, help="false alarms/hour (all services) for the budget operating point")
    p.add_argument("--data-dir", default=os.path.join(REPO_ROOT, "datasets", "phase4"))
    p.add_argument("--doc-out", default=os.path.join(REPO_ROOT, "docs", "evaluation", "phase4-experiments.md"))
    p.add_argument("--json-out", default=os.path.join(REPO_ROOT, "docs", "evaluation", "phase4-results.json"))
    p.add_argument("--config-out", default=os.path.join(ML_ROOT, "app", "model_config.json"))
    a = p.parse_args(argv)

    all_seeds = a.train_seeds + a.val_seeds + [a.val_normal_seed] + a.test_seeds + [a.test_normal_seed]
    if len(set(all_seeds)) != len(all_seeds):
        raise SystemExit("seeds must be disjoint across TRAIN / VALIDATION / TEST")

    t0 = time.time()
    print(f"simulator: {simulator_path()}  data: {a.data_dir}")
    train = load_set(a.data_dir, "train", a.train_seeds, "normal", a.days)
    val = load_set(a.data_dir, "val", a.val_seeds, "continuous", a.days)
    val_normal = load_set(a.data_dir, "val", [a.val_normal_seed], "normal", a.days)
    test = load_set(a.data_dir, "test", a.test_seeds, "continuous", a.days)
    test_normal = load_set(a.data_dir, "test", [a.test_normal_seed], "normal", a.days)
    sets = [describe("TRAIN", train), describe("VALIDATION", val), describe("TEST", test),
            describe("VALIDATION-normal", val_normal), describe("TEST-normal", test_normal)]
    train_data = normal_training_data(train)

    # ---- tuning on VALIDATION only: both operating points per grid point ----
    def tune(make, grid_keys, grid):
        rows, fitted = [], {}
        for values in itertools.product(*grid):
            params = dict(zip(grid_keys, values))
            det = make(**params).fit(train_data)
            scored_val = score_runs(det, val)
            thr, f1, _, _ = best_threshold(scored_val, val)
            b_thr, b_rate = budget_threshold(score_runs(det, val_normal), val_normal, a.alarm_budget)
            b_eval = evaluate(flags_at(scored_val, b_thr), val)["ALL"]
            rows.append({**params, "threshold": thr, "f1": f1, "budget_threshold": b_thr, "budget_alarms_per_hour": b_rate,
                         "budget_episode_recall": b_eval["episode_recall"], "budget_f1": b_eval["row_f1"]})
            fitted[tuple(values)] = det
            print(f"  {det.name} {params}: VAL F1={f1:.3f}@{thr:.3f} | budget thr={b_thr:.3f} alarms/h={b_rate:.2f} "
                  f"episode recall={b_eval['episode_recall']:.3f} F1={b_eval['row_f1']:.3f}")
        best_f1 = max(rows, key=lambda r: r["f1"])
        best_b = max(rows, key=lambda r: (r["budget_episode_recall"], r["budget_f1"]))
        return rows, fitted, best_f1, best_b

    l1_rows, l1_fit, l1_f1, l1_b = tune(RobustZDetector, ["window", "alpha"], [L1_GRID["window"], L1_GRID["alpha"]])
    l2_rows, l2_fit, l2_f1, l2_b = tune(IsolationForestDetector, ["window"], [L2_GRID["window"]])

    def configured(fitted, row, keys, threshold):
        """The fitted detector for that grid point, at the given threshold (shallow copy)."""
        clone = copy.copy(fitted[tuple(row[k] for k in keys)])
        clone.threshold = threshold
        return clone

    detectors = {
        "L0": ThresholdDetector(),
        "L1-F1": configured(l1_fit, l1_f1, ["window", "alpha"], l1_f1["threshold"]),
        "L2-F1": configured(l2_fit, l2_f1, ["window"], l2_f1["threshold"]),
        "L1-budget": configured(l1_fit, l1_b, ["window", "alpha"], l1_b["budget_threshold"]),
        "L2-budget": configured(l2_fit, l2_b, ["window"], l2_b["budget_threshold"]),
    }

    # ---- evaluation: VALIDATION (for the choice) and TEST (reported) ----
    val_metrics, test_metrics, fa = {}, {}, {}
    for n, d in detectors.items():
        val_metrics[n] = evaluate([{s: v[1] for s, v in r.items()} for r in score_runs(d, val)], val)
        normal_flags = [{s: v[1] for s, v in r.items()} for r in score_runs(d, test_normal)]
        fa[n] = false_alarms(normal_flags, test_normal)
        test_metrics[n] = evaluate([{s: v[1] for s, v in r.items()} for r in score_runs(d, test)], test,
                                   chance_rate=flag_rate(normal_flags))
        m = test_metrics[n]["ALL"]
        print(f"  TEST {n:9}: episode recall {pct(m['episode_recall'])} (chance {pct(m['chance_episode_recall'])}), "
              f"row P/R/F1 {pct(m['row_precision'])}/{pct(m['row_recall'])}/{pct(m['row_f1'])}, "
              f"delay {fmt_delay(m['mean_delay_ticks'])}, false alarms/h {fa[n]['alarms_per_hour']:.2f}")

    # ---- default for the scoring service: the budget operating point, picked on VALIDATION ----
    vb = {n: val_metrics[n]["ALL"] for n in ("L1-budget", "L2-budget")}
    default = max(vb, key=lambda n: (vb[n]["episode_recall"], vb[n]["row_f1"]))
    other = "L2-budget" if default == "L1-budget" else "L1-budget"
    tb = {n: test_metrics[n]["ALL"] for n in vb}
    agrees = (tb[default]["episode_recall"], tb[default]["row_f1"]) >= (tb[other]["episode_recall"], tb[other]["row_f1"])
    vf = {n: val_metrics[n]["ALL"]["row_f1"] for n in ("L1-F1", "L2-F1")}
    choice_text = (
        f"**{default.split('-')[0]} at the budget operating point** is the default (`{json.dumps(detectors[default].params())}`). "
        f"It was picked on VALIDATION: at ≤ {a.alarm_budget} false alarm/hour it detects {pct(vb[default]['episode_recall'])} of "
        f"VALIDATION episodes vs {pct(vb[other]['episode_recall'])} for {other.split('-')[0]}. On TEST: episode recall "
        f"{pct(tb[default]['episode_recall'])} vs {pct(tb[other]['episode_recall'])}, row F1 {pct(tb[default]['row_f1'])} vs "
        f"{pct(tb[other]['row_f1'])}, false alarms/hour {fa[default]['alarms_per_hour']:.2f} vs {fa[other]['alarms_per_hour']:.2f} — "
        + ("TEST agrees with the VALIDATION choice." if agrees else "**TEST does not agree with the VALIDATION choice**; it was kept "
           "because switching after seeing TEST would be tuning on TEST.")
        + f"\n\nThe original plan (best VALIDATION row F1) would have picked "
        f"**{max(vf, key=vf.get).split('-')[0]}-F1** ({pct(max(vf.values()))} VALIDATION F1). It was not used as the service default "
        f"because its threshold flags {fa[max(vf, key=vf.get)]['alarms_per_hour']:.0f} false alarms/hour on the normal TEST run, "
        f"which no alerting service could act on. The scoring service also returns the L0 result alongside for comparison.")

    chosen = {n: d.params() for n, d in detectors.items() if n != "L0"}
    ctx = {"args": a, "sets": sets, "chosen": chosen, "choice_text": choice_text,
           "validation": {"L1_grid": l1_rows, "L2_grid": l2_rows, "metrics": val_metrics},
           "test": {"metrics": test_metrics, "false_alarms": fa},
           "generated_at": time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime()), "runtime_s": time.time() - t0}
    write_report(a.doc_out, ctx)
    with open(a.json_out, "w") as f:
        json.dump({k: v for k, v in ctx.items() if k != "args"} | {"args": vars(a)}, f, indent=2, default=str)
    with open(a.config_out, "w") as f:
        json.dump({"default_detector": default.split("-")[0], "operating_point": "budget",
                   "alarm_budget_per_hour": a.alarm_budget, "detectors": {
                       "L1": chosen["L1-budget"], "L2": chosen["L2-budget"]},
                   "train": {"seeds": a.train_seeds, "days": a.days, "mode": "normal"},
                   "selected_by": f"VALIDATION episode recall at <= {a.alarm_budget} false alarm/hour (seeds {a.val_seeds}, normal {a.val_normal_seed})",
                   "evaluation": "docs/evaluation/phase4-experiments.md"}, f, indent=2)
        f.write("\n")
    print(f"default detector: {default}; wrote {a.doc_out}, {a.json_out}, {a.config_out} ({time.time() - t0:.0f} s)")


if __name__ == "__main__":
    main()
