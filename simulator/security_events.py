import random

FAILED_LOGIN_USERNAMES = ["jdoe", "asmith", "root", "admin", "svc-account"]
SCANNED_PORTS = [22, 23, 3389, 5432, 8080, 445]


def active_security_events(schedule, elapsed_minutes):
    return [
        e
        for e in schedule["security_events"]
        if e["start_minute"] <= elapsed_minutes < e["start_minute"] + e["duration_minutes"]
    ]


def build_event_payload(entry, service_id):
    if entry["event_type"] == "FAILED_LOGIN":
        details = {
            "username": random.choice(FAILED_LOGIN_USERNAMES),
            "source_ip": f"203.0.113.{random.randint(1, 254)}",
        }
    elif entry["event_type"] == "PORT_SCAN":
        details = {
            "source_ip": f"198.51.100.{random.randint(1, 254)}",
            "port": random.choice(SCANNED_PORTS),
        }
    else:
        details = {}

    return {
        "serviceId": service_id,
        "eventType": entry["event_type"],
        "severity": entry["severity"],
        "details": details,
    }
