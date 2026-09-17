import random
import time

from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import Status, StatusCode

from telemetry_generator import active_metric_anomalies

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


def _span_duration_seconds(service_name, elapsed_minutes, schedule, baseline=0.02):
    """Real (short) sleep so span durations look realistic - stretched during a latency_spike
    or error_burst anomaly on that service, capped so one tick never runs long."""
    anomalies = {a["anomaly_type"] for a in active_metric_anomalies(schedule, elapsed_minutes, service_name)}
    if "latency_spike" in anomalies:
        return min(random.uniform(0.15, 0.35), 0.35)
    if "error_burst" in anomalies:
        return min(random.uniform(0.08, 0.2), 0.2)
    return random.uniform(baseline, baseline * 3)


def _traced_call(tracers, service_name, span_name, elapsed_minutes, schedule, on_span=None):
    anomalies = {a["anomaly_type"] for a in active_metric_anomalies(schedule, elapsed_minutes, service_name)}

    with tracers[service_name].start_as_current_span(span_name) as span:
        time.sleep(_span_duration_seconds(service_name, elapsed_minutes, schedule))

        if "error_burst" in anomalies:
            span.set_status(Status(StatusCode.ERROR, "downstream error burst"))
            span.record_exception(RuntimeError(f"{service_name} error burst"))

        if on_span:
            on_span()


def simulate_call_chain(tracers, elapsed_minutes, schedule):
    """One synthetic request per tick through the full topology:
    frontend-web -> api-gateway -> {auth-service -> user-db,
                                     order-service -> {order-db,
                                                        inventory-service -> inventory-db}}
    """

    def call_order_service():
        _traced_call(tracers, "order-db", "order-db.insert_order", elapsed_minutes, schedule)
        _traced_call(
            tracers, "inventory-service", "inventory-service.check_stock", elapsed_minutes, schedule,
            on_span=lambda: _traced_call(
                tracers, "inventory-db", "inventory-db.query_stock", elapsed_minutes, schedule
            ),
        )

    with tracers["frontend-web"].start_as_current_span("frontend-web.handle_request"):
        time.sleep(_span_duration_seconds("frontend-web", elapsed_minutes, schedule))

        with tracers["api-gateway"].start_as_current_span("api-gateway.route_request"):
            time.sleep(_span_duration_seconds("api-gateway", elapsed_minutes, schedule))

            _traced_call(
                tracers, "auth-service", "auth-service.authenticate", elapsed_minutes, schedule,
                on_span=lambda: _traced_call(
                    tracers, "user-db", "user-db.query_user", elapsed_minutes, schedule
                ),
            )

            _traced_call(
                tracers, "order-service", "order-service.create_order", elapsed_minutes, schedule,
                on_span=call_order_service,
            )
