#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Verify actual APK streaming assets, notices and JNI, not merely the staging directory."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / "tools/package-streaming-speech.py"), "--check"], check=True)
    spec = importlib.util.spec_from_file_location("bundle", ROOT / "tools/package-streaming-speech.py")
    bundle = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bundle)
    manifest = json.loads((bundle.WORK / "asr-only/manifest.json").read_text())
    cache = (bundle.WORK / "build-arm64-v8a/CMakeCache.txt").read_text()
    strip_match = re.search(r"^CMAKE_STRIP:FILEPATH=(.+)$", cache, re.MULTILINE)
    if not strip_match or not Path(strip_match[1]).is_file():
        raise ValueError("NDK strip tool missing from native build cache")
    strip_tool = strip_match[1]
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        if any(n.startswith("assets/speech/") or n.endswith("/libzhimo_speech.so") for n in names):
            raise ValueError("Legacy Whisper/VAD payload must not be packaged in Android")
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entry")
        expected_assets = {"assets/speech-streaming/manifest.json"}
        for relative, digest in manifest["files"].items():
            if relative.startswith("android-assets/"):
                name = "assets/" + relative.removeprefix("android-assets/")
                expected_assets.add(name)
                with archive.open(name) as data:
                    if hashlib.file_digest(data, "sha256").hexdigest() != digest:
                        raise ValueError(f"APK content mismatch: {name}")
        actual_assets = {n for n in names if n.startswith("assets/speech-streaming/") and not n.endswith("/")}
        if actual_assets != expected_assets:
            raise ValueError("APK streaming model/notice entries mismatch")
        if archive.read("assets/speech-streaming/manifest.json") != (bundle.WORK / "android-assets/speech-streaming/manifest.json").read_bytes():
            raise ValueError("APK model manifest mismatch")
        with tempfile.TemporaryDirectory(prefix="zhimo-streaming-apk-") as temporary:
            for abi in bundle.ABIS:
                lib = Path(temporary) / f"{abi}.so"
                lib.write_bytes(archive.read(f"lib/{abi}/libsherpa-onnx-jni.so"))
                bundle.inspect_native(lib, abi) # Gradle may strip debug symbols.
                expected_jni = Path(temporary) / f"{abi}-expected-jni.so"
                subprocess.run([strip_tool, "--strip-unneeded", "-o", str(expected_jni),
                                str(bundle.WORK / f"asr-only/jni/{abi}/libsherpa-onnx-jni.so")], check=True)
                if bundle.sha(lib) != bundle.sha(expected_jni):
                    raise ValueError(f"APK ASR native bytes mismatch after stripping: {abi}")
                # Runtime must be the existing ORT 1.22, not an extra statically linked 1.17.
                with archive.open(f"lib/{abi}/libonnxruntime.so") as data:
                    digest = hashlib.file_digest(data, "sha256").hexdigest()
                expected_ort = Path(temporary) / f"{abi}-expected-ort.so"
                subprocess.run([strip_tool, "--strip-unneeded", "-o", str(expected_ort),
                                str(bundle.WORK / f"ort-1.22/jni/{abi}/libonnxruntime.so")], check=True)
                if digest != bundle.sha(expected_ort):
                    raise ValueError(f"APK ORT runtime mismatch: {abi}")
    print("Actual APK streaming models, notices, ASR-only JNI and shared ORT verified; no device test implied")


if __name__ == "__main__":
    main()
