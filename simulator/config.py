import os

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")

# The simulator provisions its own ADMIN account on first run (register, ignore 409
# on subsequent runs) rather than requiring one to be created out-of-band.
ADMIN_USERNAME = os.environ.get("SIMULATOR_ADMIN_USERNAME", "simulator-admin")
ADMIN_PASSWORD = os.environ.get("SIMULATOR_ADMIN_PASSWORD", "")
ADMIN_EMAIL = os.environ.get("SIMULATOR_ADMIN_EMAIL", "simulator-admin@intelliguard.local")

TICK_SECONDS = float(os.environ.get("TICK_SECONDS", "5"))
OTLP_ENDPOINT = os.environ.get("OTLP_ENDPOINT", "localhost:4317")
SCENARIO_FILE = os.environ.get("SCENARIO_FILE", "scenarios/anomaly_schedule.json")
