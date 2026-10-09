import random

# (mean, std, min_clamp, max_clamp) for baseline (no active anomaly) generation. The clamp
# range is the hard support of normal data: a normal value can never fall outside it.
METRIC_BASELINES = {
    "CPU": (25.0, 4.0, 1.0, 60.0),
    "MEMORY": (45.0, 6.0, 5.0, 70.0),
    "DISK": (35.0, 3.0, 5.0, 70.0),
    "NETWORK": (15.0, 4.0, 0.5, 60.0),
    "LATENCY": (70.0, 12.0, 5.0, 150.0),
    "ERROR_RATE": (0.4, 0.3, 0.0, 2.0),
}

# Fixed generation order - part of the determinism contract (same seed -> same draws).
METRIC_TYPES = list(METRIC_BASELINES)

# Legacy (Phase 2) anomaly ranges, still used verbatim by SCENARIO_MODE=demo so the existing
# anomaly_schedule.json demo behaves exactly as before. During an active anomaly the value is
# drawn from a clearly separated range instead of a multiplier - baselines vary too much in
# scale (0.4% error rate vs 70ms latency) for one multiplier to make sense everywhere.
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

# Same messages, keyed by the metric an episode affects (scenario episodes aren't named
# after a legacy anomaly_type).
METRIC_LOG_TEMPLATES = {ANOMALY_METRIC_MAP[k]: v for k, v in ANOMALY_LOG_TEMPLATES.items()}

BASELINE_LOG_MESSAGES = [
    "heartbeat ok",
    "request processed",
    "cache hit",
    "scheduled job completed",
]

# Per-tick probability of an anomaly log line while an episode is active on a service. SUBTLE
# episodes log rarely, so the log stream doesn't give away an injection the metrics hide.
ANOMALY_LOG_PROBABILITY = {"EASY": 0.7, "SUBTLE": 0.2}
BASELINE_LOG_PROBABILITY = 0.3


def rng_stream(seed, *parts):
    """An independent, reproducible random stream for one purpose (e.g. one service's values).

    Separate streams mean that, for example, adding a service or a security event never shifts
    the values drawn for anything else. Seeding random.Random with a str is deterministic across
    processes (it hashes with SHA-512, unaffected by PYTHONHASHSEED)."""
    return random.Random(":".join([str(seed)] + [str(p) for p in parts]))


def service_jitter(seed, service_name):
    """Small per-service baseline offset so services don't all look identical. Derived from the
    run seed (not just the service name), so a different seed gives different baselines."""
    return rng_stream(seed, "jitter", service_name).uniform(0.85, 1.15)


def baseline_stats(metric_type, jitter):
    """(mean, std, lo, hi) of normal data for one service - the reference every scenario is
    defined against."""
    mean, std, lo, hi = METRIC_BASELINES[metric_type]
    return mean * jitter, std, lo, hi


def normal_value(rng, metric_type, jitter):
    mean, std, lo, hi = baseline_stats(metric_type, jitter)
    return round(max(lo, min(hi, rng.gauss(mean, std))), 2)


def generate_logs(rng, active_episodes):
    """Log lines for one service on one tick. `active_episodes` are the episodes currently
    injecting into this service (possibly none)."""
    logs = []
    for episode in active_episodes:
        if rng.random() < ANOMALY_LOG_PROBABILITY[episode.difficulty]:
            logs.append(METRIC_LOG_TEMPLATES[episode.primary_metric])

    # EASY episodes (including every demo episode) suppress routine INFO lines, as the
    # original simulator did; SUBTLE ones keep them so the log stream doesn't give them away.
    if not any(e.difficulty == "EASY" for e in active_episodes) and rng.random() < BASELINE_LOG_PROBABILITY:
        logs.append(("INFO", rng.choice(BASELINE_LOG_MESSAGES)))

    return logs
