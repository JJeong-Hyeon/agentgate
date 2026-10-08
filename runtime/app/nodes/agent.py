"""Tool-calling agent as a LangGraph sub-flow. Every tool call the model makes is governed
by AgentGate on behalf of the agent, with its definition's tool permissions.

    <name>             start: system prompt + rendered user prompt
      ↓
    <name>.think       model turn (tools bound)
      ├─ no tool calls → state[<name>] = answer → next nodes
      └─ tool calls    → <name>.gate
    <name>.gate        first unanswered call: ask AgentGate (once)
      ├─ ALLOWED            → <name>.execute
      ├─ APPROVAL_REQUIRED  → <name>.approval (interrupt until resumed)
      │                          ├─ APPROVED → <name>.execute
      │                          └─ REJECTED → tell the model → <name>.gate
      ├─ BLOCKED / FAILED   → tell the model → <name>.gate
      └─ none left          → <name>.think
    <name>.execute     run the tool, give the model its result → <name>.gate

Denied calls are answered with a tool message saying why, so the model can continue
without them. On the last allowed turn (maxSteps) the model gets no tools and must answer.

Tool calling is NATIVE (OpenAI function calling) or JSON for models without it: the tools
are described in the system prompt and a reply like {"tool": ..., "arguments": {...}} is a
call. Either way the conversation is kept in the native form; for JSON it is flattened into
plain messages when sent to the model.

With an output schema, the final answer must be JSON matching it; a non-matching answer gets
one more turn with the error, then fails the node. The answer is stored as compact JSON.

The conversation lives in state["agent_runs"][<name>]; gate results are stored there before
interrupting, because LangGraph re-runs an interrupted node from the top on resume.
"""

import json
import re
from collections.abc import Callable
from typing import Any

from jsonschema import Draft202012Validator
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.runnables import RunnableConfig
from langgraph.graph import StateGraph
from langgraph.types import Command, interrupt

from app.dsl.schema import AgentSpec, AgentToolSpec
from app.tools.base import GovernedTool, ToolResult

LlmFactory = Callable[[str | None, float | None], BaseChatModel]

FINAL_TURN_NOTE = (
    "You have used all your tool calls for this task. "
    "Answer now with what you have; do not request more tools."
)
JSON_TOOLS_PROTOCOL = """You can use these tools:
{tools}

To call a tool, reply with only a JSON object and nothing else:
{{"tool": "<tool name>", "arguments": {{<arguments matching the tool's parameters>}}}}
You will then get the tool's result. Call one tool per reply.
When you need no more tools, reply with your final answer instead of JSON."""
OUTPUT_SCHEMA_NOTE = """Your final answer must be only a JSON value matching this JSON Schema, \
with no other text:
{schema}"""
_MAX_REASON_CHARS = 1000


class AgentTool:
    """A tool as the model sees it (function name and JSON Schema) and as AgentGate governs it."""

    def __init__(self, function_name: str, spec: AgentToolSpec, tool: GovernedTool):
        self.function_name = function_name
        self.spec = spec
        self.tool = tool

    def definition(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.function_name,
                "description": self.spec.description or f"{self.spec.server}/{self.spec.tool}",
                "parameters": self.spec.input_schema or {"type": "object", "properties": {}},
            },
        }


def function_names(specs: list[AgentToolSpec]) -> list[str]:
    """OpenAI function names (^[A-Za-z0-9_-]{1,64}$) for `server/tool`, unique."""
    names: list[str] = []
    for spec in specs:
        base = re.sub(r"[^A-Za-z0-9_-]", "_", f"{spec.server}__{spec.tool}")[:60]
        name, n = base, 2
        while name in names:
            name, n = f"{base[:57]}_{n}", n + 1
        names.append(name)
    return names


def add_agent(
    graph: StateGraph,
    name: str,
    spec: AgentSpec,
    build_prompt: Callable[[dict], str],
    llm_factory: LlmFactory,
    tools: list[AgentTool],
    next_nodes: list[str],
    tool_calling: str = "NATIVE",
) -> None:
    think_node = f"{name}.think"
    gate_node = f"{name}.gate"
    approval_node = f"{name}.approval"
    execute_node = f"{name}.execute"
    by_name = {t.function_name: t for t in tools}
    definitions = [t.definition() for t in tools]
    json_calls = tool_calling == "JSON"
    system_prompt = build_system_prompt(spec, definitions, json_calls)
    validator = Draft202012Validator(spec.output_schema) if spec.output_schema else None

    def run_of(state: dict) -> dict:
        return state["agent_runs"][name]

    def save(run: dict, **changes) -> dict:
        return {"agent_runs": {name: {**run, **changes}}}

    def start(state: dict) -> Command:
        messages = [SystemMessage(system_prompt), HumanMessage(build_prompt(state))]
        return Command(
            update={"agent_runs": {name: {"messages": messages, "steps": 0, "pending": None}}},
            goto=think_node,
        )

    def think(state: dict) -> Command:
        run = run_of(state)
        steps = run["steps"] + 1
        # A repair turn (fixing the answer's format) is an answer turn too.
        answering = run.get("repairing", False)
        final_turn = answering or steps >= spec.max_steps
        messages = list(run["messages"])
        if definitions and final_turn and not answering:
            messages.append(HumanMessage(FINAL_TURN_NOTE))
        llm = llm_factory(spec.model, spec.temperature)
        offer_tools = bool(definitions) and not final_turn
        if json_calls:
            reply = llm.invoke(flatten(messages))
            if offer_tools:
                reply = AIMessage(
                    content=reply.content, tool_calls=parse_json_calls(reply.content, steps)
                )
        else:
            reply = (llm.bind_tools(definitions) if offer_tools else llm).invoke(messages)
        messages.append(reply)
        if reply.tool_calls and not final_turn:
            return Command(update=save(run, messages=messages, steps=steps), goto=gate_node)

        answer = str(reply.content).strip()
        if validator is not None:
            value, problem = check_output(answer, validator)
            if problem:
                if answering:
                    raise AgentOutputError(f"'{name}': final answer does not match: {problem}")
                messages.append(HumanMessage(repair_note(problem, spec.output_schema)))
                update = save(run, messages=messages, steps=steps, repairing=True)
                return Command(update=update, goto=think_node)
            answer = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        update = save(run, messages=messages, steps=steps, repairing=False)
        return Command(update={name: answer, **update}, goto=next_nodes)

    def answer(run: dict, call: dict, content: str, result: ToolResult) -> dict:
        message = ToolMessage(content=content, tool_call_id=call["id"], name=call["name"])
        update = save(run, messages=[*run["messages"], message], pending=None)
        return {**update, "tool_results": [result.model_dump()]}

    def gate(state: dict, config: RunnableConfig) -> Command:
        run = run_of(state)
        call = next_unanswered_call(run["messages"])
        if call is None:
            return Command(goto=think_node)
        tool = by_name.get(call["name"])
        if tool is None:
            result = ToolResult(
                tool=f"{name}:{call['name']}", status="FAILED", error="unknown tool"
            )
            content = f"There is no tool named '{call['name']}'."
            return Command(update=answer(run, call, content, result), goto=gate_node)

        execution_id = config.get("configurable", {}).get("thread_id")
        result = tool.tool.authorize(
            execution_id,
            agent_id=spec.agent_id,
            agent_version=spec.version,
            reason=approval_reason(spec, tool.spec, call["args"]),
        )
        pending = {"call": call, "result": result.model_dump()}
        if result.status == "ALLOWED":
            return Command(update=save(run, pending=pending), goto=execute_node)
        if result.status == "APPROVAL_REQUIRED":
            return Command(update=save(run, pending=pending), goto=approval_node)
        return Command(update=answer(run, call, denial(result), result), goto=gate_node)

    def await_approval(state: dict) -> Command:
        run = run_of(state)
        pending = run["pending"]
        result = ToolResult(**pending["result"])
        decision = interrupt({"tool": result.tool, "approval_id": result.approval_id})
        if decision.get("decision") == "APPROVED":
            return Command(goto=execute_node)
        rejected = result.model_copy(update={"status": "REJECTED"})
        update = answer(run, pending["call"], denial(rejected), rejected)
        return Command(update=update, goto=gate_node)

    def execute(state: dict) -> Command:
        run = run_of(state)
        call, authorized = run["pending"]["call"], ToolResult(**run["pending"]["result"])
        result = by_name[call["name"]].tool.execute(call["args"], authorized)
        if result.status == "EXECUTED":
            content = result.response_body or "(no output)"
        else:
            content = f"The tool failed: {result.error}"
        return Command(update=answer(run, call, content, result), goto=gate_node)

    graph.add_node(name, start, destinations=(think_node,))
    graph.add_node(think_node, think, destinations=(gate_node, think_node, *next_nodes))
    graph.add_node(
        gate_node, gate, destinations=(think_node, execute_node, approval_node, gate_node)
    )
    graph.add_node(approval_node, await_approval, destinations=(execute_node, gate_node))
    graph.add_node(execute_node, execute, destinations=(gate_node,))


def next_unanswered_call(messages: list[BaseMessage]) -> dict | None:
    """The first tool call of the latest model turn that has no tool message yet."""
    last_ai = next((m for m in reversed(messages) if isinstance(m, AIMessage)), None)
    if last_ai is None:
        return None
    answered = {m.tool_call_id for m in messages if isinstance(m, ToolMessage)}
    return next((c for c in last_ai.tool_calls if c["id"] not in answered), None)


def denial(result: ToolResult) -> str:
    """What the model is told when a call does not run."""
    if result.status == "REJECTED":
        return "A human approver rejected this call. Do not retry it; continue without it."
    if result.status == "BLOCKED":
        why = f" ({result.basis})" if result.basis else ""
        return f"Blocked by governance policy{why}. Do not retry it; continue without it."
    return f"The call could not be authorized: {result.error}. Continue without it."


def approval_reason(spec: AgentSpec, tool: AgentToolSpec, args: dict[str, Any]) -> str:
    """Shown to the approver: who wants to call what, with which arguments."""
    arguments = json.dumps(args, ensure_ascii=False, default=str)
    reason = (
        f"Agent '{spec.agent_id}' (v{spec.version}) wants to call "
        f"{tool.server}/{tool.tool} with {arguments}"
    )
    if len(reason) > _MAX_REASON_CHARS:
        reason = reason[: _MAX_REASON_CHARS - 1] + "…"
    return reason


class AgentOutputError(Exception):
    """The agent's final answer does not match its output schema, even after a repair turn."""


def build_system_prompt(spec: AgentSpec, definitions: list[dict], json_calls: bool) -> str:
    parts = [spec.system_prompt]
    if json_calls and definitions:
        described = "\n".join(
            f"- {d['function']['name']}: {d['function']['description']}\n"
            f"  parameters: {json.dumps(d['function']['parameters'], ensure_ascii=False)}"
            for d in definitions
        )
        parts.append(JSON_TOOLS_PROTOCOL.format(tools=described))
    if spec.output_schema:
        schema = json.dumps(spec.output_schema, ensure_ascii=False)
        parts.append(OUTPUT_SCHEMA_NOTE.format(schema=schema))
    return "\n\n".join(parts)


def flatten(messages: list[BaseMessage]) -> list[BaseMessage]:
    """The conversation for a model without function calling: its own calls as the text it
    wrote, tool results as user messages."""
    flat: list[BaseMessage] = []
    for m in messages:
        if isinstance(m, AIMessage):
            flat.append(AIMessage(content=m.content))
        elif isinstance(m, ToolMessage):
            flat.append(HumanMessage(f"Result of {m.name}:\n{m.content}"))
        else:
            flat.append(m)
    return flat


def extract_json(text: str) -> Any:
    """The first JSON object or array in `text` (code fences allowed); raises ValueError."""
    stripped = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip())
    starts = [i for i in (stripped.find("{"), stripped.find("[")) if i >= 0]
    if not starts:
        raise ValueError("no JSON found")
    value, _ = json.JSONDecoder().raw_decode(stripped[min(starts) :])
    return value


def parse_json_calls(content: Any, step: int) -> list[dict]:
    """Tool calls in a JSON-mode reply; none when the reply is not a call (a final answer)."""
    try:
        value = extract_json(str(content))
    except ValueError:
        return []
    items = value if isinstance(value, list) else [value]
    calls = []
    for i, item in enumerate(items):
        if not isinstance(item, dict) or not isinstance(item.get("tool"), str):
            return []
        arguments = item.get("arguments") or {}
        if not isinstance(arguments, dict):
            return []
        calls.append({"name": item["tool"], "args": arguments, "id": f"json-{step}-{i}"})
    return calls


def check_output(answer: str, validator: Draft202012Validator) -> tuple[Any, str | None]:
    """(parsed value, None) when the answer matches the schema, else (None, the problem)."""
    try:
        value = extract_json(answer)
    except ValueError as e:
        return None, f"not valid JSON ({e})"
    error = next(iter(validator.iter_errors(value)), None)
    if error is not None:
        where = "/".join(str(p) for p in error.absolute_path) or "the answer"
        return None, f"{where}: {error.message}"
    return value, None


def repair_note(problem: str, schema: dict) -> str:
    return (
        f"Your answer does not match the required format: {problem}\n"
        "Reply again with only a JSON value matching this JSON Schema:\n"
        f"{json.dumps(schema, ensure_ascii=False)}"
    )
