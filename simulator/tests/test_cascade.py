import hashlib
from collections import defaultdict
from datetime import timedelta

from conftest import SERVICES, run_engine
from offline import generate
from test_labels import _assert_labels_match_injected_rows
from topology import callers_of

NAMES = {v: k for k, v in SERVICES.items()}
FILES = ("metrics.csv", "logs.csv", "security_events.csv", "labels.csv")


def _transitive_callers(service):
    callers, out, frontier = callers_of(), set(), [service]
    while frontier:
        for c in callers.get(frontier.pop(), []):
            if c not in out:
                out.add(c)
                frontier.append(c)
    return out


def _groups(labels):
    groups = defaultdict(list)
    for label in labels:
        if label["signal_type"] == "METRIC":
            groups[label["params"]["groupId"]].append(label)
    return groups


def test_cascade_output_is_deterministic_by_seed(tmp_path):
    for name, seed in (("a", 11), ("b", 11), ("c", 12)):
        generate(str(tmp_path / name), seed, "cascade", 6 * 60)
    digest = lambda d: [hashlib.sha256((tmp_path / d / f).read_bytes()).hexdigest() for f in FILES]  # noqa: E731
    assert digest("a") == digest("b")
    assert digest("a") != digest("c")


def test_cascade_labels_match_exactly_the_injected_rows(make_engine):
    metrics, security, labels = run_engine(make_engine(mode="cascade", seed=21), 24 * 60)
    assert len(_groups(labels)) > 50
    _assert_labels_match_injected_rows(metrics, security, labels)


def test_cascade_groups_are_well_formed(make_engine):
    _, _, labels = run_engine(make_engine(mode="cascade", seed=21), 24 * 60)
    cascades = {g: ls for g, ls in _groups(labels).items() if any(l["params"]["role"] == "ROOT" for l in ls)}
    types = set()

    for group, members in cascades.items():
        [root] = [l for l in members if l["params"]["role"] == "ROOT"]
        root_service = NAMES[root["service_id"]]
        types.add(root["params"]["cascadeType"])
        assert all(l["params"]["rootServiceId"] == root["service_id"] for l in members)
        assert {l["params"]["role"] for l in members} <= {"ROOT", "SYMPTOM"}
        symptoms = [l for l in members if l["params"]["role"] == "SYMPTOM"]
        assert len({l["service_id"] for l in symptoms}) == len(symptoms)  # each service at most once
        for s in symptoms:
            # symptoms only on services that (transitively) call the root, with a delayed onset
            assert NAMES[s["service_id"]] in _transitive_callers(root_service), (group, s)
            assert s["start_time"] >= root["start_time"] + timedelta(seconds=10)

    assert types == {"EASY", "HARD"}
    assert any(sum(l["params"]["role"] == "SYMPTOM" for l in m) >= 2 for m in cascades.values())


def test_distractors_are_never_part_of_a_cascade_group(make_engine):
    _, _, labels = run_engine(make_engine(mode="cascade", seed=21), 24 * 60)
    groups = _groups(labels)
    cascade_groups = {g for g, ls in groups.items() if any(l["params"]["role"] == "ROOT" for l in ls)}
    distractors = [l for l in labels if l["signal_type"] == "METRIC" and l["params"]["role"] == "DISTRACTOR"]
    assert distractors

    for d in distractors:
        group = d["params"]["groupId"]
        assert group not in cascade_groups
        assert len(groups[group]) == 1  # a distractor is its own single-member group
        assert d["params"]["rootServiceId"] == d["service_id"]
        cascade = groups[group.rsplit("-dis-", 1)[0]]
        assert d["service_id"] not in {l["service_id"] for l in cascade}  # an unrelated service
        root_start = next(l["start_time"] for l in cascade if l["params"]["role"] == "ROOT")
        assert root_start - timedelta(seconds=60) <= d["start_time"] <= root_start + timedelta(seconds=180)


def test_cascade_keys_never_appear_in_other_modes(make_engine):
    _, _, labels = run_engine(make_engine(mode="continuous", seed=21), 6 * 60)
    assert labels and not any({"groupId", "role", "rootServiceId"} & set(l["params"]) for l in labels)
