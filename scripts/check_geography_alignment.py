#!/usr/bin/env python3
"""Fail deployment preflight when geography settings are incomplete or split."""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path
from typing import Mapping


SERVICES = ("event-generator", "processor")
ENABLED = "TELECOM_GEOGRAPHY_ENABLED"
EFFECTIVE_FROM = "TELECOM_GEOGRAPHY_EFFECTIVE_FROM"


def _validate_values(values: Mapping[str, object], source: str) -> list[str]:
    errors: list[str] = []
    enabled = str(values.get(ENABLED, "")).strip().lower()
    effective_from = str(values.get(EFFECTIVE_FROM, "")).strip()
    if enabled not in {"true", "false"}:
        errors.append(f"{source}: {ENABLED} must be true or false")
    if not effective_from:
        errors.append(f"{source}: {EFFECTIVE_FROM} must be set")
    else:
        try:
            parsed = datetime.fromisoformat(effective_from.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                errors.append(f"{source}: {EFFECTIVE_FROM} must include a timezone")
        except ValueError:
            errors.append(f"{source}: {EFFECTIVE_FROM} must be an ISO-8601 timestamp")
    return errors


def validate_environment(values: Mapping[str, object]) -> list[str]:
    return _validate_values(values, "host environment")


def validate_compose(config: Mapping[str, object]) -> list[str]:
    services = config.get("services")
    if not isinstance(services, Mapping):
        return ["Compose configuration has no services object"]

    environments: dict[str, Mapping[str, object]] = {}
    errors: list[str] = []
    for service in SERVICES:
        definition = services.get(service)
        environment = definition.get("environment") if isinstance(definition, Mapping) else None
        if not isinstance(environment, Mapping):
            errors.append(f"Compose service {service} has no environment mapping")
            continue
        environments[service] = environment
        errors.extend(_validate_values(environment, f"Compose service {service}"))

    if len(environments) == len(SERVICES):
        generator = {
            key: str(environments["event-generator"].get(key, "")).strip().lower()
            if key == ENABLED
            else str(environments["event-generator"].get(key, "")).strip()
            for key in (ENABLED, EFFECTIVE_FROM)
        }
        processor = {
            key: str(environments["processor"].get(key, "")).strip().lower()
            if key == ENABLED
            else str(environments["processor"].get(key, "")).strip()
            for key in (ENABLED, EFFECTIVE_FROM)
        }
        if generator != processor:
            errors.append(
                "Compose event-generator and processor geography settings must match"
            )
    return errors


def _read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"{path}:{line_number}: expected KEY=VALUE")
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip("\"'")
    return values


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--compose-json", action="store_true")
    args = parser.parse_args()

    if args.compose_json:
        try:
            errors = validate_compose(json.load(sys.stdin))
        except json.JSONDecodeError as error:
            print(f"Invalid Compose JSON: {error}", file=sys.stderr)
            return 2
    else:
        try:
            values = _read_env_file(args.env_file) if args.env_file else os.environ
        except (OSError, ValueError) as error:
            print(f"ERROR: {error}", file=sys.stderr)
            return 2
        errors = validate_environment(values)

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("Geography startup alignment passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
