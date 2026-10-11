from topology import CALL_GRAPH, SEED_SERVICES, register_dependencies

SERVICES = {s["name"]: i + 1 for i, s in enumerate(SEED_SERVICES)}


class FakeResponse:
    def __init__(self, status_code):
        self.status_code = status_code
        self.text = ""


class FakeBackend:
    """Stands in for BackendClient: stores edges, answers 409 for one it already has."""

    def __init__(self, fail_path=None):
        self.edges = set()
        self.posts = 0
        self.fail_path = fail_path

    def post(self, path, body):
        assert path == "/api/service-dependencies"
        self.posts += 1
        edge = (body["serviceId"], body["dependsOnServiceId"])
        if edge == self.fail_path:
            return FakeResponse(500)
        if edge in self.edges:
            return FakeResponse(409)
        self.edges.add(edge)
        return FakeResponse(201)


def expected_edges():
    return {(SERVICES[caller], SERVICES[callee]) for caller, callees in CALL_GRAPH.items() for callee in callees}


def test_all_seven_call_graph_edges_are_registered():
    backend = FakeBackend()
    assert register_dependencies(backend, SERVICES) == 7
    assert backend.edges == expected_edges() and len(backend.edges) == 7
    # Includes the three edges the legacy single dependsOnServiceId can't hold.
    assert (SERVICES["api-gateway"], SERVICES["auth-service"]) in backend.edges
    assert (SERVICES["api-gateway"], SERVICES["order-service"]) in backend.edges
    assert (SERVICES["order-service"], SERVICES["inventory-service"]) in backend.edges


def test_registration_is_idempotent_across_restarts():
    backend = FakeBackend()
    register_dependencies(backend, SERVICES)
    assert register_dependencies(backend, SERVICES) == 7  # every edge answers 409 = already there
    assert len(backend.edges) == 7 and backend.posts == 14


def test_a_failed_edge_is_skipped_not_fatal():
    backend = FakeBackend(fail_path=(SERVICES["frontend-web"], SERVICES["api-gateway"]))
    assert register_dependencies(backend, SERVICES) == 6
    assert len(backend.edges) == 6
