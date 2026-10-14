"""Governance evaluation: run the cases in cases.json and report how safely the runtime behaved.

    python -m evals.run                 # table to stdout, report to evals/report.json
    python -m evals.run --summary       # the same as a Markdown table (for a CI job summary)

Each case runs a scripted agent through the real graph (agent node, gate, approval, delegation,
payload binding, model policy). Only AgentGate's answers and the model's replies are faked, so
what is measured is the runtime's own enforcement, not a model's judgement. The side-effecting
tool is the test MCP server's `count`, which appends a line to a file: the number of lines is how
many times the effect really happened.

Exit status is 1 if any case fails. The headline number is `unsafe executions`, side effects that
happened though the case says they must not (or more often than allowed): it must be 0.
"""

import json
import sys
import tempfile
from dataclasses import asdict, dataclass, field
from pathlib import Path

import httpx
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

CASES = Path(__file__).with_name("cases.json")
REPORT = Path(__file__).with_name("report.json")
TAMPERED_HASH = "0" * 64


@dataclass
class Outcome:
    id: str
    category: str
    why: str
    expected: dict
    executed: int = 0
    paused: bool = False
    error: str | None = None
    problems: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not self.problems

    @property
    def unsafe(self) -> int:
        return max(self.executed - int(self.expected.get("executed", 0)), 0)

    @property
    def missed(self) -> int:
        return max(int(self.expected.get("executed", 0)) - self.executed, 0)


def _tools(items: list[dict]) -> list[dict]:
    return [{"server": "local", "tool": "count", **item} for item in items]


def _llm_script(steps: list[dict], counter: Path):
    from tests.fake_llm import ScriptedChatModel, calls

    replies = []
    for step in steps:
        if "say" in step:
            replies.append(step["say"])
        else:
            replies.append(
                calls(
                    *[
                        (name, json.loads(json.dumps(args).replace("$COUNTER", counter.as_posix())))
                        for name, args in step["call"]
                    ]
                )
            )
    return ScriptedChatModel.of(*replies)


def run_case(case: dict, workdir: Path) -> Outcome:
    from tests.test_agent_node import PolicyGate
    from tests.test_delegation import agent, build, team

    setup = case.get("setup", {})
    outcome = Outcome(case["id"], case["category"], case["why"], case.get("expect", {}))
    counter = workdir / f"{case['id']}.txt"
    llm = _llm_script(case["llm"], counter)

    lead = agent("lead", tools=_tools(setup.get("lead_tools", [])))
    extra = {}
    if setup.get("research_tools") is not None:
        extra["lead"] = agent(
            "lead",
            delegates=[{"agentId": "research", "permission": "AUTO"}],
            description="Leads the team",
        )
        research = {"tools": _tools(setup["research_tools"])}
        if setup.get("research_model"):
            research["model"] = setup["research_model"]
        extra["research"] = agent("research", description="Finds things out", **research)
    else:
        extra["lead"] = lead
    gate = PolicyGate(**setup.get("gate", {}))
    if setup.get("gate_down"):
        gate = lambda request: httpx.Response(500)  # noqa: E731 - AgentGate is down
    graph, state, _ = build(team(**extra), llm, gate, InMemorySaver())
    config = {"configurable": {"thread_id": f"eval-{case['id']}"}}

    try:
        graph.invoke(state, config)
        outcome.paused = bool(graph.get_state(config).interrupts)
        resume = case.get("resume")
        if resume and outcome.paused:
            decision = {"decision": "REJECTED" if resume == "REJECTED" else "APPROVED"}
            if resume == "TAMPERED":
                decision["payloadHash"] = TAMPERED_HASH
            graph.invoke(Command(resume=decision), config)
    except Exception as e:  # noqa: BLE001 - a run that stops with an error is an outcome too
        outcome.error = type(e).__name__
    outcome.executed = len(counter.read_text().splitlines()) if counter.exists() else 0
    _check(outcome)
    return outcome


def _check(outcome: Outcome) -> None:
    expected = outcome.expected
    if outcome.unsafe:
        outcome.problems.append(f"UNSAFE: ran {outcome.executed}x, at most {expected['executed']}")
    if outcome.missed:
        outcome.problems.append(
            f"did not run: ran {outcome.executed}x, expected {expected['executed']}"
        )
    if "paused" in expected and outcome.paused != expected["paused"]:
        outcome.problems.append(f"paused={outcome.paused}, expected {expected['paused']}")
    if "error" in expected and bool(outcome.error) != expected["error"]:
        outcome.problems.append(f"error={outcome.error}, expected error={expected['error']}")


def run_all() -> list[Outcome]:
    _allow_external_provider()
    cases = json.loads(CASES.read_text(encoding="utf-8"))["cases"]
    with tempfile.TemporaryDirectory() as tmp:
        return [run_case(case, Path(tmp)) for case in cases]


def _allow_external_provider() -> None:
    """Registers a provider named "gemini" (never contacted), so the model-policy cases can
    refer to a non-default provider."""
    from app.config import Settings
    from app.llm import policy

    path = Path(tempfile.mkdtemp()) / "providers.json"
    path.write_text(json.dumps({"gemini": {"base_url": "http://gemini.invalid/v1"}}))
    settings = Settings(_env_file=None, llm_providers_path=str(path))
    policy.get_settings = lambda: settings


def summarize(outcomes: list[Outcome]) -> dict:
    return {
        "cases": len(outcomes),
        "passed": sum(o.passed for o in outcomes),
        "unsafe_executions": sum(o.unsafe for o in outcomes),
        "missed_executions": sum(o.missed for o in outcomes),
        "by_category": {
            category: {
                "cases": sum(o.category == category for o in outcomes),
                "passed": sum(o.category == category and o.passed for o in outcomes),
            }
            for category in dict.fromkeys(o.category for o in outcomes)
        },
    }


def render(outcomes: list[Outcome], summary: dict, markdown: bool) -> str:
    lines = []
    if markdown:
        lines += [
            "### Governance evaluation",
            "",
            "| case | category | result | ran | detail |",
            "|---|---|---|---|---|",
        ]
        for o in outcomes:
            detail = "; ".join(o.problems) or o.why
            result = "pass" if o.passed else "**FAIL**"
            lines.append(f"| {o.id} | {o.category} | {result} | {o.executed} | {detail} |")
        lines.append("")
        lines.append(
            f"**{summary['passed']}/{summary['cases']} passed, "
            f"unsafe executions: {summary['unsafe_executions']}, "
            f"missed executions: {summary['missed_executions']}**"
        )
    else:
        for o in outcomes:
            lines.append(
                f"{'PASS' if o.passed else 'FAIL'}  {o.category:<10} {o.id}  ran={o.executed}"
            )
            lines += [f"      {problem}" for problem in o.problems]
        lines.append("")
        lines.append(
            f"{summary['passed']}/{summary['cases']} passed | "
            f"unsafe executions: {summary['unsafe_executions']} | "
            f"missed executions: {summary['missed_executions']}"
        )
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    outcomes = run_all()
    summary = summarize(outcomes)
    REPORT.write_text(
        json.dumps(
            {"summary": summary, "cases": [asdict(o) | {"passed": o.passed} for o in outcomes]},
            indent=2,
        ),
        encoding="utf-8",
    )
    print(render(outcomes, summary, markdown="--summary" in argv))
    return 0 if summary["passed"] == summary["cases"] and summary["unsafe_executions"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
