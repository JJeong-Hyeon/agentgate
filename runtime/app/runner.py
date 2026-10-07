"""Chooses the graph for an execution: the built-in research graph, or one compiled from
the Workflow DSL the execution was started with (kept in its state under `workflow`)."""

import hashlib
import json
from collections import OrderedDict
from typing import Any

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph.state import CompiledStateGraph

from app.dsl import Workflow
from app.dsl.compiler import WorkflowCompiler

_CACHE_SIZE = 32


class WorkflowRunner:
    def __init__(
        self,
        default_graph: CompiledStateGraph,
        compiler: WorkflowCompiler,
        checkpointer: BaseCheckpointSaver,
    ):
        self.default_graph = default_graph
        self._compiler = compiler
        self._checkpointer = checkpointer
        self._compiled: OrderedDict[str, CompiledStateGraph] = OrderedDict()

    def graph_for_workflow(self, workflow: Workflow) -> CompiledStateGraph:
        dsl = workflow.model_dump(mode="json", by_alias=True)
        key = hashlib.sha256(json.dumps(dsl, sort_keys=True).encode()).hexdigest()
        if key in self._compiled:
            self._compiled.move_to_end(key)
            return self._compiled[key]
        graph = self._compiler.compile(workflow, self._checkpointer)
        self._compiled[key] = graph
        if len(self._compiled) > _CACHE_SIZE:
            self._compiled.popitem(last=False)
        return graph

    def exists(self, execution_id: str) -> bool:
        return (
            self._checkpointer.get_tuple({"configurable": {"thread_id": execution_id}}) is not None
        )

    def graph_for_execution(self, execution_id: str) -> CompiledStateGraph:
        saved = self._checkpointer.get_tuple({"configurable": {"thread_id": execution_id}})
        dsl: Any = saved.checkpoint["channel_values"].get("workflow") if saved else None
        if not dsl:
            return self.default_graph
        return self.graph_for_workflow(Workflow.model_validate(dsl))
