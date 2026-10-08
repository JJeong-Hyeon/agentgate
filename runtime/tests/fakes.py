import json

import httpx


class FakeAgentGate:
    """MockTransport handler that records evaluation requests and returns a fixed decision."""

    def __init__(
        self, status="ALLOWED", risk_level="LOW", approval_id=None, http_status=200, basis=None
    ):
        self.decision = {"status": status, "riskLevel": risk_level, "approvalId": approval_id}
        if basis:
            self.decision["basis"] = basis
        self.http_status = http_status
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.http_status != 200:
            return httpx.Response(self.http_status, json={"code": "INVALID_API_KEY"})
        return httpx.Response(200, json=self.decision)

    @property
    def bodies(self) -> list[dict]:
        return [json.loads(r.content) for r in self.requests]


class FakeTarget:
    """MockTransport handler standing in for the external system a tool calls."""

    def __init__(self, status_code=200):
        self.status_code = status_code
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return httpx.Response(self.status_code, json={"ok": True})


class FakeAgentGateEvents:
    """MockTransport handler standing in for AgentGate's execution event endpoint."""

    def __init__(self, status_code=200):
        self.status_code = status_code
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return httpx.Response(self.status_code)

    @property
    def events(self) -> list[dict]:
        return [json.loads(r.content) for r in self.requests]

    @property
    def types(self) -> list[str]:
        return [e["type"] for e in self.events]
