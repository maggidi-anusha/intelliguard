"""Load the simulator's offline datasets (python -m simulator.offline) into per-service frames,
with ground truth taken from labels.csv. Synthetic, injected anomalies only."""
from __future__ import annotations

import json
import os
import subprocess
import sys
from dataclasses import dataclass, field
from typing import Dict, Optional

import numpy as np
import pandas as pd

from .detectors.base import METRICS

DEFAULT_SIMULATOR_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "simulator")


def simulator_path():
    """The simulator source dir: $SIMULATOR_PATH (set in the Docker image) or ../simulator."""
    return os.path.abspath(os.environ.get("SIMULATOR_PATH", DEFAULT_SIMULATOR_PATH))


def generate(out_dir, seed, mode, days=0, minutes=0):
    """Run the simulator's own CLI (same generator as the live simulator). Skips if already there:
    the generator is deterministic, so an existing run with the same args is the same data."""
    if os.path.exists(os.path.join(out_dir, "labels.csv")):
        return out_dir
    sim = simulator_path()
    cmd = [sys.executable, "-m", "simulator.offline", "--seed", str(seed), "--mode", mode, "--out", os.path.abspath(out_dir)]
    if days:
        cmd += ["--days", str(days)]
    if minutes:
        cmd += ["--minutes", str(minutes)]
    subprocess.run(cmd, cwd=os.path.dirname(sim), check=True, stdout=subprocess.DEVNULL,
                   env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
    return out_dir


@dataclass
class Run:
    """One generated run. frames[service_id]: timestamp-indexed DataFrame, one column per metric.
    truth[service_id]: per-row bool - any metric of that service injected at that tick.
    episode_of[service_id]: per-row episode id (None when not injected)."""
    path: str
    seed: int
    mode: str
    frames: Dict[int, pd.DataFrame]
    truth: Dict[int, pd.Series] = field(default_factory=dict)
    episode_of: Dict[int, pd.Series] = field(default_factory=dict)
    episodes: Optional[pd.DataFrame] = None
    injected_metric_rows: int = 0
    metric_rows: int = 0


def load_run(path, seed=None, mode=None) -> Run:
    metrics = pd.read_csv(os.path.join(path, "metrics.csv"))
    metrics["timestamp"] = pd.to_datetime(metrics["timestamp"], utc=True)
    wide = metrics.pivot(index=["service_id", "timestamp"], columns="metric_type", values="value")[METRICS]
    frames = {int(sid): g.droplevel(0).sort_index() for sid, g in wide.groupby(level=0)}
    run = Run(path, seed, mode, frames, metric_rows=len(metrics))

    labels = pd.read_csv(os.path.join(path, "labels.csv"))
    labels = labels[labels["signal_type"] == "METRIC"].copy()
    for col in ("start_time", "end_time"):
        labels[col] = pd.to_datetime(labels[col], utc=True)
    labels["episode_id"] = [json.loads(p)["episode_id"] for p in labels["params"]]

    for sid, frame in frames.items():
        run.truth[sid] = pd.Series(False, index=frame.index)
        run.episode_of[sid] = pd.Series(None, index=frame.index, dtype=object)

    for row in labels.itertuples():
        idx = run.frames[row.service_id].index
        mask = (idx >= row.start_time) & (idx <= row.end_time)
        run.truth[row.service_id] |= mask
        run.episode_of[row.service_id][mask] = row.episode_id
        run.injected_metric_rows += int(mask.sum())

    if len(labels):
        run.episodes = (labels.groupby("episode_id")
                        .agg(service_id=("service_id", "first"), scenario_type=("scenario_type", "first"),
                             difficulty=("difficulty", "first"), start=("start_time", "min"), end=("end_time", "max"))
                        .reset_index())
    else:
        run.episodes = pd.DataFrame(columns=["episode_id", "service_id", "scenario_type", "difficulty", "start", "end"])
    return run


def normal_training_data(runs):
    """{service_id: [frame per run]} - each run kept separate so rolling features never span runs."""
    train = {}
    for run in runs:
        for sid, frame in run.frames.items():
            train.setdefault(sid, []).append(frame)
    return train


def service_tick_count(run):
    return int(sum(len(f) for f in run.frames.values()))


def stack(per_service):
    """Concatenate per-service Series/arrays in service order."""
    return np.concatenate([np.asarray(per_service[s]) for s in sorted(per_service)])
