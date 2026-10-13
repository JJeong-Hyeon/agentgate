"""Durable record of external tool calls (MCP and HTTP alike), keyed by the idempotency key
`nodes/tool.py` sets on every call. Without this, a runtime restart, a duplicate resume
notification, or a crash between a tool succeeding and its checkpoint being saved can run a
non-idempotent external call twice (see docs/AgentGate_CODE_REVIEW.md, "실행 중 장애와 멱등성").

`begin()` claims a key before the call runs; `finish()` records its outcome. A key already on
record is never run again: a finished call is replayed from the ledger, and a call that was
`STARTED` but never finished (the runtime died mid-call; its real-world outcome is unknown) is
replayed as FAILED rather than risk repeating a side effect that may have already happened.
"""

from functools import lru_cache
from typing import TYPE_CHECKING, Any

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from app.config import Settings

if TYPE_CHECKING:
    from app.tools.base import ToolResult

_TERMINAL = {"EXECUTED", "FAILED", "BLOCKED", "REJECTED"}

_SCHEMA = """
create table if not exists tool_call_ledger (
    idempotency_key text primary key,
    tool text not null,
    status text not null,
    response_status int,
    response_body text,
    error text,
    duration_ms int,
    created_at timestamptz not null default now(),
    finished_at timestamptz
)
"""


@lru_cache
def _pool(database_url: str) -> ConnectionPool:
    pool = ConnectionPool(
        database_url,
        open=True,
        max_size=5,
        kwargs={"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row},
    )
    with pool.connection() as conn:
        conn.execute(_SCHEMA)
    return pool


def begin(settings: Settings, key: str, tool: str) -> "ToolResult | None":
    """None: `key` is newly claimed, go ahead and run the call. Otherwise the result already on
    record for it — replay it instead of running the call again."""
    if not settings.runtime_database_url:
        return None
    with _pool(settings.runtime_database_url).connection() as conn:
        claimed = conn.execute(
            "insert into tool_call_ledger (idempotency_key, tool, status) "
            "values (%s, %s, 'STARTED') on conflict (idempotency_key) do nothing "
            "returning idempotency_key",
            (key, tool),
        ).fetchone()
        if claimed:
            return None
        row = conn.execute(
            "select status, response_status, response_body, error, duration_ms "
            "from tool_call_ledger where idempotency_key = %s",
            (key,),
        ).fetchone()
    from app.tools.base import ToolResult

    if row is None:  # raced with another begin() that has not inserted yet
        return None
    if row["status"] not in _TERMINAL:
        return ToolResult(
            tool=tool,
            status="FAILED",
            error="a previous attempt at this call did not finish; its outcome is unknown, so "
            "it was not repeated",
        )
    return ToolResult(
        tool=tool,
        status=row["status"],
        response_status=row["response_status"],
        response_body=row["response_body"],
        error=row["error"],
        duration_ms=row["duration_ms"],
    )


def finish(settings: Settings, key: str, result: Any) -> None:
    if not settings.runtime_database_url:
        return
    with _pool(settings.runtime_database_url).connection() as conn:
        conn.execute(
            "update tool_call_ledger set status = %s, response_status = %s, response_body = %s, "
            "error = %s, duration_ms = %s, finished_at = now() where idempotency_key = %s",
            (
                result.status,
                result.response_status,
                result.response_body,
                result.error,
                result.duration_ms,
                key,
            ),
        )
