FROM python:3.13.9-slim-bookworm
WORKDIR /app
COPY services/ml-service/requirements-ml.txt services/ml-service/requirements-ml.txt
RUN python -m pip install --no-cache-dir -r services/ml-service/requirements-ml.txt
COPY contracts/features/feature-order-v2.json contracts/features/feature-order-v2.json
COPY services/ml-service/app services/ml-service/app
COPY services/ml-service/models services/ml-service/models
ENV PYTHONPATH=/app/services/ml-service
CMD ["python", "-m", "uvicorn", "app.inference.api:app", "--host", "0.0.0.0", "--port", "8090", "--limit-concurrency", "8"]
