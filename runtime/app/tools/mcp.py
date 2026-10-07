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
from pathlib import Path
from typing import Any

import anyio
from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client
from mcp.client.streamable_http import streamable_http_client
from pydantic import BaseModel, model_validator

from app.governance.agentgate_client import AgentGateClient
from app.tools.base import MAX_OUTPUT_CHARS, GovernedTool, ToolResult


class McpServerConfig(BaseModel):
    # stdio server
    command: str | None = None
    args: list[str] = []
    env: dict[str, str] | None = None
    # Streamable HTTP server
    url: str | None = None

    @model_validator(mode="after")
    def one_transport(self) -> "McpServerConfig":
        if (self.command is None) == (self.url is None):
            raise ValueError("an MCP server needs exactly one of 'command' or 'url'")
        return self


def load_mcp_servers(path: str) -> dict[str, McpServerConfig]:
    if not path:
        return {}
    servers = json.loads(Path(path).read_text()).get("mcpServers", {})
    return {name: McpServerConfig.model_validate(config) for name, config in servers.items()}


class McpTool(GovernedTool):
    def __init__(
        self,
        name: str,
        server_name: str,
        server: McpServerConfig,
        tool: str,
        action: str,
        labels: list[str],
        gate: AgentGateClient,
        timeout: float = 60.0,
    ):
        super().__init__(name, action, f"mcp://{server_name}/{tool}", labels, gate)
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
        with anyio.fail_after(self.timeout):
            if self.server.url:
                async with streamable_http_client(self.server.url) as streams:
                    return await self._call_in(streams[0], streams[1], arguments)
            params = StdioServerParameters(
                command=self.server.command, args=self.server.args, env=self.server.env
            )
            async with stdio_client(params) as (read, write):
                return await self._call_in(read, write, arguments)

    async def _call_in(self, read, write, arguments: dict[str, Any]) -> tuple[bool, str]:
        async with ClientSession(read, write) as session:
            await session.initialize()
            result = await session.call_tool(self.tool, arguments)
        text = "\n".join(c.text for c in result.content if getattr(c, "type", None) == "text")
        return bool(result.is_error), text
