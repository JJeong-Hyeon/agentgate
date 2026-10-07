#!/usr/bin/env bash
# End-to-end governance test: AgentGate (Spring) + Runtime (LangGraph) + fake LLM/tool target.
# Requires: Docker, Java 21, Python 3.12 (override with PYTHON=...).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PYTHON="${PYTHON:-python3}"
WORK="$(mktemp -d)"
TOKEN="e2e-runtime-token"
PIDS=()

cleanup() {
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
  docker compose -f "$ROOT/compose.yaml" -p agentgate-e2e down -v >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

wait_for() { # url, name, log
  for _ in $(seq 1 90); do
    curl -sf "$1" >/dev/null && return 0
    sleep 2
  done
  echo "$2 did not start; last log lines:" >&2
  tail -50 "$3" >&2
  exit 1
}

echo "==> Postgres / Redis"
docker compose -f "$ROOT/compose.yaml" -p agentgate-e2e up -d --wait postgres redis
PG_PORT=$(docker compose -f "$ROOT/compose.yaml" -p agentgate-e2e port postgres 5432 | cut -d: -f2)
REDIS_PORT=$(docker compose -f "$ROOT/compose.yaml" -p agentgate-e2e port redis 6379 | cut -d: -f2)

echo "==> AgentGate"
(cd "$ROOT" && ./gradlew -q bootJar)
JAR=$(find "$ROOT/build/libs" -name '*.jar' ! -name '*-plain.jar' | head -1)
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$PG_PORT/mydatabase" \
SPRING_DATASOURCE_USERNAME=myuser SPRING_DATASOURCE_PASSWORD=secret \
SPRING_DATA_REDIS_HOST=localhost SPRING_DATA_REDIS_PORT="$REDIS_PORT" \
SPRING_DOCKER_COMPOSE_ENABLED=false \
AGENTGATE_RUNTIME_BASE_URL=http://localhost:8000 AGENTGATE_RUNTIME_TOKEN="$TOKEN" \
AGENTGATE_RUNTIME_RETRY_DELAY=PT2S \
  java -jar "$JAR" > "$WORK/agentgate.log" 2>&1 &
PIDS+=($!)

echo "==> Fake LLM / tool target"
"$PYTHON" "$ROOT/e2e/fake_services.py" 18081 > "$WORK/fake.log" 2>&1 &
PIDS+=($!)

wait_for http://localhost:8080/actuator/health AgentGate "$WORK/agentgate.log"
API_KEY=$(curl -sf -u admin:changeme -X POST http://localhost:8080/api/v1/agents \
  -H 'Content-Type: application/json' -d '{"agentId":"runtime-agent","name":"Runtime Agent"}' \
  | "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin)["apiKey"])')

echo "==> Runtime"
# MCP server for the MCP tool scenario: the runtime's own test echo server over stdio.
cat > "$WORK/mcp.json" <<JSON
{"mcpServers": {"echo": {"command": "$(command -v "$PYTHON")", "args": ["$ROOT/runtime/tests/mcp_echo_server.py"]}}}
JSON
(cd "$ROOT/runtime" && \
  AGENTGATE_BASE_URL=http://localhost:8080 AGENTGATE_API_KEY="$API_KEY" \
  LLM_BASE_URL=http://localhost:18081/v1 LLM_MODEL=fake-model \
  RUNTIME_TOKEN="$TOKEN" MCP_CONFIG_PATH="$WORK/mcp.json" \
  "$PYTHON" -m uvicorn app.main:app --port 8000 > "$WORK/runtime.log" 2>&1) &
PIDS+=($!)
wait_for http://localhost:8000/health Runtime "$WORK/runtime.log"

echo "==> Tests"
if ! (cd "$ROOT/runtime" && "$PYTHON" -m pytest -q "$ROOT/e2e"); then
  echo "--- agentgate.log" >&2; tail -80 "$WORK/agentgate.log" >&2
  echo "--- runtime.log" >&2; tail -80 "$WORK/runtime.log" >&2
  exit 1
fi
