"""End-to-end: Runtime tool calls governed by a real AgentGate (Spring).

Run via scripts/e2e.sh, which starts AgentGate, the runtime and fake services.
Each test sets the risk of SEND_REPORT (the research workflow's report action) through
the policy API, then runs the graph and checks what reached the tool target.
"""

import json
import os
import time
import uuid
from pathlib import Path

import httpx
import pytest

AGENTGATE = os.environ.get("E2E_AGENTGATE_URL", "http://localhost:8080")
RUNTIME = os.environ.get("E2E_RUNTIME_URL", "http://localhost:8000")
FAKE = os.environ.get("E2E_FAKE_URL", "http://localhost:18081")
ADMIN = ("admin", os.environ.get("E2E_ADMIN_PASSWORD", "changeme"))


@pytest.fixture(scope="module")
def admin():
    with httpx.Client(base_url=AGENTGATE, auth=ADMIN, timeout=10) as client:
        yield client


@pytest.fixture
def report_risk(admin):
    created: list[int] = []

    def set_risk(level: str) -> None:
        policy = admin.post(
            "/api/v1/policies", json={"actionType": "SEND_REPORT", "riskLevel": level}
        )
        policy.raise_for_status()
        created.append(policy.json()["id"])

    yield set_risk
    for policy_id in created:
        admin.delete(f"/api/v1/policies/{policy_id}")


def reports_received() -> int:
    return httpx.get(f"{FAKE}/report/count").json()["count"]


def research_workflow() -> dict:
    """The research example as DSL, reporting to the fake tool target."""
    path = Path(__file__).parent.parent / "runtime" / "examples" / "research.json"
    workflow = json.loads(path.read_text())
    report = next(n for n in workflow["nodes"] if n["id"] == "report")
    report["config"]["url"] = f"{FAKE}/report"
    return workflow


def start_execution(workflow: dict | None = None) -> dict:
    body = {"task": "e2e", "workflow": workflow or research_workflow()}
    response = httpx.post(f"{RUNTIME}/runtime/executions", json=body, timeout=60)
    response.raise_for_status()
    return response.json()


def wait_for(execution_id: str, status: str, timeout: float = 15) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        execution = httpx.get(f"{RUNTIME}/runtime/executions/{execution_id}").json()
        if execution["status"] == status:
            return execution
        time.sleep(0.5)
    raise AssertionError(f"{execution_id} did not reach {status}: {execution}")


def tool_statuses(execution: dict) -> list[str]:
    return [r["status"] for r in execution["state"].get("tool_results", [])]


def test_allowed_tool_runs_immediately(report_risk):
    report_risk("LOW")
    before = reports_received()

    execution = start_execution()

    assert execution["status"] == "COMPLETED"
    assert tool_statuses(execution) == ["EXECUTED"]
    assert reports_received() == before + 1


def test_blocked_tool_never_runs(report_risk):
    report_risk("BLOCKED")
    before = reports_received()

    execution = start_execution()

    assert execution["status"] == "STOPPED"
    assert execution["state"]["stopped"]["reason"].startswith("report: blocked by AgentGate")
    assert tool_statuses(execution) == ["BLOCKED"]
    assert reports_received() == before


@pytest.mark.parametrize(
    ("decision", "expected_tool_status", "expected_reports", "final_status"),
    [("approve", "EXECUTED", 1, "COMPLETED"), ("reject", "REJECTED", 0, "STOPPED")],
)
def test_high_risk_tool_waits_for_human_decision(
    admin, report_risk, decision, expected_tool_status, expected_reports, final_status
):
    report_risk("HIGH")
    before = reports_received()

    execution = start_execution()
    assert execution["status"] == "WAITING_APPROVAL"
    assert reports_received() == before

    approval_id = execution["waiting_approval_id"]
    approval = admin.get(f"/api/v1/approvals/{approval_id}").json()
    assert approval["executionId"] == execution["execution_id"]
    admin.post(
        f"/api/v1/approvals/{approval_id}/{decision}", json={"decidedBy": "e2e"}
    ).raise_for_status()

    # AgentGate resumes the runtime on its own; nothing else calls the runtime here.
    finished = wait_for(execution["execution_id"], final_status)
    assert tool_statuses(finished) == [expected_tool_status]
    assert reports_received() == before + expected_reports


def wait_for_agentgate(admin, execution_id: str, status: str, timeout: float = 20) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        execution = admin.get(f"/api/v1/executions/{execution_id}").json()
        if execution["status"] == status:
            return execution
        time.sleep(0.5)
    raise AssertionError(f"{execution_id} did not reach {status} in AgentGate: {execution}")


def test_execution_started_through_agentgate_is_tracked(admin, report_risk):
    report_risk("HIGH")
    workflow_id = f"research-{int(time.time() * 1000)}"
    admin.post(
        "/api/v1/workflows", json={"workflowId": workflow_id, "dsl": research_workflow()}
    ).raise_for_status()
    before = reports_received()

    started = admin.post("/api/v1/executions", json={"workflowId": workflow_id, "task": "e2e"})
    started.raise_for_status()
    execution_id = started.json()["executionId"]

    waiting = wait_for_agentgate(admin, execution_id, "WAITING_APPROVAL")
    assert [n["nodeId"] for n in waiting["nodes"]][:3] == ["plan", "findings", "review"]
    assert waiting["nodes"][-1]["status"] == "WAITING"
    admin.post(
        f"/api/v1/approvals/{waiting['waitingApprovalId']}/approve", json={}
    ).raise_for_status()

    done = wait_for_agentgate(admin, execution_id, "COMPLETED")
    assert all(n["status"] == "COMPLETED" for n in done["nodes"])
    assert [n["step"] for n in done["nodes"]][-2:] == ["report.approval", "report.execute"]
    assert reports_received() == before + 1


def test_approval_node_waits_for_a_human_then_continues(admin):
    workflow = {
        "workflowId": "approval-e2e",
        "nodes": [
            {"id": "start", "type": "START"},
            {"id": "confirm", "type": "APPROVAL", "config": {"message": "Proceed with {task}?"}},
            {
                "id": "answer",
                "type": "LLM",
                "config": {"system": "You are a researcher.", "prompt": "{task}"},
            },
            {"id": "end", "type": "END"},
        ],
        "edges": [
            {"source": "start", "target": "confirm"},
            {"source": "confirm", "target": "answer"},
            {"source": "answer", "target": "end"},
        ],
    }

    execution = start_execution(workflow)
    assert execution["status"] == "WAITING_APPROVAL"
    approval = admin.get(f"/api/v1/approvals/{execution['waiting_approval_id']}").json()
    assert approval["reason"] == "Proceed with e2e?"
    assert approval["action"] == "HUMAN_APPROVAL"

    admin.post(f"/api/v1/approvals/{approval['id']}/approve", json={}).raise_for_status()

    finished = wait_for(execution["execution_id"], "COMPLETED")
    assert finished["state"]["confirm"] == "APPROVED"
    assert finished["state"]["answer"]


@pytest.fixture
def action_risk(admin):
    created: list[int] = []

    def set_risk(action: str, level: str) -> None:
        policy = admin.post("/api/v1/policies", json={"actionType": action, "riskLevel": level})
        policy.raise_for_status()
        created.append(policy.json()["id"])

    yield set_risk
    for policy_id in created:
        admin.delete(f"/api/v1/policies/{policy_id}")


MCP_WORKFLOW = {
    "workflowId": "mcp-e2e",
    "nodes": [
        {"id": "start", "type": "START"},
        {
            "id": "say",
            "type": "MCP_TOOL",
            "config": {"server": "echo", "tool": "echo", "arguments": {"text": "{task}"}},
        },
        {"id": "end", "type": "END"},
    ],
    "edges": [
        {"source": "start", "target": "say"},
        {"source": "say", "target": "end"},
    ],
}


@pytest.mark.parametrize(
    ("level", "expected", "status"),
    [("LOW", "echo: e2e", "COMPLETED"), ("BLOCKED", "BLOCKED", "STOPPED")],
)
def test_mcp_tool_is_governed_by_agentgate(action_risk, level, expected, status):
    action_risk("MCP:echo:echo", level)

    execution = start_execution(MCP_WORKFLOW)

    assert execution["status"] == status
    assert execution["state"]["say"] == expected


def test_tools_of_configured_mcp_servers_are_listed(admin):
    response = admin.get("/api/v1/tools", params={"refresh": "true"}, timeout=40)

    assert response.status_code == 200
    [echo] = [s for s in response.json() if s["server"] == "echo"]
    assert echo["error"] is None
    tools = {t["name"]: t for t in echo["tools"]}
    assert {"echo", "add", "fail"} <= set(tools)
    assert tools["add"]["inputSchema"]["required"] == ["a", "b"]


def wait_for_execution(admin, execution_id: str, status: str, timeout: float = 30) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        execution = admin.get(f"/api/v1/executions/{execution_id}").json()
        if execution["status"] == status:
            return execution
        time.sleep(0.5)
    raise AssertionError(f"execution {execution_id} never reached {status}: {execution}")


def test_registered_agent_calls_tools_under_its_own_permissions(admin, action_risk):
    # Policies alone would allow `add`; the agent's definition requires approval for it.
    action_risk("MCP:echo:add", "LOW")
    suffix = uuid.uuid4().hex[:8]
    agent_id = f"e2e-agent-{suffix}"
    agent = admin.post("/api/v1/agents", json={"agentId": agent_id, "name": "E2E Agent"}).json()
    admin.put(
        f"/api/v1/agents/{agent['id']}/definition",
        json={
            "systemPrompt": "You are a tool agent.",
            "tools": [
                {"server": "echo", "tool": "add", "permission": "APPROVAL"},
                {"server": "echo", "tool": "echo", "permission": "AUTO"},
            ],
        },
    ).raise_for_status()
    workflow_id = f"agent-e2e-{suffix}"
    admin.post(
        "/api/v1/workflows",
        json={
            "workflowId": workflow_id,
            "dsl": {
                "nodes": [
                    {"id": "start", "type": "START"},
                    {
                        "id": "helper",
                        "type": "AGENT",
                        "config": {"agentId": agent_id, "prompt": "{task}"},
                    },
                    {"id": "end", "type": "END"},
                ],
                "edges": [
                    {"source": "start", "target": "helper"},
                    {"source": "helper", "target": "end"},
                ],
            },
        },
    ).raise_for_status()

    started = admin.post("/api/v1/executions", json={"workflowId": workflow_id, "task": "add"})
    started.raise_for_status()
    assert started.json()["agentVersions"] == {agent_id: 1}
    execution_id = started.json()["executionId"]

    waiting = wait_for_execution(admin, execution_id, "WAITING_APPROVAL", timeout=60)
    approval = admin.get(f"/api/v1/approvals/{waiting['waitingApprovalId']}").json()
    assert approval["agentId"] == agent_id
    assert approval["action"] == "MCP:echo:add"
    assert (
        approval["reason"]
        == f'Agent \'{agent_id}\' (v1) wants to call echo/add with {{"a": 2, "b": 3}}'
    )
    [audit] = admin.get("/api/v1/audit-logs", params={"agentId": agent_id}).json()
    assert audit["basis"] == "TOOL_REQUIRES_APPROVAL"

    admin.post(f"/api/v1/approvals/{approval['id']}/approve", json={}).raise_for_status()

    finished = wait_for_execution(admin, execution_id, "COMPLETED", timeout=60)
    outputs = " ".join(n.get("output") or "" for n in finished["nodes"])
    assert "Agent result: 5" in outputs


def test_rejected_tool_stops_the_execution_in_agentgate(admin, report_risk):
    report_risk("HIGH")
    workflow_id = f"research-stop-{int(time.time() * 1000)}"
    admin.post(
        "/api/v1/workflows", json={"workflowId": workflow_id, "dsl": research_workflow()}
    ).raise_for_status()
    started = admin.post("/api/v1/executions", json={"workflowId": workflow_id, "task": "e2e"})
    started.raise_for_status()
    execution_id = started.json()["executionId"]

    waiting = wait_for_agentgate(admin, execution_id, "WAITING_APPROVAL")
    admin.post(
        f"/api/v1/approvals/{waiting['waitingApprovalId']}/reject", json={}
    ).raise_for_status()

    stopped = wait_for_agentgate(admin, execution_id, "STOPPED")
    assert stopped["error"] == "report: rejected by an approver"
    assert stopped["finishedAt"]


def test_supervisor_delegates_to_a_worker_governed_under_its_own_name(admin, action_risk):
    action_risk("MCP:echo:add", "LOW")
    suffix = uuid.uuid4().hex[:8]
    worker_id, lead_id = f"e2e-worker-{suffix}", f"e2e-lead-{suffix}"
    worker = admin.post("/api/v1/agents", json={"agentId": worker_id, "name": "Worker"}).json()
    admin.put(
        f"/api/v1/agents/{worker['id']}/definition",
        json={
            "systemPrompt": "You are a tool agent.",
            "tools": [{"server": "echo", "tool": "add", "permission": "APPROVAL"}],
        },
    ).raise_for_status()
    lead = admin.post("/api/v1/agents", json={"agentId": lead_id, "name": "Lead"}).json()
    admin.put(
        f"/api/v1/agents/{lead['id']}/definition",
        json={
            "systemPrompt": "You are a supervisor agent.",
            "tools": [],
            "delegates": [{"agentId": worker_id, "permission": "AUTO"}],
        },
    ).raise_for_status()
    workflow_id = f"team-e2e-{suffix}"
    admin.post(
        "/api/v1/workflows",
        json={
            "workflowId": workflow_id,
            "dsl": {
                "nodes": [
                    {"id": "start", "type": "START"},
                    {
                        "id": "lead",
                        "type": "AGENT",
                        "config": {"agentId": lead_id, "prompt": "{task}"},
                    },
                    {"id": "end", "type": "END"},
                ],
                "edges": [
                    {"source": "start", "target": "lead"},
                    {"source": "lead", "target": "end"},
                ],
            },
        },
    ).raise_for_status()

    started = admin.post("/api/v1/executions", json={"workflowId": workflow_id, "task": "add"})
    started.raise_for_status()
    assert started.json()["agentVersions"] == {lead_id: 1, worker_id: 1}
    execution_id = started.json()["executionId"]

    waiting = wait_for_execution(admin, execution_id, "WAITING_APPROVAL", timeout=60)
    approval = admin.get(f"/api/v1/approvals/{waiting['waitingApprovalId']}").json()
    assert approval["agentId"] == worker_id
    assert approval["delegatedBy"] == lead_id
    assert approval["action"] == "MCP:echo:add"
    admin.post(f"/api/v1/approvals/{approval['id']}/approve", json={}).raise_for_status()

    finished = wait_for_execution(admin, execution_id, "COMPLETED", timeout=60)
    outputs = " ".join(n.get("output") or "" for n in finished["nodes"])
    assert "Supervisor result: Agent result: 5" in outputs
    assert {n["nodeId"] for n in finished["nodes"]} == {"lead"}
    delegation = admin.get("/api/v1/audit-logs", params={"agentId": lead_id}).json()
    assert [a["action"] for a in delegation] == [f"AGENT:{worker_id}"]
