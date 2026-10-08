import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.tools.catalog import ToolCatalog
from app.tools.mcp import McpServerConfig, McpToolInfo
from app.tools_api import get_catalog

ECHO_SERVER = McpServerConfig(
    command=sys.executable, args=[str(Path(__file__).parent / "mcp_echo_server.py")]
)
DOWN_SERVER = McpServerConfig(url="http://127.0.0.1:1/mcp")


def info(name: str) -> McpToolInfo:
    return McpToolInfo(name=name, input_schema={"type": "object"})


class CountingLister:
    def __init__(self, tools=None, error: Exception | None = None):
        self.tools = tools or [info("search")]
        self.error = error
        self.calls = 0

    def __call__(self, server):
        self.calls += 1
        if self.error:
            raise self.error
        return self.tools


def test_lists_a_real_stdio_server():
    catalog = ToolCatalog({"echo": ECHO_SERVER})

    [server] = catalog.all()

    assert server.error is None
    assert server.transport == "stdio"
    tools = {t.name: t for t in server.tools}
    assert set(tools) == {"echo", "add", "fail"}
    assert tools["add"].input_schema["properties"]["a"]["type"] == "integer"
    assert tools["add"].input_schema["required"] == ["a", "b"]
    assert tools["add"].annotations == {"readOnlyHint": True}
    assert tools["echo"].description == "Return the text prefixed with 'echo: '."


def test_unreachable_server_reports_its_error_without_hiding_others():
    catalog = ToolCatalog({"echo": ECHO_SERVER, "down": DOWN_SERVER})

    servers = {s.server: s for s in catalog.all()}

    assert servers["down"].error
    assert servers["down"].tools == []
    assert servers["down"].transport == "url"
    assert servers["echo"].error is None


def test_results_are_cached_until_ttl_or_refresh():
    now = [0.0]
    lister = CountingLister()
    catalog = ToolCatalog({"s": DOWN_SERVER}, ttl_seconds=60, lister=lister, clock=lambda: now[0])

    catalog.server("s")
    catalog.server("s")
    assert lister.calls == 1

    catalog.server("s", refresh=True)
    assert lister.calls == 2

    now[0] = 61
    catalog.server("s")
    assert lister.calls == 3


def test_errors_are_not_cached():
    lister = CountingLister(error=RuntimeError("down"))
    catalog = ToolCatalog({"s": DOWN_SERVER}, lister=lister)

    assert catalog.server("s").error == "down"
    catalog.server("s")
    assert lister.calls == 2


def test_tool_lookup():
    catalog = ToolCatalog({"s": DOWN_SERVER}, lister=CountingLister([info("search")]))

    assert catalog.tool("s", "search").name == "search"
    assert catalog.tool("s", "missing") is None
    assert catalog.tool("unknown", "search") is None


def test_unknown_server_raises_key_error():
    with pytest.raises(KeyError):
        ToolCatalog({}).server("nope")


@pytest.fixture
def api():
    catalog = ToolCatalog(
        {"a": DOWN_SERVER, "b": DOWN_SERVER}, lister=CountingLister([info("search")])
    )
    app.dependency_overrides[get_catalog] = lambda: catalog
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_api_lists_servers_and_tools(api):
    response = api.get("/runtime/tools")

    assert response.status_code == 200
    body = response.json()
    assert [s["server"] for s in body] == ["a", "b"]
    assert body[0]["tools"][0] == {
        "name": "search",
        "title": None,
        "description": None,
        "input_schema": {"type": "object"},
        "annotations": None,
    }


def test_api_gets_one_server_or_404(api):
    assert api.get("/runtime/tools/a?refresh=true").json()["server"] == "a"
    assert api.get("/runtime/tools/zzz").status_code == 404
