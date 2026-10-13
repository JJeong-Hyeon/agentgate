"""Fault-injection tests for the external-call ledger: does a call actually run exactly once
under duplication, concurrency, and a crash that leaves its outcome unknown? Scenarios follow
docs/AgentGate_FINALIZATION_PLAN.md section 3 (T1). Needs a real Postgres: set
LEDGER_TEST_DATABASE_URL to run, otherwise every test here is skipped.

Deliberately a different variable from RUNTIME_DATABASE_URL (which Settings binds to
automatically): other suites build graphs that reuse the same fixed execution id/node names
across test functions, and if RUNTIME_DATABASE_URL were set for the whole process, the first
test to call the cached get_settings() would hand every later test -- including unrelated ones
-- a real, shared ledger, letting one test's call replay as another's. Instead an autouse
fixture monkeypatches app.tools.base.get_settings for the scope of this module only.
"""

import os
import sys
import threading
import uuid
from pathlib import Path

import httpx
import pytest

from app.config import Settings
from app.governance.agentgate_client import AgentGateClient
from app.tools import ledger
from app.tools.http import HttpTool, HttpToolSpec
from app.tools.mcp import McpServerConfig, McpTool
from tests.fakes import FakeAgentGate, FakeTarget

_DB_URL = os.environ.get("LEDGER_TEST_DATABASE_URL", "")

pytestmark = pytest.mark.skipif(
    not _DB_URL, reason="set LEDGER_TEST_DATABASE_URL to run ledger tests against a real Postgres"
)

ECHO_SERVER = McpServerConfig(
    command=sys.executable, args=[str(Path(__file__).parent / "mcp_echo_server.py")]
)


def ledger_settings() -> Settings:
    return Settings(_env_file=None, runtime_database_url=_DB_URL)


@pytest.fixture(autouse=True)
def _real_ledger_for_this_module(monkeypatch):
    import app.tools.base as base_module

    monkeypatch.setattr(base_module, "get_settings", ledger_settings)


def http_tool(target: FakeTarget) -> HttpTool:
    spec = HttpToolSpec(name="report", action="SEND_REPORT", url="http://hook/report", labels=[])
    client = AgentGateClient(
        "http://agentgate", "runtime-agent", "k", httpx.MockTransport(FakeAgentGate())
    )
    return HttpTool(spec, client, httpx.MockTransport(target))


def mcp_tool() -> McpTool:
    client = AgentGateClient(
        "http://agentgate", "runtime-agent", "k", httpx.MockTransport(FakeAgentGate())
    )
    return McpTool("say", "local", ECHO_SERVER, "count", "MCP:local:count", [], client)


def authorized(key: str):
    from app.tools.base import ToolResult

    return ToolResult(tool="t", status="ALLOWED", idempotency_key=key)


def new_key() -> str:
    return f"test:{uuid.uuid4()}"


# A. normal call --------------------------------------------------------------------------


def test_a_normal_call_runs_once_and_is_recorded():
    target = FakeTarget()
    tool = http_tool(target)
    key = new_key()

    result = tool.execute_timed({"msg": "hi"}, authorized(key))

    assert result.status == "EXECUTED"
    assert len(target.requests) == 1


# B. duplicate resume: same key called again ----------------------------------------------


def test_b_a_repeated_call_is_not_sent_again():
    target = FakeTarget()
    tool = http_tool(target)
    key = new_key()

    first = tool.execute_timed({"msg": "hi"}, authorized(key))
    second = tool.execute_timed({"msg": "hi"}, authorized(key))

    assert len(target.requests) == 1  # the external call ran exactly once
    assert second.status == first.status == "EXECUTED"
    assert second.response_status == first.response_status


# C / D. a call interrupted mid-run (crash between the external call and ledger.finish()) --
# docs/AgentGate_CODE_REVIEW.md's two scenarios -- "Runtime restart after the tool call
# succeeded" and "a crash before the checkpoint is saved" -- look identical to the ledger: a
# row stuck at STARTED with no outcome recorded. One test covers both.


def test_c_a_call_that_never_finished_is_not_retried():
    target = FakeTarget()
    tool = http_tool(target)
    key = new_key()
    # Simulate the crash: claim the key (as execute_timed would) but never call finish().
    claimed = ledger.begin(ledger_settings(), key, tool.name)
    assert claimed is None  # newly claimed, as execute_timed would see it

    result = tool.execute_timed({"msg": "hi"}, authorized(key))

    assert target.requests == []  # not retried: its outcome was unknown, not failed
    assert result.status == "FAILED"


# E. concurrent calls with the same key race to run it ------------------------------------


def test_e_concurrent_calls_with_the_same_key_run_it_once():
    target = FakeTarget()
    tool = http_tool(target)
    key = new_key()
    results: list[str] = []

    def call():
        results.append(tool.execute_timed({"msg": "hi"}, authorized(key)).status)

    threads = [threading.Thread(target=call) for _ in range(5)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert len(target.requests) == 1
    assert results.count("EXECUTED") == 1
    # the rest replayed the same result or saw it as still in flight (unknown, not retried)
    assert all(r in ("EXECUTED", "FAILED") for r in results)


# F. a different payload must not replay another call's result ----------------------------


def test_f_different_payloads_get_independent_ledger_entries():
    target = FakeTarget()
    tool = http_tool(target)
    key_a, key_b = new_key(), new_key()  # nodes/tool.py mixes the payload hash into the key

    tool.execute_timed({"order": "A"}, authorized(key_a))
    tool.execute_timed({"order": "B"}, authorized(key_b))

    assert len(target.requests) == 2  # both ran; neither replayed the other's result


# G. the ledger itself is unreachable: fail closed, never run the call unprotected --------


def test_g_an_unreachable_ledger_blocks_the_call_instead_of_skipping_it():
    target = FakeTarget()
    tool = http_tool(target)
    key = new_key()
    broken = Settings(_env_file=None, runtime_database_url="postgresql://u:p@127.0.0.1:1/db")

    import app.tools.base as base_module

    original = base_module.get_settings
    base_module.get_settings = lambda: broken
    try:
        result = tool.execute_timed({"msg": "hi"}, authorized(key))
    finally:
        base_module.get_settings = original

    assert target.requests == []  # never ran without the ledger's protection
    assert result.status == "FAILED"


# H. a failed call is on record too, and is not blindly retried ---------------------------


def test_h_a_failed_call_replays_as_failed_rather_than_retrying():
    tool = http_tool(FakeTarget(status_code=500))
    key = new_key()

    first = tool.execute_timed({"msg": "hi"}, authorized(key))
    assert first.status == "FAILED"

    target_two = FakeTarget()  # a fresh target: if this got called, it would be a retry
    tool_two = http_tool(target_two)
    second = tool_two.execute_timed({"msg": "hi"}, authorized(key))

    assert second.status == "FAILED"
    assert target_two.requests == []


# I. a replayed result carries the same fields as the original ----------------------------


def test_i_a_replayed_result_matches_the_original():
    target = FakeTarget()
    tool = http_tool(target)
    key = new_key()

    first = tool.execute_timed({"msg": "hi"}, authorized(key))
    second = tool.execute_timed({"msg": "hi"}, authorized(key))

    assert second.response_status == first.response_status
    assert second.response_body == first.response_body
    assert second.duration_ms == first.duration_ms


# J. the same protection applies to MCP tools, not just HTTP ------------------------------


def test_j_mcp_calls_are_covered_by_the_same_ledger(tmp_path):
    counter_file = tmp_path / "calls.txt"
    tool = mcp_tool()
    key = new_key()

    first = tool.execute_timed({"path": str(counter_file)}, authorized(key))
    second = tool.execute_timed({"path": str(counter_file)}, authorized(key))

    assert counter_file.read_text().count("1") == 1  # the MCP server process ran once
    assert first.status == second.status == "EXECUTED"
    assert first.response_body == second.response_body
