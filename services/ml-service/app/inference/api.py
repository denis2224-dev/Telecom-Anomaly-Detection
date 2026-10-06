"""Private inference endpoint for the packaged service models."""

from contextlib import asynccontextmanager
import os

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.inference.scoring import load, score
from app.inference.scoring import ROOT
from app.inference.sms_classifier import load_classifier, score as classifier_score

MODEL_VERSION = 'sms-supervised-v1-2'
MODEL_SHA256 = 'f3baf6be91d56c0a8054a9cd81e028774e3af9464d8124a81aa89180030c9f19'
THRESHOLD = .55


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.models = {service: load(service) for service in ("VOLTE", "SMS")}
    app.state.sms_shadow_enabled = os.getenv('ML_SMS_SHADOW_ENABLED', 'false').lower() == 'true'
    app.state.sms_classifier = None
    if app.state.sms_shadow_enabled:
        loaded = load_classifier(os.getenv('ML_SMS_CANDIDATE_PATH', str(
            ROOT / 'services/ml-service/candidate-models' / MODEL_VERSION)))
        manifest = loaded[0]
        if (manifest['modelVersion'] != MODEL_VERSION or manifest['modelSha256'] != MODEL_SHA256
                or manifest['threshold'] != THRESHOLD):
            raise ValueError('Shadow candidate must match the frozen version, artifact and cutoff')
        app.state.sms_classifier = loaded
    yield


app = FastAPI(title="Service assurance inference", lifespan=lifespan)


@app.get("/health/ready")
def ready():
    if app.state.sms_shadow_enabled and app.state.sms_classifier is None:
        return JSONResponse(status_code=503, content={'status': 'DOWN'})
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


def shadow_failure(status, code):
    return JSONResponse(status_code=code, content=dict(schemaVersion=1, mlStatus=status,
        classifierScore=None, detection=None, threshold=THRESHOLD, modelVersion=None, modelSha256=MODEL_SHA256))


@app.post('/internal/inference/sms-classifier')
def infer_sms_classifier(window: dict):
    if not app.state.sms_shadow_enabled:
        return shadow_failure('DISABLED', 503)
    if app.state.sms_classifier is None:
        return shadow_failure('UNAVAILABLE', 503)
    try:
        result = classifier_score(window, app.state.sms_classifier)
        return dict(result, schemaVersion=1, mlStatus='OK', threshold=THRESHOLD, modelSha256=MODEL_SHA256)
    except ValueError:
        return shadow_failure('INSUFFICIENT_DATA', 422)
    except Exception:
        return shadow_failure('UNAVAILABLE', 503)
