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
