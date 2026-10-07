import json
import sys
from pathlib import Path

import httpx
import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command
from pydantic import ValidationError

from app.dsl import Workflow, validate_workflow
from app.dsl.compiler import CompileError
from app.governance.agentgate_client import AgentGateClient
from app.tools.mcp import McpServerConfig, McpTool, load_mcp_servers
from tests.fakes import FakeAgentGate
from tests.workflows import compile_graph, compiler, initial_state

ECHO_SERVER = McpServerConfig(
    command=sys.executable, args=[str(Path(__file__).parent / "mcp_echo_server.py")]
)


def gate_client(gate: FakeAgentGate) -> AgentGateClient:
    return AgentGateClient("http://agentgate", "runtime-agent", "k", httpx.MockTransport(gate))


def tool(gate: FakeAgentGate, name: str = "echo", server=ECHO_SERVER) -> McpTool:
    return McpTool("say", "local", server, name, "MCP:local:echo", ["PII"], gate_client(gate))


def test_load_servers_from_config_file(tmp_path):
    config = tmp_path / "mcp.json"
    config.write_text(
        json.dumps(
            {
                "mcpServers": {
                    "files": {"command": "npx", "args": ["-y", "server-filesystem", "/data"]},
                    "search": {"url": "http://search:8000/mcp"},
                }
            }
        )
    )

    servers = load_mcp_servers(str(config))

    assert servers["files"].command == "npx"
    assert servers["search"].url == "http://search:8000/mcp"
    assert load_mcp_servers("") == {}


def test_server_needs_exactly_one_transport():
    with pytest.raises(ValidationError):
        McpServerConfig(command="x", url="http://y")
    with pytest.raises(ValidationError):
        McpServerConfig()


def test_allowed_call_runs_the_mcp_tool():
    gate = FakeAgentGate("ALLOWED", "LOW")

    result = tool(gate).run({"text": "hi"})

    assert result.status == "EXECUTED"
    assert result.response_body == "echo: hi"
    assert gate.bodies[0]["action"] == "MCP:local:echo"
    assert gate.bodies[0]["target"] == "mcp://local/echo"
    assert gate.bodies[0]["labels"] == ["PII"]


def test_approval_required_does_not_call():
    # A server that cannot even start proves no call was attempted.
    broken = McpServerConfig(command="/nonexistent/mcp-server")

    result = tool(FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 5), server=broken).run({"text": "hi"})

    assert result.status == "APPROVAL_REQUIRED"
    assert result.approval_id == 5


def test_tool_error_is_failed():
    result = tool(FakeAgentGate(), name="fail").run({})

    assert result.status == "FAILED"
    assert "fail" in result.error


def test_unreachable_server_is_failed():
    result = tool(FakeAgentGate(), server=McpServerConfig(command="/nonexistent/mcp-server")).run(
        {"text": "hi"}
    )

    assert result.status == "FAILED"
    assert result.error.startswith("MCP call failed")


def mcp_workflow(**config) -> dict:
    return {
        "workflowId": "mcp",
        "nodes": [
            {"id": "start", "type": "START"},
            {
                "id": "say",
                "type": "MCP_TOOL",
                "config": {
                    "server": "local",
                    "tool": "echo",
                    "arguments": {"text": "about {task}"},
                    **config,
                },
            },
            {"id": "answer", "type": "LLM", "config": {"prompt": "Tool said: {say}"}},
            {"id": "end", "type": "END"},
        ],
        "edges": [
            {"source": "start", "target": "say"},
            {"source": "say", "target": "answer"},
            {"source": "answer", "target": "end"},
        ],
    }


def compile_mcp(dsl: dict, gate: FakeAgentGate):
    return compiler(["done"], gate, mcp_servers={"local": ECHO_SERVER}).compile(
        Workflow.model_validate(dsl), InMemorySaver()
    )


def test_workflow_passes_rendered_arguments_and_exposes_output():
    dsl = mcp_workflow()
    gate = FakeAgentGate()
    graph = compile_mcp(dsl, gate)

    state = graph.invoke(initial_state(dsl, "runtimes"), {"configurable": {"thread_id": "m1"}})

    assert state["say"] == "echo: about runtimes"
    assert state["answer"] == "done"
    assert state["tool_results"][0]["status"] == "EXECUTED"


def test_workflow_waits_for_approval_then_calls():
    dsl = mcp_workflow(action="READ_FILES")
    gate = FakeAgentGate("APPROVAL_REQUIRED", "HIGH", 8)
    graph = compile_mcp(dsl, gate)
    config = {"configurable": {"thread_id": "m2"}}

    graph.invoke(initial_state(dsl, "x"), config)
    assert graph.get_state(config).interrupts[0].value == {"tool": "say", "approval_id": 8}
    assert gate.bodies[0]["action"] == "READ_FILES"

    state = graph.invoke(Command(resume={"decision": "APPROVED"}), config)

    assert state["say"] == "echo: about x"


def test_unknown_server_is_a_compile_error():
    with pytest.raises(CompileError, match="unknown MCP server 'local'"):
        compile_graph(mcp_workflow(), ["done"])


def test_argument_variables_are_validated():
    _, issues = validate_workflow(mcp_workflow(arguments={"text": "{ghost}"}))

    assert [(i.node_id, i.message) for i in issues] == [("say", "Unknown variable '{ghost}'")]
