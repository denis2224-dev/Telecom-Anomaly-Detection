"""Shared test utilities for contract and API test suites."""

from datetime import datetime
import json
from pathlib import Path

from jsonschema import FormatChecker

ROOT = Path(__file__).resolve().parents[1]
FORMAT_CHECKER = FormatChecker()


@FORMAT_CHECKER.checks("date-time", raises=ValueError)
def valid_calendar_time(value):
    """Reject dates such as 30 February that match the timestamp pattern."""
    if isinstance(value, str):
        datetime.fromisoformat(value)
    return True


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON property: {key}")
        result[key] = value
    return result


def reject_non_json_number(value):
    raise ValueError(f"Not a JSON number: {value}")


def read_json(path):
    return json.loads(
        Path(path).read_text(encoding="utf-8"),
        object_pairs_hook=unique_object,
        parse_constant=reject_non_json_number,
    )
