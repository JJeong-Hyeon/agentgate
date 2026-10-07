from fastapi import FastAPI

app = FastAPI(title="AgentGate Runtime")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}
