#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Fetch the pinned evaluation runtime only; does not change the APK or default engine."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NAME = "sherpa-onnx-static-link-onnxruntime-1.12.11.aar"
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.11/{NAME}"
# Published release asset SHA-256, not a hash obtained from our first download.
SHA256 = "bb9856423978d2318480405a3aa0442a2af9674cf258d73ff419899b8e0d69b4"


def validate(path, expected=SHA256):
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != expected:
        raise ValueError("Runtime SHA-256 mismatch; refusing to use the download")
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        required = {"classes.jar"} | {
            f"jni/{abi}/libsherpa-onnx-jni.so"
            for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        if not required.issubset(names):
            raise ValueError("Runtime does not contain all required Android ABI libraries")
        if any(name.startswith("/") or ".." in Path(name).parts for name in names):
            raise ValueError("Unsafe archive entry")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify local runtime without network access")
    args = parser.parse_args()
    directory = ROOT / "target/streaming-speech"
    destination = directory / NAME
    if destination.exists():
        validate(destination)
    elif args.check:
        raise SystemExit("Runtime not prepared; no APK streaming integration is available yet")
    else:
        directory.mkdir(parents=True, exist_ok=True)
        # A failed download cannot replace an existing verified runtime.
        with tempfile.TemporaryDirectory(prefix="runtime-", dir=directory) as temporary:
            part = Path(temporary) / NAME
            subprocess.run([
                "curl", "--fail", "--location", "--proto", "=https", "--proto-redir", "=https",
                "--connect-timeout", "15", "--max-time", "300",
                "--retry", "2", "--retry-all-errors", "--output", str(part), URL,
            ], check=True)
            validate(part)
            part.replace(destination)
    print(f"Verified evaluation runtime: {destination}")
    # Only the Apache-licensed API bytecode is used by the app by default.
    # The generic AAR native payload also contains TTS; it is not approved for APK packaging.
    api = directory / "api/classes.jar"
    with zipfile.ZipFile(destination) as archive:
        payload = archive.read("classes.jar")
    if args.check:
        if not api.exists() or api.read_bytes() != payload:
            raise SystemExit("API classes missing or stale; run preparation without --check")
    else:
        api.parent.mkdir(exist_ok=True)
        api.write_bytes(payload)
    print("Model preparation, native compatibility checks and device evaluation are still required.")


if __name__ == "__main__":
    main()
