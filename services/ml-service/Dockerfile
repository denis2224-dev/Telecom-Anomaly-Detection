FROM python:3.13.9-slim-bookworm
WORKDIR /app
COPY services/ml-service/requirements-ml.txt services/ml-service/requirements-ml.txt
RUN python -m pip install --no-cache-dir -r services/ml-service/requirements-ml.txt
COPY contracts/features/feature-order-v2.json contracts/features/feature-order-v2.json
COPY services/ml-service/app services/ml-service/app
COPY services/ml-service/models services/ml-service/models
COPY services/ml-service/candidate-models services/ml-service/candidate-models
ENV ML_SMS_SHADOW_ENABLED=false
ENV ML_SMS_CANDIDATE_PATH=/app/services/ml-service/candidate-models/sms-supervised-v1-2
ENV PYTHONPATH=/app/services/ml-service
CMD ["python", "-m", "uvicorn", "app.inference.api:app", "--host", "0.0.0.0", "--port", "8090", "--limit-concurrency", "8"]
