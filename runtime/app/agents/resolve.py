"""Prepares the agents a workflow runs, when an execution starts."""

from app.dsl import Workflow
from app.dsl.compiler import CompileError
from app.tools.catalog import ToolCatalog


def attach_tool_schemas(workflow: Workflow, catalog: ToolCatalog) -> Workflow:
    """Copy each agent tool's description and argument schema from the catalog into the
    workflow, which is kept in the execution's state; a resumed execution then rebuilds its
    graph without needing the MCP servers. BLOCKED tools are never offered, so they are skipped.

    Raises CompileError when a tool cannot be found (unknown, or its server is unreachable).
    """
    agents = {}
    for agent_id, spec in workflow.agents.items():
        tools = []
        for tool in spec.tools:
            if tool.permission == "BLOCKED" or tool.input_schema is not None:
                tools.append(tool)
                continue
            info = catalog.tool(tool.server, tool.tool)
            if info is None:
                raise CompileError(
                    f"agent '{agent_id}': tool {tool.server}/{tool.tool} is not available"
                )
            tools.append(
                tool.model_copy(
                    update={"description": info.description, "input_schema": info.input_schema}
                )
            )
        agents[agent_id] = spec.model_copy(update={"tools": tools})
    return workflow.model_copy(update={"agents": agents})
