import time

from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import Status, StatusCode

TRACE_SERVICE_NAMES = [
    "frontend-web",
    "api-gateway",
    "auth-service",
    "order-service",
    "inventory-service",
    "user-db",
    "order-db",
    "inventory-db",
]


def build_tracers(otlp_endpoint):
    """One TracerProvider per simulated service, each with its own Resource(service.name=...),
    so Jaeger's service map shows the real topology instead of one blob."""
    tracers = {}
    for name in TRACE_SERVICE_NAMES:
        resource = Resource(attributes={"service.name": name})
        provider = TracerProvider(resource=resource)
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=otlp_endpoint, insecure=True)))
        tracers[name] = provider.get_tracer(name)
    return tracers


def _span_duration_seconds(engine, service_name, ts, baseline=0.02):
    """Real (short) sleep so span durations look realistic - stretched while a LATENCY or
    ERROR_RATE injection is active on that service, capped so one tick never runs long.
    Durations come from the engine's seeded tracing stream."""
    rng = engine.trace_rng
    active = engine.active_metric_types(service_name, ts)
    if "LATENCY" in active:
        return min(rng.uniform(0.15, 0.35), 0.35)
    if "ERROR_RATE" in active:
        return min(rng.uniform(0.08, 0.2), 0.2)
    return rng.uniform(baseline, baseline * 3)


def _traced_call(tracers, engine, ts, service_name, span_name, on_span=None):
    with tracers[service_name].start_as_current_span(span_name) as span:
        time.sleep(_span_duration_seconds(engine, service_name, ts))

        if "ERROR_RATE" in engine.active_metric_types(service_name, ts):
            span.set_status(Status(StatusCode.ERROR, "downstream error burst"))
            span.record_exception(RuntimeError(f"{service_name} error burst"))

        if on_span:
            on_span()


def simulate_call_chain(tracers, engine, ts):
    """One synthetic request per tick through the full topology:
    frontend-web -> api-gateway -> {auth-service -> user-db,
                                     order-service -> {order-db,
                                                        inventory-service -> inventory-db}}
    """

    def call_order_service():
        _traced_call(tracers, engine, ts, "order-db", "order-db.insert_order")
        _traced_call(
            tracers, engine, ts, "inventory-service", "inventory-service.check_stock",
            on_span=lambda: _traced_call(tracers, engine, ts, "inventory-db", "inventory-db.query_stock"),
        )

    with tracers["frontend-web"].start_as_current_span("frontend-web.handle_request"):
        time.sleep(_span_duration_seconds(engine, "frontend-web", ts))

        with tracers["api-gateway"].start_as_current_span("api-gateway.route_request"):
            time.sleep(_span_duration_seconds(engine, "api-gateway", ts))

            _traced_call(
                tracers, engine, ts, "auth-service", "auth-service.authenticate",
                on_span=lambda: _traced_call(tracers, engine, ts, "user-db", "user-db.query_user"),
            )

            _traced_call(
                tracers, engine, ts, "order-service", "order-service.create_order",
                on_span=call_order_service,
            )
