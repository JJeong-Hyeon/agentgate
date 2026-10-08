"""Chat model that answers from a script and records what it was asked (with bound tools)."""

from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult


def calls(*tool_calls: tuple[str, dict]) -> AIMessage:
    """A model turn that calls tools: calls(("echo__echo", {"text": "hi"}), ...)."""
    return AIMessage(
        content="",
        tool_calls=[
            {"name": name, "args": args, "id": f"call-{i}-{name}"}
            for i, (name, args) in enumerate(tool_calls)
        ],
    )


class ScriptedChatModel(BaseChatModel):
    # Shared by bound copies, so the script advances across bind_tools().
    script: list[AIMessage]
    log: list[dict[str, Any]]
    tools: list[dict] | None = None

    @classmethod
    def of(cls, *replies: AIMessage | str) -> "ScriptedChatModel":
        script = [r if isinstance(r, AIMessage) else AIMessage(content=r) for r in replies]
        return cls(script=script, log=[])

    @property
    def _llm_type(self) -> str:
        return "scripted"

    def bind_tools(self, tools, **kwargs) -> "ScriptedChatModel":
        return self.model_copy(update={"tools": list(tools)})

    def _generate(self, messages: list[BaseMessage], stop=None, run_manager=None, **kwargs):
        self.log.append({"messages": list(messages), "tools": self.tools})
        return ChatResult(generations=[ChatGeneration(message=self.script.pop(0))])
