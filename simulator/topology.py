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
# unambiguous 1:1 edges are captured here; the full call graph (CALL_GRAPH below) is
# registered separately in backend-core's service_dependencies (Phase 5.3) by
# register_dependencies, and is also what Jaeger's service map shows from the trace spans.
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

# The full call graph (service -> services it calls/depends on), as exercised by the trace call
# chain in tracing.py. It has 7 edges; the backend's single dependsOnServiceId can only hold the 4
# unambiguous ones above (api-gateway -> auth-service/order-service and order-service ->
# inventory-service are lost). Cascade scenarios propagate along this graph, towards callers.
CALL_GRAPH = {
    "frontend-web": ["api-gateway"],
    "api-gateway": ["auth-service", "order-service"],
    "auth-service": ["user-db"],
    "order-service": ["order-db", "inventory-service"],
    "inventory-service": ["inventory-db"],
}


def callers_of(graph=None):
    """Inverse of the call graph: service -> services that call (depend on) it."""
    callers = {}
    for caller, callees in (graph or CALL_GRAPH).items():
        for callee in callees:
            callers.setdefault(callee, []).append(caller)
    return {k: sorted(v) for k, v in callers.items()}


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


def register_dependencies(client, services):
    """Idempotently registers every CALL_GRAPH edge in backend-core's service_dependencies.

    Runs after register_services on every start; 409 (edge already exists) counts as success. A
    failed edge is logged and skipped - telemetry generation must not stop over it.
    Returns the number of edges that are registered (new or already present)."""
    ok = 0
    for caller, callees in sorted(CALL_GRAPH.items()):
        for callee in callees:
            if caller not in services or callee not in services:
                log.warning("Dependency %s -> %s skipped: service not registered", caller, callee)
                continue
            body = {"serviceId": services[caller], "dependsOnServiceId": services[callee]}
            try:
                resp = client.post("/api/service-dependencies", body)
            except Exception as exc:  # network errors: log and continue
                log.warning("Dependency %s -> %s failed: %s", caller, callee, exc)
                continue
            if resp.status_code in (201, 409):
                ok += 1
                log.info("Dependency %s -> %s %s", caller, callee,
                         "registered" if resp.status_code == 201 else "already registered")
            else:
                log.warning("Dependency %s -> %s failed: %s %s", caller, callee, resp.status_code, resp.text)
    return ok
