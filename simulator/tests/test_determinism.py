import hashlib

from conftest import SERVICES, run_engine
from offline import generate

FILES = ("metrics.csv", "logs.csv", "security_events.csv", "labels.csv")


def _hashes(directory):
    return {name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in FILES}


def test_same_seed_gives_byte_identical_output(tmp_path):
    generate(str(tmp_path / "a"), 7, "continuous", 6 * 60)
    generate(str(tmp_path / "b"), 7, "continuous", 6 * 60)

    assert _hashes(tmp_path / "a") == _hashes(tmp_path / "b")


def test_different_seed_gives_different_output(tmp_path):
    generate(str(tmp_path / "a"), 7, "continuous", 6 * 60)
    generate(str(tmp_path / "b"), 8, "continuous", 6 * 60)

    a, b = _hashes(tmp_path / "a"), _hashes(tmp_path / "b")
    assert a["metrics.csv"] != b["metrics.csv"]
    assert a["labels.csv"] != b["labels.csv"]


def test_baselines_depend_on_seed_not_just_service_name(make_engine):
    # Previously the per-service jitter was seeded by service name alone.
    assert make_engine(seed=1).jitter("auth-service") != make_engine(seed=2).jitter("auth-service")
    assert make_engine(seed=1).jitter("auth-service") == make_engine(seed=1).jitter("auth-service")


def test_extra_registered_service_does_not_shift_other_services_values(make_engine):
    # The live simulator also feeds services it didn't create; each service has its own random
    # stream, so their presence must not change the topology's values.
    base, _, _ = run_engine(make_engine(seed=5), 60)
    extra, _, _ = run_engine(make_engine(seed=5, services={**SERVICES, "someone-elses-service": 99}), 60)

    key = lambda r: (r.timestamp, r.service_name, r.metric_type, r.value)  # noqa: E731
    assert [key(r) for r in base] == [key(r) for r in extra if r.service_name != "someone-elses-service"]
