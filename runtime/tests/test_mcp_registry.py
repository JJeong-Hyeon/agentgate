import json
import socket
import threading
import time

import httpx
import pytest
import uvicorn

from app.governance.agentgate_client import AgentGateClient
from app.tools.base import ToolResult
from app.tools.catalog import ToolCatalog
from app.tools.mcp import McpServerConfig, McpTool
from app.tools.registry import AgentGateServers, McpServerRegistry
from tests.fakes import FakeAgentGate
from tests.mcp_echo_server import server as echo_server

TOKEN = "Bearer s3cret"


def guarded(app):
    """ASGI wrapper answering 401 unless the request carries the expected Authorization."""

    async def wrapped(scope, receive, send):
        if (
            scope["type"] == "http"
            and dict(scope["headers"]).get(b"authorization") != TOKEN.encode()
        ):
            await send({"type": "http.response.start", "status": 401, "headers": []})
            await send({"type": "http.response.body", "body": b"unauthorized"})
            return
        await app(scope, receive, send)

    return wrapped


@pytest.fixture(scope="module")
def protected_url():
    """A real Streamable HTTP MCP server (the echo server) that requires a bearer token."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    config = uvicorn.Config(
        guarded(echo_server.streamable_http_app()), host="127.0.0.1", port=port, log_level="warning"
    )
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 10
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    yield f"http://127.0.0.1:{port}/mcp"
    server.should_exit = True
    thread.join(timeout=5)


def echo_tool(server) -> McpTool:
    gate = AgentGateClient(
        "http://agentgate", "runtime-agent", "k", httpx.MockTransport(FakeAgentGate())
    )
    return McpTool("say", "crm", server, "echo", "MCP:crm:echo", [], gate)


def allowed() -> ToolResult:
    return ToolResult(tool="say", status="ALLOWED")


def test_registered_headers_reach_the_server(protected_url):
    with_token = McpServerConfig(url=protected_url, headers={"Authorization": TOKEN})
    without = McpServerConfig(url=protected_url)

    assert echo_tool(with_token).execute({"text": "hi"}, allowed()).response_body == "echo: hi"
    failed = echo_tool(without).execute({"text": "hi"}, allowed())
    assert failed.status == "FAILED"


def test_catalog_lists_a_protected_server_with_its_headers(protected_url):
    registry = McpServerRegistry(
        {}, lambda: {"crm": McpServerConfig(url=protected_url, headers={"Authorization": TOKEN})}
    )

    [crm] = ToolCatalog(registry).all()

    assert crm.error is None
    assert crm.source == "agentgate"
    assert {t.name for t in crm.tools} >= {"echo", "add"}
    assert "s3cret" not in crm.model_dump_json()


def test_tool_looks_up_its_server_at_each_call(protected_url):
    current = {"crm": McpServerConfig(url=protected_url, headers={"Authorization": "Bearer old"})}
    tool = echo_tool(lambda: current.get("crm"))

    assert tool.execute({"text": "x"}, allowed()).status == "FAILED"
    current["crm"] = McpServerConfig(url=protected_url, headers={"Authorization": TOKEN})
    assert tool.execute({"text": "x"}, allowed()).response_body == "echo: x"
    del current["crm"]
    gone = tool.execute({"text": "x"}, allowed())
    assert gone.status == "FAILED"
    assert "no longer configured" in gone.error


class Source:
    def __init__(self, *results):
        self.results = list(results)
        self.calls = 0

    def __call__(self):
        self.calls += 1
        result = self.results.pop(0) if len(self.results) > 1 else self.results[0]
        if isinstance(result, Exception):
            raise result
        return result


FILE = {"files": McpServerConfig(command="npx", args=["server-filesystem"])}


def test_registry_merges_and_the_config_file_wins_conflicts():
    registered = {
        "crm": McpServerConfig(url="http://crm/mcp"),
        "files": McpServerConfig(url="http://other/mcp"),
    }
    registry = McpServerRegistry(FILE, Source(registered))

    servers = registry.servers()

    assert set(servers) == {"crm", "files"}
    assert servers["files"].command == "npx"
    assert registry.source("files") == "runtime"
    assert registry.source("crm") == "agentgate"
    assert registry.source("nope") is None
    assert registry.conflicts() == ["files"]


def test_registry_caches_and_keeps_the_last_list_when_agentgate_fails():
    now = [0.0]
    first = {"crm": McpServerConfig(url="http://crm/mcp")}
    source = Source(first, RuntimeError("AgentGate down"))
    registry = McpServerRegistry({}, source, ttl_seconds=30, clock=lambda: now[0])

    assert set(registry.servers()) == {"crm"}
    registry.servers()
    assert source.calls == 1

    now[0] = 31
    assert set(registry.servers()) == {"crm"}  # failed fetch, last list kept
    assert source.calls == 2
    registry.servers()
    assert source.calls == 2  # the failure is not retried on every call


def test_registry_without_agentgate_has_only_the_config_file():
    assert McpServerRegistry(FILE).servers() == FILE


def test_catalog_reports_hidden_registered_servers():
    registry = McpServerRegistry(FILE, Source({"files": McpServerConfig(url="http://x/mcp")}))
    catalog = ToolCatalog(registry, lister=lambda config: [])

    servers = {(s.server, s.source): s for s in catalog.all()}

    assert servers[("files", "runtime")].error is None
    assert "config file" in servers[("files", "agentgate")].error


def test_catalog_relists_a_server_whose_configuration_changed():
    current = {"crm": McpServerConfig(url="http://a/mcp")}
    calls = []
    catalog = ToolCatalog(
        McpServerRegistry({}, lambda: dict(current), ttl_seconds=0),
        lister=lambda config: calls.append(config.url) or [],
    )

    catalog.server("crm")
    catalog.server("crm")
    current["crm"] = McpServerConfig(url="http://b/mcp")
    catalog.server("crm")

    assert calls == ["http://a/mcp", "http://b/mcp"]


def test_agentgate_servers_are_fetched_with_the_runtime_token():
    seen = []

    def agentgate(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(
            200,
            json=[
                {"name": "crm", "url": "https://crm/mcp", "headers": {"Authorization": TOKEN}},
                {"name": "open", "url": "https://open/mcp", "headers": {}},
            ],
        )

    servers = AgentGateServers("http://agentgate", "rt", httpx.MockTransport(agentgate))()

    assert seen[0].url == "http://agentgate/api/v1/runtime/mcp-servers"
    assert seen[0].headers["X-Runtime-Token"] == "rt"
    assert servers["crm"].headers == {"Authorization": TOKEN}
    assert servers["open"].headers is None


def test_headers_stay_out_of_reprs_and_need_a_url():
    config = McpServerConfig(url="https://crm/mcp", headers={"Authorization": TOKEN})
    assert "s3cret" not in repr(config)
    with pytest.raises(ValueError, match="only for 'url' servers"):
        McpServerConfig(command="npx", headers={"Authorization": TOKEN})
    assert json.loads(config.model_dump_json())[
        "headers"
    ]  # still usable when serialized on purpose
