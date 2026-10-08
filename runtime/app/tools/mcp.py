"""MCP tools: calls to tools on MCP servers, guarded by AgentGate like any other tool.

Servers are configured in a JSON file (MCP_CONFIG_PATH) in the common `mcpServers` format:

    {"mcpServers": {
        "files": {"command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"]},
        "search": {"url": "http://search-mcp:8000/mcp"}
    }}

Each call opens its own session (stdio process or Streamable HTTP connection) and closes it.
"""

import json
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

import anyio
from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client
from mcp.client.streamable_http import streamable_http_client
from mcp.shared._httpx_utils import create_mcp_http_client
from mcp.types import PaginatedRequestParams
from pydantic import BaseModel, Field, model_validator

from app.governance.agentgate_client import AgentGateClient
from app.tools.base import MAX_OUTPUT_CHARS, GovernedTool, ToolResult


class McpServerConfig(BaseModel):
    # stdio server
    command: str | None = None
    args: list[str] = []
    env: dict[str, str] | None = None
    # Streamable HTTP server
    url: str | None = None
    # Sent with every request to a URL server (e.g. Authorization); secret, so kept out of repr.
    headers: dict[str, str] | None = Field(default=None, repr=False)

    @model_validator(mode="after")
    def one_transport(self) -> "McpServerConfig":
        if (self.command is None) == (self.url is None):
            raise ValueError("an MCP server needs exactly one of 'command' or 'url'")
        if self.headers and self.url is None:
            raise ValueError("headers are only for 'url' servers")
        return self


def load_mcp_servers(path: str) -> dict[str, McpServerConfig]:
    if not path:
        return {}
    servers = json.loads(Path(path).read_text()).get("mcpServers", {})
    return {name: McpServerConfig.model_validate(config) for name, config in servers.items()}


ServerSource = McpServerConfig | Callable[[], McpServerConfig | None]


class McpTool(GovernedTool):
    """`server` may be a function looked up at each call, so a server's URL or credentials can
    change (or the server go away) while compiled graphs are cached and executions are paused."""

    def __init__(
        self,
        name: str,
        server_name: str,
        server: ServerSource,
        tool: str,
        action: str,
        labels: list[str],
        gate: AgentGateClient,
        timeout: float = 60.0,
    ):
        super().__init__(name, action, f"mcp://{server_name}/{tool}", labels, gate)
        self.server_name = server_name
        self.server = server
        self.tool = tool
        self.timeout = timeout

    def execute(self, payload: dict[str, Any] | None, authorized: ToolResult) -> ToolResult:
        try:
            is_error, text = anyio.run(self._call, payload or {})
        except Exception as e:  # noqa: BLE001 - any transport or protocol failure fails the step
            return ToolResult(
                **self._base(authorized), status="FAILED", error=f"MCP call failed: {e}"
            )
        if is_error:
            return ToolResult(
                **self._base(authorized), status="FAILED", error=text[:MAX_OUTPUT_CHARS]
            )
        return ToolResult(
            **self._base(authorized), status="EXECUTED", response_body=text[:MAX_OUTPUT_CHARS]
        )

    async def _call(self, arguments: dict[str, Any]) -> tuple[bool, str]:
        server = self.server() if callable(self.server) else self.server
        if server is None:
            raise RuntimeError(f"MCP server '{self.server_name}' is no longer configured")
        with anyio.fail_after(self.timeout):
            async with open_session(server) as session:
                result = await session.call_tool(self.tool, arguments)
        text = "\n".join(c.text for c in result.content if getattr(c, "type", None) == "text")
        return bool(result.is_error), text


@asynccontextmanager
async def open_session(server: McpServerConfig) -> AsyncIterator[ClientSession]:
    """An initialized session with `server` over its transport (Streamable HTTP or stdio)."""
    if server.url:
        async with create_mcp_http_client(headers=server.headers) as http:
            async with streamable_http_client(server.url, http_client=http) as streams:
                async with ClientSession(streams[0], streams[1]) as session:
                    await session.initialize()
                    yield session
        return
    params = StdioServerParameters(command=server.command, args=server.args, env=server.env)
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            yield session


class McpToolInfo(BaseModel):
    """A tool as an MCP server describes it (tools/list)."""

    name: str
    title: str | None = None
    description: str | None = None
    # JSON Schema of the tool's arguments.
    input_schema: dict[str, Any]
    # Server hints such as readOnlyHint / destructiveHint; untrusted, informational only.
    annotations: dict[str, Any] | None = None


async def list_tools(server: McpServerConfig, timeout: float = 30.0) -> list[McpToolInfo]:
    with anyio.fail_after(timeout):
        async with open_session(server) as session:
            tools: list[McpToolInfo] = []
            cursor = None
            while True:
                params = PaginatedRequestParams(cursor=cursor) if cursor else None
                page = await session.list_tools(params=params)
                tools += [
                    McpToolInfo(
                        name=t.name,
                        title=t.title,
                        description=t.description,
                        input_schema=t.input_schema,
                        annotations=(
                            t.annotations.model_dump(by_alias=True, exclude_none=True)
                            if t.annotations
                            else None
                        ),
                    )
                    for t in page.tools
                ]
                cursor = page.next_cursor
                if not cursor:
                    return tools
