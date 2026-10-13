"""The console as a person uses it: sign in, run a workflow, decide on the approval it asks for.

Needs E2E_UI_URL (the console, e.g. the vite dev server) and Playwright's Chromium; skipped otherwise.
"""

import os
import uuid

import pytest

from test_governance_e2e import ADMIN, action_risk, admin, wait_for_execution  # noqa: F401

sync_api = pytest.importorskip("playwright.sync_api")
UI = os.environ.get("E2E_UI_URL")
pytestmark = pytest.mark.skipif(not UI, reason="E2E_UI_URL not set")


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        yield browser
        browser.close()


@pytest.fixture
def page(browser):
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    page.set_default_timeout(15000)
    problems: list[str] = []
    page.on("pageerror", lambda e: problems.append(f"pageerror: {e}"))
    page.on(
        "console",
        lambda m: m.type == "error" and "401" not in m.text and problems.append(m.text),
    )
    yield page
    page.close()
    assert problems == []


def sign_in(page, username: str, password: str) -> None:
    page.goto(UI)
    page.get_by_label("아이디").fill(username)
    page.get_by_label("비밀번호").fill(password)
    page.get_by_role("button", name="로그인").click()
    page.get_by_role("heading", name="대시보드").wait_for()


@pytest.fixture
def agent_workflow(admin, action_risk):  # noqa: F811
    """An agent whose `add` tool always needs approval, in a one-agent workflow."""
    action_risk("MCP:echo:add", "LOW")
    suffix = uuid.uuid4().hex[:8]
    agent_id = f"ui-agent-{suffix}"
    agent = admin.post("/api/v1/agents", json={"agentId": agent_id, "name": "UI Agent"}).json()
    admin.put(
        f"/api/v1/agents/{agent['id']}/definition",
        json={
            "systemPrompt": "You are a tool agent.",
            "tools": [{"server": "echo", "tool": "add", "permission": "APPROVAL"}],
        },
    ).raise_for_status()
    workflow_id = f"ui-e2e-{suffix}"
    admin.post(
        "/api/v1/workflows",
        json={
            "workflowId": workflow_id,
            "name": f"UI flow {suffix}",
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
    return {"workflowId": workflow_id, "name": f"UI flow {suffix}", "agentId": agent_id}


def wait_for_approval_panel(page, admin, execution_id: str):  # noqa: F811
    panel = page.get_by_role("region", name="승인 요청")
    try:
        panel.get_by_role("heading", name="승인이 필요합니다").wait_for(timeout=30000)
        # The request's details load after the panel appears.
        sync_api.expect(panel).to_contain_text("MCP:echo:add", timeout=15000)
    except (sync_api.TimeoutError, AssertionError):
        execution = admin.get(f"/api/v1/executions/{execution_id}").json()
        raise AssertionError(
            f"no approval panel; execution={execution}; page={page.inner_text('body')[:1500]!r}"
        ) from None
    return panel


def run_from_the_console(page, workflow: dict) -> str:
    page.get_by_role("link", name="Workflows").click()
    page.get_by_role("button", name=workflow["name"]).click()
    page.get_by_label("작업").fill("add")
    page.get_by_role("button", name="실행").click()
    page.wait_for_url("**/executions/*")
    return page.url.rsplit("/", 1)[1]


def test_a_person_approves_a_tool_call_in_the_console_and_the_run_continues(page, admin, agent_workflow):  # noqa: F811
    sign_in(page, *ADMIN)
    execution_id = run_from_the_console(page, agent_workflow)

    panel = wait_for_approval_panel(page, admin, execution_id)
    assert "MCP:echo:add" in panel.inner_text()
    assert agent_workflow["agentId"] in panel.inner_text()
    assert '"a": 2' in panel.inner_text()

    panel.get_by_role("button", name="승인").click()

    wait_for_execution(admin, execution_id, "COMPLETED", timeout=60)
    page.get_by_text("완료").first.wait_for()
    assert page.get_by_role("region", name="승인 요청").count() == 0


def test_a_person_rejects_a_tool_call_and_it_never_runs(page, admin, agent_workflow):  # noqa: F811
    sign_in(page, *ADMIN)
    execution_id = run_from_the_console(page, agent_workflow)
    panel = wait_for_approval_panel(page, admin, execution_id)

    panel.get_by_role("button", name="거절").click()

    finished = wait_for_execution(admin, execution_id, "COMPLETED", timeout=60)
    outputs = " ".join(n.get("output") or "" for n in finished["nodes"])
    assert "rejected" in outputs
    assert "EXECUTED" not in outputs
    [audit] = admin.get("/api/v1/audit-logs", params={"agentId": agent_workflow["agentId"]}).json()
    assert audit["basis"] == "TOOL_REQUIRES_APPROVAL"


def test_a_viewer_sees_the_request_but_cannot_decide(page, admin, agent_workflow):  # noqa: F811
    username = f"viewer-{uuid.uuid4().hex[:6]}"
    password = uuid.uuid4().hex
    created = admin.post(
        "/api/v1/users", json={"username": username, "password": password, "roles": ["VIEWER"]}
    )
    created.raise_for_status()
    started = admin.post(
        "/api/v1/executions", json={"workflowId": agent_workflow["workflowId"], "task": "add"}
    ).json()
    wait_for_execution(admin, started["executionId"], "WAITING_APPROVAL", timeout=60)
    try:
        sign_in(page, username, password)
        page.goto(f"{UI}/executions/{started['executionId']}")
        panel = wait_for_approval_panel(page, admin, started["executionId"])
        assert panel.get_by_role("button", name="승인").count() == 0
        assert panel.get_by_role("button", name="거절").count() == 0
    finally:
        admin.delete(f"/api/v1/users/{created.json()['id']}")
