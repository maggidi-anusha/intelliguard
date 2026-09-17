import logging

log = logging.getLogger("simulator.topology")

# Order matters: a service's "depends_on" must already exist (and be registered) before
# it, since dependsOnServiceId needs a real id. Databases first, then the services that
# depend on exactly one of them, then the fan-out services.
#
# The schema's dependsOnServiceId is a single self-referential FK (Phase 1, manual, used
# by Phase 5). order-service and api-gateway genuinely have more than one downstream
# dependency (order-service also calls inventory-service; api-gateway fans out to both
# auth-service and order-service) - that can't be expressed as one FK. Only the
# unambiguous 1:1 edges are captured here; the full call graph, including the ones this
# field can't hold, is what Jaeger's service map shows from the trace spans instead.
SEED_SERVICES = [
    {"name": "user-db", "type": "DATABASE", "criticality": "CRITICAL", "depends_on": None},
    {"name": "order-db", "type": "DATABASE", "criticality": "CRITICAL", "depends_on": None},
    {"name": "inventory-db", "type": "DATABASE", "criticality": "HIGH", "depends_on": None},
    {"name": "auth-service", "type": "MICROSERVICE", "criticality": "HIGH", "depends_on": "user-db"},
    {"name": "inventory-service", "type": "MICROSERVICE", "criticality": "MEDIUM", "depends_on": "inventory-db"},
    {"name": "order-service", "type": "MICROSERVICE", "criticality": "HIGH", "depends_on": "order-db"},
    {"name": "api-gateway", "type": "MICROSERVICE", "criticality": "HIGH", "depends_on": None},
    {"name": "frontend-web", "type": "MICROSERVICE", "criticality": "MEDIUM", "depends_on": "api-gateway"},
]


def _fetch_existing(client):
    resp = client.get("/api/services")
    resp.raise_for_status()
    return {s["name"]: s["id"] for s in resp.json()}


def register_services(client):
    """Idempotently ensures the seed topology exists in backend-core, returning {name: id}."""
    existing = _fetch_existing(client)

    for entry in SEED_SERVICES:
        name = entry["name"]
        if name in existing:
            log.info("Service '%s' already registered (id=%s)", name, existing[name])
            continue

        depends_on_name = entry["depends_on"]
        body = {
            "name": name,
            "type": entry["type"],
            "criticality": entry["criticality"],
            "hostname": None,
            "dependsOnServiceId": existing.get(depends_on_name) if depends_on_name else None,
        }

        resp = client.post("/api/services", body)
        if resp.status_code == 201:
            existing[name] = resp.json()["id"]
            log.info("Registered service '%s' -> id %s", name, existing[name])
        elif resp.status_code == 409:
            existing = _fetch_existing(client)
        else:
            resp.raise_for_status()

    return existing
