"""Policy for which model providers an agent may use.

The default model server (LLM_BASE_URL) is the organisation's own, so using it needs no check.
An agent whose model names another provider ("gemini@gemini-flash-latest") sends its prompts
elsewhere, so before its first model call AgentGate evaluates the action "LLM:<provider>" for
that agent. It is decided by the same policies as any other action (an administrator blocks
"LLM:gemini" like any risky action) and lands in the audit log. Anything but ALLOWED stops the
agent: fail-closed, including when AgentGate cannot be reached.
"""

from app.config import get_settings
from app.dsl.schema import AgentSpec
from app.governance.agentgate_client import AgentGateClient, AgentGateError
from app.llm.gateway import _providers
from app.llm.providers import provider_name


class ModelNotAllowed(Exception):
    """The agent may not use the model provider it is configured with."""


def check_model_allowed(
    gate: AgentGateClient,
    spec: AgentSpec,
    execution_id: str | None,
    delegated_by: str | None = None,
) -> None:
    provider = provider_name(spec.model, _providers(get_settings().llm_providers_path))
    if provider is None:
        return
    action = f"LLM:{provider}"
    try:
        decision = gate.evaluate(
            action,
            target=f"llm://{provider}",
            execution_id=execution_id,
            reason=f"Agent '{spec.agent_id}' (v{spec.version}) wants to send its prompts to "
            f"model provider '{provider}' ({spec.model})",
            agent_id=spec.agent_id,
            agent_version=spec.version,
            delegated_by=delegated_by or None,
        )
    except AgentGateError as e:
        raise ModelNotAllowed(f"could not check model provider '{provider}': {e}") from e
    if decision.status != "ALLOWED":
        raise ModelNotAllowed(
            f"model provider '{provider}' is not allowed for agent '{spec.agent_id}' "
            f"({decision.status}{f', {decision.basis}' if decision.basis else ''})"
        )
