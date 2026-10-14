FROM python:3.13.9-slim-bookworm
WORKDIR /app
COPY services/ml-service/requirements-ml.txt services/ml-service/requirements-ml.txt
RUN python -m pip install --no-cache-dir -r services/ml-service/requirements-ml.txt
COPY contracts/features/feature-order-v2.json contracts/features/feature-order-v2.json
COPY contracts/features/trace-link* contracts/features/
COPY contracts/detections/trace-link-evaluation-v1.schema.json contracts/detections/trace-link-evaluation-v1.schema.json
COPY contracts/baselines/trace-link-baseline-policy-v1* contracts/baselines/
COPY contracts/kpi/trace-link-kpi-definitions-v1.json contracts/kpi/trace-link-kpi-definitions-v1.json
COPY contracts/ml-shadow/trace-link* contracts/ml-shadow/
COPY services/ml-service/app services/ml-service/app
COPY services/ml-service/models services/ml-service/models
COPY services/ml-service/candidate-models services/ml-service/candidate-models
ENV ML_SMS_SHADOW_ENABLED=false
ENV ML_TRACE_LINK_SHADOW_ENABLED=false
ENV ML_TRACE_LINK_CANDIDATE_PATH=/app/services/ml-service/candidate-models/trace-link-shadow-v1
ENV ML_SMS_CANDIDATE_PATH=/app/services/ml-service/candidate-models/sms-supervised-v1-2
ENV PYTHONPATH=/app/services/ml-service
CMD ["python", "-m", "uvicorn", "app.inference.api:app", "--host", "0.0.0.0", "--port", "8090", "--limit-concurrency", "32"]
