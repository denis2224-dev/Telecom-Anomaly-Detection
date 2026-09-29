"""Private inference endpoint for the packaged service models."""

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.inference.scoring import load, score


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.models = {service: load(service) for service in ("VOLTE", "SMS")}
    yield


app = FastAPI(title="Service assurance inference", lifespan=lifespan)


@app.get("/health/ready")
def ready():
    return {"status": "UP"}


@app.post("/internal/inference")
def infer(window: dict):
    service = window.get("service")
    if not isinstance(service, str) or service not in app.state.models:
        return JSONResponse(status_code=422, content={"mlStatus": "INSUFFICIENT_DATA",
                                                    "modelVersion": None, "anomalyRank": None})
    try:
        return score(window, app.state.models[service])
    except ValueError:
        return JSONResponse(status_code=422, content={"mlStatus": "INSUFFICIENT_DATA",
                                                    "modelVersion": None, "anomalyRank": None})
