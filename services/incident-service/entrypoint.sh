#!/bin/sh
set -eu

if [ -z "${KEYCLOAK_CLIENT_SECRET:-}" ]; then
  echo "Keycloak client secret is unavailable; run scripts/prepare-keycloak first." >&2
  exit 1
fi

exec java -jar /app/app.jar
