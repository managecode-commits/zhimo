#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Fail closed: generic ASR+TTS native libraries must not enter the APK."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
group = parser.add_mutually_exclusive_group(required=True)
group.add_argument("--api-only", action="store_true")
group.add_argument("--native", action="store_true")
args = parser.parse_args()
api_notices = ROOT / "platform/android-ime/app/src/main/assets/speech-api"
if hashlib.sha256((api_notices / "LICENSE").read_bytes()).hexdigest() != "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30":
    raise SystemExit("Missing or changed sherpa API license")
if "sherpa-onnx" not in (api_notices / "NOTICE.txt").read_text():
    raise SystemExit("Missing sherpa API attribution")
subprocess.run([sys.executable, str(ROOT / "tools/prepare-streaming-speech-runtime.py"), "--check"], check=True)
if args.native:
    subprocess.run([sys.executable, str(ROOT / "tools/package-streaming-speech.py"), "--check"], check=True)
    print("Android streaming-only bundle verified; phone quality/latency validation is still required")
else:
    print("API files verified; this check alone is insufficient for an Android build")
