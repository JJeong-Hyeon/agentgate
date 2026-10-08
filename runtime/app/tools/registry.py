"""The MCP servers the runtime can use: those in its config file (MCP_CONFIG_PATH) plus
those registered in AgentGate, which can change at any time.

AgentGate's list is cached briefly; when it cannot be fetched the last one is kept, so a
short AgentGate outage does not take tools away. A name in both places is the config file's
(it is reported as a conflict).
"""

import logging
import threading
import time
from collections.abc import Callable

import httpx

from app.tools.mcp import McpServerConfig

log = logging.getLogger(__name__)

Fetch = Callable[[], dict[str, McpServerConfig]]


class AgentGateServers:
    """Fetches enabled MCP servers (with credentials) from AgentGate with the runtime token."""

    def __init__(self, base_url: str, token: str, transport: httpx.BaseTransport | None = None):
        self._client = httpx.Client(
            base_url=base_url,
            headers={"X-Runtime-Token": token},
            transport=transport,
            timeout=10.0,
        )

    def __call__(self) -> dict[str, McpServerConfig]:
        response = self._client.get("/api/v1/runtime/mcp-servers")
        response.raise_for_status()
        return {
            s["name"]: McpServerConfig(url=s["url"], headers=s.get("headers") or None)
            for s in response.json()
        }


class McpServerRegistry:
    def __init__(
        self,
        configured: dict[str, McpServerConfig],
        fetch: Fetch | None = None,
        ttl_seconds: float = 30.0,
        clock: Callable[[], float] = time.monotonic,
    ):
        self._configured = configured
        self._fetch = fetch
        self._ttl = ttl_seconds
        self._clock = clock
        self._registered: dict[str, McpServerConfig] = {}
        self._fetched_at: float | None = None
        self._lock = threading.Lock()

    def servers(self, refresh: bool = False) -> dict[str, McpServerConfig]:
        """All usable servers by name."""
        return {**self._current_registered(refresh), **self._configured}

    def get(self, name: str) -> McpServerConfig | None:
        return self.servers().get(name)

    def source(self, name: str) -> str | None:
        """'runtime' (config file) or 'agentgate' (registered), as servers() resolves it."""
        if name in self._configured:
            return "runtime"
        return "agentgate" if name in self._current_registered() else None

    def conflicts(self) -> list[str]:
        """Registered names hidden by a server of the same name in the config file."""
        return sorted(set(self._current_registered()) & set(self._configured))

    def _current_registered(self, refresh: bool = False) -> dict[str, McpServerConfig]:
        if self._fetch is None:
            return {}
        with self._lock:
            now = self._clock()
            fresh = self._fetched_at is not None and now - self._fetched_at < self._ttl
            if fresh and not refresh:
                return self._registered
            try:
                self._registered = self._fetch()
            except Exception as e:  # noqa: BLE001 - keep serving the last known servers
                log.warning("Could not fetch MCP servers from AgentGate: %s", e)
            # Also after a failure, so an outage is not retried on every call.
            self._fetched_at = now
            return self._registered
