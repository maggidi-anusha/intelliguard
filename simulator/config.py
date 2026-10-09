import os

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")

# The ADMIN account is seeded by backend-core at startup from the same env config
# (AdminAccountSeeder) - public registration can't create ADMIN accounts, so the
# simulator only logs in with these credentials.
ADMIN_USERNAME = os.environ.get("SIMULATOR_ADMIN_USERNAME", "simulator-admin")
ADMIN_PASSWORD = os.environ.get("SIMULATOR_ADMIN_PASSWORD", "")

TICK_SECONDS = float(os.environ.get("TICK_SECONDS", "5"))
OTLP_ENDPOINT = os.environ.get("OTLP_ENDPOINT", "localhost:4317")
SCENARIO_FILE = os.environ.get("SCENARIO_FILE", "scenarios/anomaly_schedule.json")

# One seed drives every random draw (values, baselines, episode plan, logs, security events,
# trace timings). Unset or blank -> a fresh seed is generated, logged, and stored in every label.
SEED = os.environ.get("SIMULATOR_SEED", "")

# demo (default): the fixed anomaly_schedule.json, as in Phase 2/3.
# continuous: randomized episodes repeating for the whole run. normal: no anomalies at all.
SCENARIO_MODE = os.environ.get("SCENARIO_MODE", "").strip().lower() or "demo"
