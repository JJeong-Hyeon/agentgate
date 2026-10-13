"""A fingerprint of what a governed tool call will do, so an approval can be tied to it."""

import hashlib
import json
import re
from typing import Any


def payload_hash(action: str, target: str | None, payload: Any) -> str:
    """SHA-256 over the action, target and the arguments exactly as they will be sent."""
    canonical = json.dumps(
        {"action": action, "target": target, "payload": payload},
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
        default=str,
    )
    return hashlib.sha256(canonical.encode()).hexdigest()


_SECRET_KEY = re.compile(r"pass(word|wd)|secret|token|api[-_]?key|authorization|credential", re.I)
MAX_PREVIEW_CHARS = 20000


def _masked(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: "***" if _SECRET_KEY.search(str(k)) else _masked(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_masked(v) for v in value]
    return value


def payload_preview(payload: Any) -> str:
    """The arguments as an approver should read them: indented, secrets masked, bounded."""
    text = json.dumps(_masked(payload), ensure_ascii=False, indent=2, default=str)
    if len(text) > MAX_PREVIEW_CHARS:
        return text[:MAX_PREVIEW_CHARS] + "\n… (truncated)"
    return text
