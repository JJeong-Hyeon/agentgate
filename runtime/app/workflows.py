from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel

from app.dsl import ValidationIssue, Workflow, validate_workflow

router = APIRouter(prefix="/runtime/workflows", tags=["workflows"])


class ValidationResult(BaseModel):
    valid: bool
    errors: list[ValidationIssue]


@router.post("/validate")
def validate(body: dict[str, Any]) -> ValidationResult:
    _, issues = validate_workflow(body)
    return ValidationResult(valid=not issues, errors=issues)


@router.get("/schema")
def schema() -> dict[str, Any]:
    return Workflow.model_json_schema(by_alias=True)
