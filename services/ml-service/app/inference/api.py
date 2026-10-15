"""Private inference endpoint for the packaged service models."""

from contextlib import asynccontextmanager
import os
from threading import BoundedSemaphore

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from fastapi.exception_handlers import request_validation_exception_handler

from app.inference.scoring import load, score
from app.inference.scoring import ROOT
from app.inference.sms_classifier import load_classifier, score as classifier_score
from app.inference.trace_link_scoring import load as load_trace_link, score as trace_link_score, DEFAULT as TRACE_LINK_DEFAULT

MODEL_VERSION = 'sms-supervised-v1-2'
MODEL_SHA256 = 'f3baf6be91d56c0a8054a9cd81e028774e3af9464d8124a81aa89180030c9f19'
THRESHOLD = .55


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.models = {service: load(service) for service in ("VOLTE", "SMS")}
    app.state.sms_shadow_enabled = os.getenv('ML_SMS_SHADOW_ENABLED', 'false').lower() == 'true'
    app.state.sms_classifier = None
    app.state.sms_prediction_slots = BoundedSemaphore(8)
    app.state.trace_link_enabled = os.getenv('ML_TRACE_LINK_SHADOW_ENABLED', 'false').lower() == 'true'
    app.state.trace_link_slots = BoundedSemaphore(8)
    app.state.trace_link_candidate = None
    if app.state.trace_link_enabled:
        try:
            app.state.trace_link_candidate = load_trace_link(os.getenv('ML_TRACE_LINK_CANDIDATE_PATH', str(TRACE_LINK_DEFAULT)))
        except Exception:
            # This optional capability cannot take legacy inference down.
            app.state.trace_link_candidate = None
    if app.state.sms_shadow_enabled:
        loaded = load_classifier(os.getenv('ML_SMS_CANDIDATE_PATH', str(
            ROOT / 'services/ml-service/candidate-models' / MODEL_VERSION)), expected=(MODEL_VERSION, MODEL_SHA256, THRESHOLD))
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


@app.exception_handler(RequestValidationError)
async def invalid_request(request, error):
    if request.url.path == '/internal/inference/trace-link':
        return trace_link_failure('INELIGIBLE', 'INVALID_REQUEST', 422)
    if request.url.path == '/internal/inference/sms-classifier':
        return shadow_failure('INSUFFICIENT_DATA', 422)
    return await request_validation_exception_handler(request, error)


def trace_link_failure(status, reason, code):
    return JSONResponse(status_code=code, content={'status': status, 'reason': reason})


@app.get('/health/trace-link')
def trace_link_health():
    if not app.state.trace_link_enabled:
        return {'status': 'DISABLED'}
    return {'status': 'OK' if app.state.trace_link_candidate is not None else 'FALLBACK'}


@app.post('/internal/inference/trace-link')
def infer_trace_link(request: dict):
    if not app.state.trace_link_enabled:
        return trace_link_failure('DISABLED', 'SHADOW_DISABLED', 503)
    if app.state.trace_link_candidate is None:
        return trace_link_failure('FALLBACK', 'MODEL_UNAVAILABLE', 503)
    if not app.state.trace_link_slots.acquire(blocking=False):
        return trace_link_failure('FALLBACK', 'SATURATED', 503)
    try:
        return trace_link_score(request, app.state.trace_link_candidate)
    except ValueError:
        return trace_link_failure('INELIGIBLE', 'INVALID_REQUEST', 422)
    except Exception:
        return trace_link_failure('FALLBACK', 'INFERENCE_ERROR', 503)
    finally:
        app.state.trace_link_slots.release()


@app.post('/internal/inference/sms-classifier')
def infer_sms_classifier(window: dict):
    if not app.state.sms_shadow_enabled:
        return shadow_failure('DISABLED', 503)
    if app.state.sms_classifier is None:
        return shadow_failure('UNAVAILABLE', 503)
    if not app.state.sms_prediction_slots.acquire(blocking=False):
        return shadow_failure('UNAVAILABLE', 503)
    try:
        result = classifier_score(window, app.state.sms_classifier)
        return dict(result, schemaVersion=1, mlStatus='OK', threshold=THRESHOLD, modelSha256=MODEL_SHA256)
    except ValueError:
        return shadow_failure('INSUFFICIENT_DATA', 422)
    except Exception:
        return shadow_failure('UNAVAILABLE', 503)
    finally:
        app.state.sms_prediction_slots.release()
