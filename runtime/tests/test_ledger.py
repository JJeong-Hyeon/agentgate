from app.config import Settings
from app.tools import ledger
from app.tools.base import ToolResult


def _settings(**overrides) -> Settings:
    return Settings(_env_file=None, **overrides)


def test_without_a_database_begin_always_claims_and_finish_is_a_no_op():
    settings = _settings(runtime_database_url="")

    assert ledger.begin(settings, "exec-1:node", "notes:save") is None

    # No database configured: finish() must not try to reach one.
    ledger.finish(
        settings, "exec-1:node", ToolResult(tool="notes:save", status="EXECUTED", duration_ms=5)
    )
