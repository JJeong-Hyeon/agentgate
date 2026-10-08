"""Catalog of the tools the runtime can reach: what each configured MCP server offers.

Listing a stdio server starts its process, so results are cached for a short while.
A server that cannot be listed is reported with its error instead of failing the catalog.
"""

import logging
import threading
import time
from collections.abc import Callable

import anyio
from pydantic import BaseModel

from app.tools.mcp import McpServerConfig, McpToolInfo, list_tools

log = logging.getLogger(__name__)

Lister = Callable[[McpServerConfig], list[McpToolInfo]]


class ServerTools(BaseModel):
    server: str
    # "url" (Streamable HTTP) or "stdio"
    transport: str
    tools: list[McpToolInfo] = []
    error: str | None = None


def _list_sync(server: McpServerConfig) -> list[McpToolInfo]:
    return anyio.run(list_tools, server)


class ToolCatalog:
    def __init__(
        self,
        servers: dict[str, McpServerConfig],
        ttl_seconds: float = 60.0,
        lister: Lister = _list_sync,
        clock: Callable[[], float] = time.monotonic,
    ):
        self._servers = servers
        self._ttl = ttl_seconds
        self._lister = lister
        self._clock = clock
        self._cache: dict[str, tuple[float, ServerTools]] = {}
        self._lock = threading.Lock()

    @property
    def server_names(self) -> list[str]:
        return sorted(self._servers)

    def all(self, refresh: bool = False) -> list[ServerTools]:
        return [self.server(name, refresh) for name in self.server_names]

    def server(self, name: str, refresh: bool = False) -> ServerTools:
        """Raises KeyError for a server that is not configured."""
        config = self._servers[name]
        now = self._clock()
        with self._lock:
            cached = self._cache.get(name)
            if cached and not refresh and now - cached[0] < self._ttl:
                return cached[1]
        transport = "url" if config.url else "stdio"
        try:
            result = ServerTools(server=name, transport=transport, tools=self._lister(config))
        except Exception as e:  # noqa: BLE001 - one unreachable server must not hide the others
            log.warning("Could not list tools of MCP server %s: %s", name, e)
            result = ServerTools(server=name, transport=transport, error=str(e) or type(e).__name__)
        with self._lock:
            # Errors are not cached, so a server that comes back is seen on the next call.
            if result.error is None:
                self._cache[name] = (now, result)
        return result

    def tool(self, server: str, name: str) -> McpToolInfo | None:
        """The tool's description, or None when the server or tool is unknown or unreachable."""
        if server not in self._servers:
            return None
        return next((t for t in self.server(server).tools if t.name == name), None)
