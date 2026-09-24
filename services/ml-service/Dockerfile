FROM python:3.13-slim

WORKDIR /app
COPY infra/model/check-manifest.py /app/check-manifest.py
COPY services/ml-service/app/health_server.py /app/health_server.py
COPY services/ml-service/model-manifest.json /app/model-manifest.json

ENV ML_MANIFEST_PATH=/app/model-manifest.json
ENV ML_PORT=8000
EXPOSE 8000

USER nobody
ENTRYPOINT ["python", "/app/health_server.py", "--manifest", "/app/model-manifest.json", "--port", "8000"]
