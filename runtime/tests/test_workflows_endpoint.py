import json
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
EXAMPLE = json.loads((Path(__file__).parent.parent / "examples" / "research.json").read_text())


def test_validate_accepts_example():
    response = client.post("/runtime/workflows/validate", json=EXAMPLE)

    assert response.status_code == 200
    assert response.json() == {"valid": True, "errors": []}


def test_validate_reports_errors():
    broken = {**EXAMPLE, "edges": EXAMPLE["edges"][:-1]}

    body = client.post("/runtime/workflows/validate", json=broken).json()

    assert body["valid"] is False
    assert {"path": "nodes", "message": "Not reachable from START", "node_id": "end"} in body[
        "errors"
    ]


def test_schema_uses_camel_case():
    schema = client.get("/runtime/workflows/schema").json()

    assert "workflowId" in schema["properties"]
    assert "payloadKeys" in schema["$defs"]["HttpToolConfig"]["properties"]
