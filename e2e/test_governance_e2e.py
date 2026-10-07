"""End-to-end: Runtime tool calls governed by a real AgentGate (Spring).

Run via scripts/e2e.sh, which starts AgentGate, the runtime and fake services.
Each test sets the risk of SEND_REPORT (the research graph's report action) through
the policy API, then runs the graph and checks what reached the tool target.
"""

import json
import os
import time
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
    body = {"task": "e2e", "workflow": workflow} if workflow else {"task": "e2e"}
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

    assert execution["status"] == "COMPLETED"
    assert tool_statuses(execution) == ["BLOCKED"]
    assert reports_received() == before


@pytest.mark.parametrize(
    ("decision", "expected_tool_status", "expected_reports", "use_dsl"),
    [
        ("approve", "EXECUTED", 1, False),
        ("reject", "REJECTED", 0, False),
        ("approve", "EXECUTED", 1, True),
    ],
    ids=["approve", "reject", "approve-dsl-workflow"],
)
def test_high_risk_tool_waits_for_human_decision(
    admin, report_risk, decision, expected_tool_status, expected_reports, use_dsl
):
    report_risk("HIGH")
    before = reports_received()

    execution = start_execution(research_workflow() if use_dsl else None)
    assert execution["status"] == "WAITING_APPROVAL"
    assert reports_received() == before

    approval_id = execution["waiting_approval_id"]
    approval = admin.get(f"/api/v1/approvals/{approval_id}").json()
    assert approval["executionId"] == execution["execution_id"]
    admin.post(
        f"/api/v1/approvals/{approval_id}/{decision}", json={"decidedBy": "e2e"}
    ).raise_for_status()

    # AgentGate resumes the runtime on its own; nothing else calls the runtime here.
    finished = wait_for(execution["execution_id"], "COMPLETED")
    assert tool_statuses(finished) == [expected_tool_status]
    assert reports_received() == before + expected_reports
