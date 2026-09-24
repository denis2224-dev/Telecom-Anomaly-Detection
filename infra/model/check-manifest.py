#!/usr/bin/env python3
"""Validate an approved local model manifest and artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys


def validate_manifest(manifest_path: Path, expected_feature_version: str | None = None) -> tuple[bool, str]:
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return False, f"manifest not found: {manifest_path}"
    except (OSError, json.JSONDecodeError) as error:
        return False, f"invalid manifest {manifest_path}: {error}"

    required = {"manifestVersion", "modelVersion", "featureVersion", "artifact"}
    missing = required - manifest.keys()
    if missing:
        return False, f"manifest missing fields: {', '.join(sorted(missing))}"
    if expected_feature_version and str(manifest["featureVersion"]) != expected_feature_version:
        return False, (
            f"feature version mismatch: expected {expected_feature_version}, "
            f"got {manifest['featureVersion']}"
        )
    artifact = manifest["artifact"]
    if not isinstance(artifact, dict) or not isinstance(artifact.get("path"), str):
        return False, "manifest artifact.path must be a string"
    checksum = artifact.get("sha256")
    if not isinstance(checksum, str) or len(checksum) != 64:
        return False, "manifest artifact.sha256 must be a 64-character SHA-256"
    try:
        int(checksum, 16)
    except ValueError:
        return False, "manifest artifact.sha256 is not hexadecimal"

    artifact_path = Path(artifact["path"])
    if not artifact_path.is_absolute():
        artifact_path = manifest_path.parent / artifact_path
    if not artifact_path.is_file():
        return False, f"model artifact not found: {artifact_path}"

    digest = hashlib.sha256()
    try:
        with artifact_path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as error:
        return False, f"cannot read model artifact {artifact_path}: {error}"
    actual = digest.hexdigest()
    if actual != checksum.lower():
        return False, f"model checksum mismatch: expected {checksum}, got {actual}"

    return True, (
        f"approved model {manifest['modelVersion']} is compatible with "
        f"feature version {manifest['featureVersion']}"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--feature-version")
    args = parser.parse_args()
    valid, message = validate_manifest(args.manifest, args.feature_version)
    print(json.dumps({"status": "READY" if valid else "NOT_READY", "detail": message}))
    return 0 if valid else 1


if __name__ == "__main__":
    sys.exit(main())
