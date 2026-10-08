"""Workflow DSL — the contract between the visual builder and the runtime.

JSON uses camelCase. Each node that produces output writes it to the execution state
under its own id; prompts reference earlier outputs as `{nodeId}` and the input as `{task}`.

Parallelism is expressed by several unlabeled edges leaving one node, and repetition by
a cycle through a REVIEWER (bounded by its maxRevisions) — there are no PARALLEL/LOOP nodes.
"""

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

NODE_ID_PATTERN = r"^[A-Za-z][A-Za-z0-9_]{0,63}$"
# State keys the runtime owns; node ids must not shadow them.
RESERVED_IDS = {"task", "workflow", "tool_results", "pending_tool", "revisions", "agent_runs"}


class DslModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class Position(DslModel):
    x: float
    y: float


class NodeBase(DslModel):
    id: str = Field(pattern=NODE_ID_PATTERN)
    label: str | None = None
    # Builder layout only; ignored by the runtime.
    position: Position | None = None


class StartNode(NodeBase):
    type: Literal["START"]


class EndNode(NodeBase):
    type: Literal["END"]


class LlmConfig(DslModel):
    prompt: str = Field(min_length=1)
    system: str | None = None
    model: str | None = None
    temperature: float | None = Field(default=None, ge=0, le=2)


class LlmNode(NodeBase):
    """Single LLM call."""

    type: Literal["LLM"]
    config: LlmConfig


class AgentConfig(LlmConfig):
    # Registered agent to run; its definition (model, system prompt, tools, permissions) is
    # resolved into Workflow.agents when the execution starts. Without it the node is a single
    # LLM call configured inline by system / model / temperature.
    agent_id: str | None = Field(default=None, min_length=1, max_length=255)
    # Pins a definition version; None → the latest when the execution starts.
    agent_version: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def definition_owns_llm_settings(self) -> "AgentConfig":
        if self.agent_id is None:
            if self.agent_version is not None:
                raise ValueError("agentVersion needs agentId")
            return self
        inline = [f for f in ("system", "model", "temperature") if getattr(self, f) is not None]
        if inline:
            raise ValueError(
                f"{', '.join(inline)} come from the agent definition when agentId is set"
            )
        return self


class AgentNode(NodeBase):
    """With config.agentId: a tool-calling agent whose every tool call is governed by
    AgentGate. Without it: an LLM step with an agent role."""

    type: Literal["AGENT"]
    config: AgentConfig


class RouterConfig(LlmConfig):
    routes: list[str] = Field(min_length=2)


class RouterNode(NodeBase):
    """LLM picks one of `routes`; each route is the label of an outgoing edge."""

    type: Literal["ROUTER"]
    config: RouterConfig


class ReviewerConfig(LlmConfig):
    max_revisions: int = Field(default=1, ge=0, le=10)


class ReviewerNode(NodeBase):
    """LLM verdict with outgoing edges labeled APPROVE and REVISE."""

    type: Literal["REVIEWER"]
    config: ReviewerConfig


class ConditionConfig(DslModel):
    key: str = Field(pattern=NODE_ID_PATTERN)
    # edge label → value of state[key] that selects it
    cases: dict[str, str] = Field(min_length=1)
    default: str


class ConditionNode(NodeBase):
    """Deterministic branch on a state value; outgoing edges are labeled by case or default."""

    type: Literal["CONDITION"]
    config: ConditionConfig


class HttpToolConfig(DslModel):
    action: str = Field(min_length=1)
    url: str = Field(pattern=r"^https?://")
    method: Literal["GET", "POST", "PUT", "PATCH", "DELETE"] = "POST"
    labels: list[str] = []
    # State keys sent as the JSON body.
    payload_keys: list[str] = []


class HttpToolNode(NodeBase):
    """HTTP call governed by AgentGate (ALLOWED / APPROVAL_REQUIRED / BLOCKED)."""

    type: Literal["HTTP_TOOL"]
    config: HttpToolConfig


class McpToolConfig(DslModel):
    # Server name from the runtime's MCP config (MCP_CONFIG_PATH).
    server: str = Field(min_length=1)
    tool: str = Field(min_length=1)
    # Tool arguments; each value is a template like prompts ({task}, {nodeId}).
    arguments: dict[str, str] = {}
    # Action name AgentGate evaluates; default "MCP:<server>:<tool>" lets a policy target one tool.
    action: str | None = None
    labels: list[str] = []


class McpToolNode(NodeBase):
    """MCP tool call governed by AgentGate (ALLOWED / APPROVAL_REQUIRED / BLOCKED)."""

    type: Literal["MCP_TOOL"]
    config: McpToolConfig


class ApprovalConfig(DslModel):
    # Shown to the approver; may use {task} / {nodeId} like prompts.
    message: str | None = None
    # Action name AgentGate evaluates and audits; a BLOCKED policy for it still blocks.
    action: str = Field(default="HUMAN_APPROVAL", min_length=1)
    labels: list[str] = []


class ApprovalNode(NodeBase):
    """Explicit human approval step, regardless of risk. Rejection ends the execution."""

    type: Literal["APPROVAL"]
    config: ApprovalConfig = ApprovalConfig()


Node = Annotated[
    StartNode
    | EndNode
    | LlmNode
    | AgentNode
    | RouterNode
    | ReviewerNode
    | ConditionNode
    | HttpToolNode
    | McpToolNode
    | ApprovalNode,
    Field(discriminator="type"),
]

# Node types whose output is written to state[node.id].
OUTPUT_TYPES = {"LLM", "AGENT", "ROUTER", "REVIEWER", "HTTP_TOOL", "MCP_TOOL", "APPROVAL"}


class SnapshotModel(BaseModel):
    """Data AgentGate resolves at execution start; unknown fields are ignored so AgentGate
    may add some before the runtime knows them."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


class AgentToolSpec(SnapshotModel):
    server: str = Field(min_length=1)
    tool: str = Field(min_length=1)
    permission: Literal["AUTO", "APPROVAL", "BLOCKED"]
    labels: list[str] = []
    # Filled from the runtime's tool catalog when the execution starts, so a resumed
    # execution does not depend on the MCP server being reachable to rebuild its graph.
    description: str | None = None
    input_schema: dict[str, Any] | None = None

    @property
    def action(self) -> str:
        return f"MCP:{self.server}:{self.tool}"


class AgentSpec(SnapshotModel):
    """One version of an agent definition (see AgentGate's AgentDefinition)."""

    agent_id: str
    version: int = Field(ge=1)
    description: str | None = None
    model: str | None = None
    temperature: float | None = Field(default=None, ge=0, le=2)
    system_prompt: str = Field(min_length=1)
    tools: list[AgentToolSpec] = []
    max_steps: int = Field(default=8, ge=1, le=50)
    output_schema: dict[str, Any] | None = None


class Edge(DslModel):
    source: str
    target: str
    label: str | None = None


class Workflow(DslModel):
    schema_version: Literal[1] = 1
    workflow_id: str = Field(pattern=r"^[a-z0-9][a-z0-9-]{0,63}$")
    version: int = Field(default=1, ge=1)
    name: str | None = None
    nodes: list[Node] = Field(min_length=2)
    edges: list[Edge]
    # agentId → the definition AGENT nodes with that agentId run; set when an execution starts.
    agents: dict[str, AgentSpec] = {}
