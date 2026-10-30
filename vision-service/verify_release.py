"""Verify the frozen vision release without changing project files."""

from __future__ import annotations

import hashlib
import importlib.metadata
import json
from pathlib import Path
import sys


SERVICE_ROOT = Path(__file__).resolve().parent
MANIFEST_PATH = SERVICE_ROOT / "RELEASE_MANIFEST.json"


def _artifact_bytes(path: Path, hash_mode: str) -> bytes:
    content = path.read_bytes()
    if hash_mode == "exact":
        return content
    if hash_mode == "normalized-lf":
        return content.replace(b"\r\n", b"\n")
    raise ValueError(f"Unknown artifact hash mode: {hash_mode}")


def _release_path(relative: str) -> Path:
    candidate = (SERVICE_ROOT / relative).resolve()
    if not candidate.is_relative_to(SERVICE_ROOT):
        raise ValueError(f"Release path escapes vision-service: {relative}")
    return candidate


def main() -> int:
    failures: list[str] = []
    if not MANIFEST_PATH.is_file():
        print(f"FAIL missing release manifest: {MANIFEST_PATH}")
        return 1

    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    print(
        f"Checking {manifest['releaseVersion']} "
        f"({manifest['protocolVersion']})"
    )

    for artifact in manifest["artifacts"]:
        path = _release_path(artifact["path"])
        label = artifact["role"]
        if not path.is_file():
            failures.append(f"{label}: missing {artifact['path']}")
            continue
        hash_mode = artifact.get("hashMode", "exact")
        content = _artifact_bytes(path, hash_mode)
        size = len(content)
        digest = hashlib.sha256(content).hexdigest()
        if size != artifact["bytes"]:
            failures.append(
                f"{label}: size {size}, expected {artifact['bytes']}"
            )
        if digest.lower() != artifact["sha256"].lower():
            failures.append(
                f"{label}: SHA-256 {digest}, expected {artifact['sha256']}"
            )
        if not any(item.startswith(f"{label}:") for item in failures):
            print(f"PASS artifact {label}: {artifact['path']}")

    tested = manifest["runtime"]["pythonTested"]
    current_python = ".".join(str(part) for part in sys.version_info[:3])
    if sys.version_info[:2] != tuple(tested["majorMinor"]):
        failures.append(
            f"python: {current_python}, expected {tested['version']} major/minor"
        )
    else:
        print(f"PASS python {current_python}")

    for distribution, expected in manifest["runtime"]["dependencies"].items():
        try:
            actual = importlib.metadata.version(distribution)
        except importlib.metadata.PackageNotFoundError:
            failures.append(f"dependency: missing {distribution}=={expected}")
            continue
        if actual != expected:
            failures.append(
                f"dependency: {distribution}=={actual}, expected {expected}"
            )
        else:
            print(f"PASS dependency {distribution}=={actual}")

    if failures:
        print("RELEASE CHECK FAILED")
        for failure in failures:
            print(f"- {failure}")
        return 1
    print("RELEASE CHECK PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
