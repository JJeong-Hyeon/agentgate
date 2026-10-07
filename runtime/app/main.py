from typing import Annotated

from fastapi import Depends, FastAPI, Response, status

from app.config import Settings, get_settings
from app.llm import LlmHealth, check_llm_health

app = FastAPI(title="AgentGate Runtime")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/llm/health")
async def llm_health(
    response: Response, settings: Annotated[Settings, Depends(get_settings)]
) -> LlmHealth:
    result = await check_llm_health(settings)
    if result.status != "UP":
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return result
