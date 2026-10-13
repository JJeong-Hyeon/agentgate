"""A fingerprint of what a governed tool call will do, so an approval can be tied to it."""

import hashlib
import json
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
