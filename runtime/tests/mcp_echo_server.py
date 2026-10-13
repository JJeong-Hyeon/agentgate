"""Tiny stdio MCP server for tests (and the end-to-end test)."""

from mcp.server.mcpserver import MCPServer
from mcp.types import ToolAnnotations

server = MCPServer("echo")


@server.tool()
def echo(text: str) -> str:
    """Return the text prefixed with 'echo: '."""
    return f"echo: {text}"


@server.tool(annotations=ToolAnnotations(readOnlyHint=True))
def add(a: int, b: int) -> str:
    """Add two integers."""
    return str(a + b)


@server.tool()
def fail() -> str:
    """Always fails."""
    raise ValueError("boom")


@server.tool()
def count(path: str) -> str:
    """Append a line to `path` and return how many calls that makes (each call is its own
    process, so a file is how tests observe how many times this actually ran)."""
    with open(path, "a") as f:
        f.write("1\n")
    with open(path) as f:
        return str(sum(1 for _ in f))


if __name__ == "__main__":
    server.run()
