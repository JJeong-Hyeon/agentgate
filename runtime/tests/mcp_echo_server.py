"""Tiny stdio MCP server for tests (and the end-to-end test)."""

from mcp.server.mcpserver import MCPServer

server = MCPServer("echo")


@server.tool()
def echo(text: str) -> str:
    """Return the text prefixed with 'echo: '."""
    return f"echo: {text}"


@server.tool()
def fail() -> str:
    """Always fails."""
    raise ValueError("boom")


if __name__ == "__main__":
    server.run()
