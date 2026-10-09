import os
import sys
from datetime import datetime, timedelta, timezone

import pytest

# The simulator modules use flat imports (they run as /app/*.py in the container).
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from engine import SimulationEngine  # noqa: E402
from topology import SEED_SERVICES  # noqa: E402

T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)
SERVICES = {s["name"]: i for i, s in enumerate(SEED_SERVICES, 1)}
SCHEDULE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scenarios", "anomaly_schedule.json")


def run_engine(engine, minutes, tick_seconds=5, start=T0):
    """Tick an engine over simulated time; returns (metric_rows, security_rows, labels)."""
    metrics, security, labels = [], [], []
    n = int(minutes * 60 // tick_seconds)
    for i in range(n):
        result = engine.tick(start + timedelta(seconds=i * tick_seconds))
        metrics.extend(result.metrics)
        security.extend(result.security)
        labels.extend(result.labels)
    labels.extend(engine.flush(start + timedelta(seconds=n * tick_seconds)))
    return metrics, security, labels


@pytest.fixture
def make_engine():
    def _make(mode="continuous", seed=1234, services=None, plan=None):
        return SimulationEngine(seed, mode, T0, services or SERVICES, f"test-{mode}-s{seed}",
                                schedule_path=SCHEDULE, plan=plan)
    return _make
