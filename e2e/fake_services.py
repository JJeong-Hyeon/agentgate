"""Fake external services for the end-to-end test.

- OpenAI-compatible LLM (/v1/models, /v1/chat/completions) with canned replies per agent role
- Tool target (/report) that records what it receives (GET /report/count)
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPLIES = {
    "planner": "1. Check the facts\n2. Summarize",
    "researcher": "Local LLMs keep data on-premise.",
    "reviewer": "VERDICT: APPROVE\nComplete.",
}
reports: list[dict] = []


class Handler(BaseHTTPRequestHandler):
    def _send(self, status: int, body: dict) -> None:
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}")

    def do_GET(self):
        if self.path == "/v1/models":
            self._send(200, {"object": "list", "data": [{"id": "fake-model", "object": "model"}]})
        elif self.path == "/report/count":
            self._send(200, {"count": len(reports)})
        else:
            self._send(404, {})

    def do_POST(self):
        body = self._body()
        if self.path == "/v1/chat/completions":
            system = next((m["content"] for m in body["messages"] if m["role"] == "system"), "")
            model = body.get("model", "fake-model")
            if "You are a tool agent" in system:
                self._send(200, tool_agent_turn(model, body))
                return
            role = next(r for r in REPLIES if f"You are a {r}" in system or f"strict {r}" in system)
            self._send(200, completion(model, REPLIES[role]))
        elif self.path == "/report":
            reports.append(body)
            self._send(200, {"ok": True})
        else:
            self._send(404, {})

    def log_message(self, *args):
        pass


def tool_agent_turn(model: str, body: dict) -> dict:
    """Calls the offered `add` tool once, then answers with what the tool returned."""
    last = body["messages"][-1]
    if last["role"] == "tool":
        return completion(model, f"Agent result: {last['content']}")
    add = next(
        t["function"]["name"] for t in body["tools"] if t["function"]["name"].endswith("add")
    )
    call = {
        "id": "call-add",
        "type": "function",
        "function": {"name": add, "arguments": '{"a": 2, "b": 3}'},
    }
    reply = completion(model, None)
    reply["choices"][0]["message"]["tool_calls"] = [call]
    reply["choices"][0]["finish_reason"] = "tool_calls"
    return reply


def completion(model: str, content: str | None) -> dict:
    return {
        "id": "chatcmpl-e2e",
        "object": "chat.completion",
        "created": 0,
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
    }


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18081
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
