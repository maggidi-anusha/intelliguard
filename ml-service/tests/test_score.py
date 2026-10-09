from conftest import normal_window


def post(client, samples, service_id=4):
    return client.post("/score", json={"serviceId": service_id, "samples": samples})


def test_health(client):
    assert client.get("/health").json() == {"status": "ok"}


def test_normal_window_is_not_flagged(client):
    body = post(client, normal_window()).json()

    assert body["detector"] == "L1"
    assert body["insufficientData"] is False
    assert body["isAnomalous"] is False
    assert all(not m["isAnomalous"] for m in body["metrics"].values())
    assert body["baseline"]["isAnomalous"] is False
    assert 0.0 <= body["serviceScore"] < 0.5


def test_clearly_anomalous_cpu_is_flagged(client):
    samples = normal_window()
    cpu = [s for s in samples if s["metricType"] == "CPU"]
    for s in cpu[-12:]:  # last minute pinned at 97% CPU
        s["value"] = 97.0
    body = post(client, samples).json()

    assert body["isAnomalous"] is True
    assert body["metrics"]["CPU"]["isAnomalous"] is True
    assert body["metrics"]["CPU"]["detector"] == "L1"
    assert body["serviceScore"] > 0.5
    assert body["baseline"]["metrics"]["CPU"]["isAnomalous"] is True  # L0 agrees (97 > 90)
    assert body["metrics"]["MEMORY"]["isAnomalous"] is False


def test_scoring_is_deterministic(client, model_dir, tmp_path):
    samples = normal_window(seed=7)
    assert post(client, samples).json() == post(client, samples).json()

    # Training is deterministic too: a second model from the same data has identical floors.
    import joblib
    from app.train import MODEL_FILE, train_and_save
    again = joblib.load(train_and_save(str(tmp_path), seeds=[101], minutes=180))["detector"]
    first = joblib.load(f"{model_dir}/{MODEL_FILE}")["detector"]
    assert again.floors == first.floors and again.params() == first.params()


def test_short_window_reports_insufficient_data(client):
    samples = normal_window(ticks=10)
    response = post(client, samples)
    body = response.json()

    assert response.status_code == 200
    assert body["insufficientData"] is True
    assert body["serviceScore"] is None and body["isAnomalous"] is False
    assert all(m["insufficientData"] and m["score"] is None for m in body["metrics"].values())
    assert body["minSamplesPerMetric"] > 10
    assert "CPU" in body["baseline"]["metrics"]  # the threshold rule still answers


def test_single_sample_does_not_crash(client):
    body = post(client, [{"timestamp": "2026-01-01T00:00:00Z", "metricType": "LATENCY", "value": 900}]).json()
    assert body["insufficientData"] is True
    assert body["baseline"]["metrics"]["LATENCY"]["isAnomalous"] is True


def test_empty_window_is_rejected(client):
    assert post(client, []).status_code == 422


def test_unknown_metric_is_rejected(client):
    samples = [{"timestamp": "2026-01-01T00:00:00Z", "metricType": "GPU", "value": 1.0}]
    assert post(client, samples).status_code == 422


def test_missing_service_id_is_rejected(client):
    assert client.post("/score", json={"samples": normal_window(ticks=2)}).status_code == 422


def test_unseen_service_falls_back_to_metric_level_floors(client):
    assert post(client, normal_window(), service_id=999).status_code == 200
