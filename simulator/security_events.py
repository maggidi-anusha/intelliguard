FAILED_LOGIN_USERNAMES = ["jdoe", "asmith", "root", "admin", "svc-account"]
SCANNED_PORTS = [22, 23, 3389, 5432, 8080, 445]


def build_event_details(rng, event_type):
    """Payload details for one security event. `rng` is the run's seeded security stream, so
    the same seed reproduces the same usernames, IPs and ports."""
    if event_type == "FAILED_LOGIN":
        return {
            "username": rng.choice(FAILED_LOGIN_USERNAMES),
            "source_ip": f"203.0.113.{rng.randint(1, 254)}",
        }
    if event_type == "PORT_SCAN":
        return {
            "source_ip": f"198.51.100.{rng.randint(1, 254)}",
            "port": rng.choice(SCANNED_PORTS),
        }
    return {}
