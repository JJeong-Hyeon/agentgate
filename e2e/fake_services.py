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
            role = next(r for r in REPLIES if f"You are a {r}" in system or f"strict {r}" in system)
            self._send(200, completion(body.get("model", "fake-model"), REPLIES[role]))
        elif self.path == "/report":
            reports.append(body)
            self._send(200, {"ok": True})
        else:
            self._send(404, {})

    def log_message(self, *args):
        pass


def completion(model: str, content: str) -> dict:
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
