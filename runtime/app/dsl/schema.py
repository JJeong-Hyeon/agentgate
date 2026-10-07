"""Workflow DSL — the contract between the visual builder and the runtime.

JSON uses camelCase. Each node that produces output writes it to the execution state
under its own id; prompts reference earlier outputs as `{nodeId}` and the input as `{task}`.

Parallelism is expressed by several unlabeled edges leaving one node, and repetition by
a cycle through a REVIEWER (bounded by its maxRevisions) — there are no PARALLEL/LOOP nodes.
"""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

NODE_ID_PATTERN = r"^[A-Za-z][A-Za-z0-9_]{0,63}$"
# State keys the runtime owns; node ids must not shadow them.
RESERVED_IDS = {"task", "workflow", "tool_results", "pending_tool", "revisions"}


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


class AgentNode(NodeBase):
    """LLM step with an agent role. Tool calling will be added to this node type."""

    type: Literal["AGENT"]
    config: LlmConfig


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


class ApprovalConfig(DslModel):
    message: str | None = None


class ApprovalNode(NodeBase):
    """Explicit human approval step, regardless of risk."""

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
    | ApprovalNode,
    Field(discriminator="type"),
]

# Node types whose output is written to state[node.id].
OUTPUT_TYPES = {"LLM", "AGENT", "ROUTER", "REVIEWER", "HTTP_TOOL"}


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
