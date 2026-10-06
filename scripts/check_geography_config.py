#!/usr/bin/env python3
"""Validate the rendered Compose geography configuration."""

from __future__ import annotations

import json
import sys
from datetime import datetime
from typing import Mapping


SERVICES = ("event-generator", "processor")
ENABLED_KEY = "TELECOM_GEOGRAPHY_ENABLED"
EFFECTIVE_FROM_KEY = "TELECOM_GEOGRAPHY_EFFECTIVE_FROM"


def validate_config(config: Mapping[str, object]) -> list[str]:
    services = config.get("services")
    if not isinstance(services, Mapping):
        return ["Compose configuration has no services object"]

    errors: list[str] = []
    values: dict[str, dict[str, str]] = {}
    for service in SERVICES:
        definition = services.get(service)
        environment = definition.get("environment") if isinstance(definition, Mapping) else None
        if not isinstance(environment, Mapping):
            errors.append(f"{service} has no environment mapping")
            continue
        missing = [key for key in (ENABLED_KEY, EFFECTIVE_FROM_KEY) if key not in environment]
        if missing:
            errors.append(f"{service} is missing {', '.join(missing)}")
            continue
        values[service] = {
            ENABLED_KEY: str(environment[ENABLED_KEY]).lower(),
            EFFECTIVE_FROM_KEY: str(environment[EFFECTIVE_FROM_KEY]),
        }

    if len(values) == len(SERVICES):
        generator = values["event-generator"]
        processor = values["processor"]
        if generator != processor:
            errors.append("event-generator and processor geography settings do not match")
        if generator[ENABLED_KEY] not in {"true", "false"}:
            errors.append(f"{ENABLED_KEY} must be true or false")
        try:
            effective_from = datetime.fromisoformat(
                generator[EFFECTIVE_FROM_KEY].replace("Z", "+00:00")
            )
            if effective_from.tzinfo is None:
                errors.append(f"{EFFECTIVE_FROM_KEY} must include a timezone")
        except ValueError:
            errors.append(f"{EFFECTIVE_FROM_KEY} must be an ISO-8601 timestamp")

    return errors


def main() -> int:
    try:
        config = json.load(sys.stdin)
    except json.JSONDecodeError as error:
        print(f"Invalid Compose JSON: {error}", file=sys.stderr)
        return 2

    errors = validate_config(config)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("Geography configuration aligned for event-generator and processor")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
