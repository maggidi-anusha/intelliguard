import random

# Explicit parameters so these tests check the method, not the tuned values in the config file.
PARAMS = {"gap_seconds": 60, "max_hops": 1,
          "weights": {"onset": 0.35, "dependents": 0.3, "peak": 0.15, "security": 0.1, "criticality": 0.1}}
# Same 4 edges the backend stores (service -> service it depends on).
BACKEND_EDGES = [{"service": 4, "dependsOn": 1}, {"service": 5, "dependsOn": 3},
                 {"service": 6, "dependsOn": 2}, {"service": 8, "dependsOn": 7}]


def ep(id_, service, start, end="2026-01-01T00:05:00Z", peak=0.8, metric="LATENCY", detector="L1"):
    return {"id": id_, "serviceId": service, "metricType": metric, "detector": detector,
            "start": start, "end": end, "peakScore": peak}


def correlate(client, episodes, **extra):
    body = {"episodes": episodes, "dependencies": BACKEND_EDGES, "params": PARAMS, **extra}
    response = client.post("/correlate", json=body)
    assert response.status_code == 200, response.text
    return response.json()


def test_single_anomaly_is_one_incident_with_full_confidence(client):
    body = correlate(client, [ep("a", 2, "2026-01-01T00:00:00Z")])

    assert "not a learned model" in body["method"]
    [incident] = body["incidents"]
    assert incident["services"] == [2]
    [candidate] = incident["rootCause"]["candidates"]
    assert candidate["serviceId"] == 2 and candidate["rank"] == 1
    assert incident["rootCause"]["confidence"] == 1.0


def test_two_linked_services_form_one_incident_with_the_dependency_as_root(client):
    body = correlate(client, [ep("db", 2, "2026-01-01T00:00:00Z", peak=0.7),
                              ep("svc", 6, "2026-01-01T00:00:30Z", peak=0.7)])

    [incident] = body["incidents"]
    assert incident["services"] == [2, 6]
    ranked = incident["rootCause"]["candidates"]
    assert [c["serviceId"] for c in ranked] == [2, 6]
    assert any("1 other anomalous service(s) depend on it: service 6" in e for e in ranked[0]["evidence"])
    assert 0 < incident["rootCause"]["confidence"] <= 1


def test_a_simultaneous_anomaly_on_an_unconnected_service_stays_separate(client):
    body = correlate(client, [ep("db", 2, "2026-01-01T00:00:00Z"),
                              ep("svc", 6, "2026-01-01T00:00:30Z"),
                              ep("distractor", 1, "2026-01-01T00:00:10Z")])  # user-db: no path to order-*

    assert sorted(i["services"] for i in body["incidents"]) == [[1], [2, 6]]


def test_root_outranks_a_more_visible_later_symptom(client):
    body = correlate(client, [ep("root", 2, "2026-01-01T00:00:00Z", peak=0.55),
                              ep("symptom", 6, "2026-01-01T00:00:40Z", peak=0.99)],
                     criticality={"2": "CRITICAL", "6": "HIGH"})

    [incident] = body["incidents"]
    assert incident["rootCause"]["candidates"][0]["serviceId"] == 2


def test_ties_are_broken_deterministically_and_reported(client):
    mutual = [{"service": 6, "dependsOn": 7}, {"service": 7, "dependsOn": 6}]
    body = client.post("/correlate", json={
        "episodes": [ep("x", 7, "2026-01-01T00:00:00Z"), ep("y", 6, "2026-01-01T00:00:00Z")],
        "dependencies": mutual, "params": PARAMS}).json()

    [incident] = body["incidents"]
    ranked = incident["rootCause"]["candidates"]
    assert ranked[0]["score"] == ranked[1]["score"]
    assert [c["serviceId"] for c in ranked] == [6, 7]  # tie -> earlier onset, then lower serviceId
    assert incident["rootCause"]["confidence"] == 0.0
    assert any("tied with service 7" in e for e in ranked[0]["evidence"])


def test_missing_end_time_counts_as_still_active(client):
    # The root has no end yet; a symptom 5 minutes later still joins it (active until asOf).
    body = correlate(client, [ep("root", 2, "2026-01-01T00:00:00Z", end=None),
                              ep("symptom", 6, "2026-01-01T00:05:00Z", end="2026-01-01T00:06:00Z")])

    [incident] = body["incidents"]
    assert incident["services"] == [2, 6]
    assert incident["openEpisodes"] == 1
    assert body["asOf"] == "2026-01-01T00:06:00Z" and incident["end"] == "2026-01-01T00:06:00Z"


def test_output_is_deterministic_and_independent_of_input_order(client):
    episodes = [ep(f"e{i}", s, f"2026-01-01T00:0{i}:00Z") for i, s in enumerate([2, 6, 1, 4, 8, 7])]
    first = correlate(client, episodes)
    shuffled = episodes[:]
    random.Random(3).shuffle(shuffled)
    assert correlate(client, shuffled) == first == correlate(client, episodes)


def test_empty_input_returns_no_incidents(client):
    body = correlate(client, [])
    assert body["incidents"] == [] and body["asOf"] is None


def test_default_profile_comes_from_the_config_file(client):
    body = client.post("/correlate", json={"episodes": [ep("a", 2, "2026-01-01T00:00:00Z")]}).json()
    assert body["profile"] in ("full_call_graph", "backend_4_edges")
    assert client.post("/correlate", json={"episodes": [], "profile": "no-such-profile"}).status_code == 422


def test_bad_input_is_rejected(client):
    bad_bodies = [
        {"episodes": [ep("a", 2, "2026-01-01T00:05:00Z", end="2026-01-01T00:00:00Z")]},  # end before start
        {"episodes": [ep("a", 2, "2026-01-01T00:00:00Z", peak=-1)]},                    # negative score
        {"episodes": [{"id": "a", "start": "2026-01-01T00:00:00Z", "peakScore": 1}]},     # no serviceId
        {"episodes": [ep("a", 2, "2026-01-01T00:00:00Z", metric="GPU")]},                # unknown metric
        {"episodes": [ep("a", 2, "2026-01-01T00:00:00Z"), ep("a", 6, "2026-01-01T00:00:00Z")]},  # duplicate id
        {"episodes": [], "criticality": {"2": "SEVERE"}},
        {"episodes": [], "securityEvents": {"2": -3}},
        {"episodes": [], "params": {**PARAMS, "weights": {**PARAMS["weights"], "onset": 0.9}}},  # weights != 1
        {"episodes": "not-a-list"},
    ]
    for body in bad_bodies:
        assert client.post("/correlate", json=body).status_code == 422, body
