"""OpenTelemetry tracing: executions, model calls, governance decisions and tool runs as spans.

Off unless an OTLP endpoint is configured with the standard variables (OTEL_EXPORTER_OTLP_ENDPOINT
or OTEL_EXPORTER_OTLP_TRACES_ENDPOINT, plus OTEL_EXPORTER_OTLP_HEADERS for auth), so it works with
any OTLP backend: Langfuse, Jaeger, Grafana Tempo, an OpenTelemetry Collector.

An execution and its resumes after approvals are separate traces in the same session (the
execution id). Prompts and answers are only exported with OTEL_CAPTURE_CONTENT=true.
"""

import json
import logging
import os
from typing import Any

from langgraph.config import get_config
from opentelemetry import trace
from opentelemetry.trace import Span, Tracer

log = logging.getLogger(__name__)

SERVICE_NAME = "agentgate-runtime"
MAX_CONTENT_CHARS = 4000

_capture_content = False


def setup_tracing(capture_content: bool = False) -> bool:
    """Exports spans when an OTLP endpoint is configured; returns whether tracing is on."""
    global _capture_content
    _capture_content = capture_content
    endpoint = os.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") or os.getenv(
        "OTEL_EXPORTER_OTLP_ENDPOINT"
    )
    if not endpoint:
        return False
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor

    resource = Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", SERVICE_NAME)})
    provider = TracerProvider(resource=resource)
    # The exporter reads the endpoint, headers and timeouts from the OTEL_* variables itself.
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(provider)
    log.info("Exporting traces to %s", endpoint)
    return True


def tracer() -> Tracer:
    return trace.get_tracer("agentgate.runtime")


def capture_content() -> bool:
    return _capture_content


def set_content(span: Span, key: str, value: Any) -> None:
    """Prompt or answer text on a span, only when content capture is on."""
    if not _capture_content or value is None:
        return
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, default=str)
    span.set_attribute(key, text[:MAX_CONTENT_CHARS])


def set_attributes(span: Span, attributes: dict[str, Any]) -> None:
    """Sets the attributes that have a value (OpenTelemetry rejects None)."""
    for key, value in attributes.items():
        if value is not None:
            span.set_attribute(key, value)


def current_step() -> str | None:
    """The graph step running this code (e.g. "helper.think"), when inside one."""
    try:
        return get_config().get("metadata", {}).get("langgraph_node")
    except RuntimeError:
        return None
