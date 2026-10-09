import os
import sys
from datetime import datetime, timedelta, timezone

import pytest

ML_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ML_ROOT)

from app.data import simulator_path  # noqa: E402

# Appended, not prepended: the simulator also has a main.py, and ml-service's must win.
sys.path.append(simulator_path())
from engine import SimulationEngine  # noqa: E402  (the simulator's generator)

T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


@pytest.fixture(scope="session")
def model_dir(tmp_path_factory):
    """A small model (3 simulated hours of normal data, one seed) so tests stay fast."""
    from app.train import train_and_save
    directory = str(tmp_path_factory.mktemp("models"))
    train_and_save(directory, seeds=[101], minutes=180)
    return directory


@pytest.fixture(scope="session")
def client(model_dir):
    os.environ["MODEL_DIR"] = model_dir
    from fastapi.testclient import TestClient
    import main
    main.get_scorer.cache_clear()
    return TestClient(main.app)


def normal_window(service="auth-service", service_id=4, ticks=90, seed=999):
    """Samples for one service from the simulator in normal mode (no anomalies)."""
    engine = SimulationEngine(seed, "normal", T0, {service: service_id}, "test-window")
    samples = []
    for i in range(ticks):
        for row in engine.tick(T0 + timedelta(seconds=5 * i)).metrics:
            samples.append({"timestamp": row.timestamp.isoformat(), "metricType": row.metric_type, "value": row.value})
    return samples
