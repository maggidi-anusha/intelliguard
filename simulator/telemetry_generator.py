import json
import random

# (mean, std, min_clamp, max_clamp) for baseline (no active anomaly) generation.
METRIC_BASELINES = {
    "CPU": (25.0, 4.0, 1.0, 60.0),
    "MEMORY": (45.0, 6.0, 5.0, 70.0),
    "DISK": (35.0, 3.0, 5.0, 70.0),
    "NETWORK": (15.0, 4.0, 0.5, 60.0),
    "LATENCY": (70.0, 12.0, 5.0, 150.0),
    "ERROR_RATE": (0.4, 0.3, 0.0, 2.0),
}

# During an active anomaly, the value is drawn from a clearly separated range instead of
# a multiplier - baselines vary too much in scale (0.4% error rate vs 70ms latency) for one
# multiplier to make sense everywhere, and a distinct range is what makes the ground-truth
# labels actually separable later for the Phase 7 precision/recall evaluation.
ANOMALY_TARGET_RANGES = {
    "CPU": {"low": (60, 75), "medium": (75, 90), "high": (90, 99)},
    "MEMORY": {"low": (65, 78), "medium": (78, 90), "high": (90, 98)},
    "DISK": {"low": (55, 68), "medium": (68, 82), "high": (82, 96)},
    "NETWORK": {"low": (40, 80), "medium": (80, 150), "high": (150, 300)},
    "LATENCY": {"low": (200, 400), "medium": (400, 900), "high": (900, 2000)},
    "ERROR_RATE": {"low": (3, 8), "medium": (8, 20), "high": (20, 45)},
}

ANOMALY_METRIC_MAP = {
    "cpu_spike": "CPU",
    "memory_spike": "MEMORY",
    "disk_spike": "DISK",
    "network_spike": "NETWORK",
    "latency_spike": "LATENCY",
    "error_burst": "ERROR_RATE",
}

ANOMALY_LOG_TEMPLATES = {
    "cpu_spike": ("WARN", "High CPU utilization detected"),
    "memory_spike": ("WARN", "Memory usage approaching limit"),
    "disk_spike": ("WARN", "Disk utilization critical"),
    "network_spike": ("WARN", "Unusual network throughput detected"),
    "latency_spike": ("WARN", "Elevated response latency observed"),
    "error_burst": ("ERROR", "Request failures spiking"),
}

BASELINE_LOG_MESSAGES = [
    "heartbeat ok",
    "request processed",
    "cache hit",
    "scheduled job completed",
]


def load_schedule(path):
    with open(path) as f:
        data = json.load(f)
    return {
        "metric_anomalies": data.get("metric_anomalies", []),
        "security_events": data.get("security_events", []),
    }


def _active_entries(entries, elapsed_minutes, service_name):
    return [
        e
        for e in entries
        if e["service"] == service_name
        and e["start_minute"] <= elapsed_minutes < e["start_minute"] + e["duration_minutes"]
    ]


def active_metric_anomalies(schedule, elapsed_minutes, service_name):
    return _active_entries(schedule["metric_anomalies"], elapsed_minutes, service_name)


# Small, deterministic per-service jitter so services don't all look identical, without
# needing to persist any state between ticks.
def _service_jitter(service_name):
    rng = random.Random(service_name)
    return rng.uniform(0.85, 1.15)


def generate_metrics_for_service(service_name, elapsed_minutes, schedule):
    active = {ANOMALY_METRIC_MAP[a["anomaly_type"]]: a for a in active_metric_anomalies(schedule, elapsed_minutes, service_name)}
    jitter = _service_jitter(service_name)

    values = {}
    for metric_type, (mean, std, lo, hi) in METRIC_BASELINES.items():
        anomaly = active.get(metric_type)
        if anomaly:
            low, high = ANOMALY_TARGET_RANGES[metric_type][anomaly["magnitude"]]
            values[metric_type] = round(random.uniform(low, high), 2)
        else:
            value = random.gauss(mean * jitter, std)
            values[metric_type] = round(max(lo, min(hi, value)), 2)

    return values


def generate_logs_for_service(service_name, elapsed_minutes, schedule):
    logs = []
    active = active_metric_anomalies(schedule, elapsed_minutes, service_name)

    for anomaly in active:
        if random.random() < 0.7:
            level, message = ANOMALY_LOG_TEMPLATES[anomaly["anomaly_type"]]
            logs.append((level, message))

    if not active and random.random() < 0.3:
        logs.append(("INFO", random.choice(BASELINE_LOG_MESSAGES)))

    return logs
