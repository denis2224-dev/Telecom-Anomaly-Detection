"""Validate the archived correction evidence without Docker or external services."""
from pathlib import Path
import gzip
import hashlib
import json
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def load(name):
    return json.loads((HERE / name).read_text(encoding="utf-8"))


def main():
    manifest = load("artifact-manifest.json")
    actual = {p.name for p in HERE.iterdir() if p.is_file()} - {"artifact-manifest.json"}
    assert actual == set(manifest["files"]), "Evidence inventory differs from manifest"
    for name, expected in manifest["files"].items():
        raw = (HERE / name).read_bytes()
        assert sha(raw) == expected["sha256"] and len(raw) == expected["bytes"], name
    for name, expected in load("packed-artifacts.json").items():
        raw = (HERE / name).read_bytes()
        unpacked = gzip.decompress(raw)
        assert sha(raw) == expected["sha256"], name
        assert sha(unpacked) == expected["uncompressedSha256"], name
        assert len(unpacked) == expected["uncompressedBytes"], name
    source = load("source-manifest.json")
    for item in source["files"]:
        raw = subprocess.check_output(
            ["git", "show", source["implementationCommit"] + ":" + item["path"]], cwd=ROOT
        )
        assert sha(raw) == item["gitBlobSha256"], item["path"]
    checks = load("verification.json")
    for name in ("reactor", "receiverUnit", "receiverIntegration"):
        assert checks[name]["failures"] == checks[name]["errors"] == 0, name
    print(f"PASS: {len(actual)} correction artifacts, compressed original bytes and implementation Git blobs")


if __name__ == "__main__":
    main()
