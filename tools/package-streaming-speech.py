#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Stage or verify the experimental ASR-only Android bundle (never the generic AAR JNI)."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "target/streaming-speech"
ABIS = {"arm64-v8a": "AArch64", "armeabi-v7a": "ARM", "x86_64": "Advanced Micro Devices X86-64"}
COMPONENTS = ["kaldi-native-fbank-1.21.3", "kaldi-decoder-0.2.6", "simple-sentencepiece-0.7",
              "cppjieba-sherpa-onnx-2024-04-19", "kissfft-febd4caeed32e33ad8b2e0bb5ea77542c40f18ec",
              "kaldifst-1.7.13", "openfst-sherpa-onnx-2024-06-13", "eigen-3.4.0"]


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def inspect_native(path, abi):
    header = subprocess.check_output(["readelf", "-h", str(path)], text=True)
    if ABIS[abi] not in header:
        raise ValueError(f"Wrong ABI: {abi}")
    dynamic = subprocess.check_output(["readelf", "-d", str(path)], text=True)
    if "libonnxruntime.so" not in dynamic or "RPATH" in dynamic or "RUNPATH" in dynamic:
        raise ValueError(f"Unexpected ORT linkage or runtime path: {abi}")
    symbols = subprocess.check_output(["nm", "-D", str(path)], text=True)
    if any(name in symbols.lower() for name in ("espeak", "piper", "offlinetts")):
        raise ValueError(f"TTS symbols in ASR-only library: {abi}")
    if "Java_com_k2fsa_sherpa_onnx_OnlineRecognizer" not in symbols:
        raise ValueError(f"Online JNI missing: {abi}")
    segments = subprocess.check_output(["readelf", "-lW", str(path)], text=True)
    loads = [line for line in segments.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(line.split()[-1], 16) < 16384 for line in loads):
        raise ValueError(f"16 KiB page alignment missing: {abi}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("models", ROOT / "tools/prepare-streaming-speech-model.py")
    models = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(models)
    model = models.MODELS["zipformer"]
    assets = WORK / "android-assets/speech-streaming"
    pairs = []
    manifest = {"model": "zipformer", "repo": model["repo"], "revision": model["revision"], "files": {}}
    for name, digest in model["files"].items():
        source = WORK / "models/zipformer" / name
        models.verify(source, digest)
        manifest["files"][name] = sha(source)
        pairs.append((source, assets / name))
    if sha(WORK / "sherpa-source-full.tar.gz") != "49ea0985748c35a16c1544e07cec13c43e5a76db83bbb6f42bae5c850c85d2e3":
        raise ValueError("Sherpa source archive mismatch")
    licenses = [(WORK / "source-full/LICENSE", assets / "licenses/sherpa-onnx/LICENSE")]
    for component in COMPONENTS:
        base = WORK / "deps/extracted" / component
        notices = [p for p in base.rglob("*") if p.is_file() and
                   p.name.upper().startswith(("LICENSE", "COPYING", "NOTICE"))]
        if not notices:
            raise ValueError(f"Missing notices: {component}")
        licenses += [(p, assets / "licenses" / component / p.relative_to(base)) for p in notices]
    for name in ("ONNXRUNTIME-LICENSE", "ONNXRUNTIME-ThirdPartyNotices.txt"):
        licenses.append((ROOT / "models/handwriting-image" / name, assets / "licenses" / name))
    licenses.append((ROOT / "tools/build-streaming-speech-android.sh", assets / "licenses/build-source-locations.sh"))
    pairs += licenses
    build = {"experimental": True, "sherpa": "1.12.11", "onnxruntime": "1.22.0",
             "tts": False, "phone_tested": False, "files": {}}
    for abi in ABIS:
        cache = (WORK / f"build-{abi}/CMakeCache.txt").read_text()
        for flag in ("SHERPA_ONNX_ENABLE_TTS:BOOL=OFF", "SHERPA_ONNX_ENABLE_JNI:BOOL=ON",
                     "BUILD_SHARED_LIBS:BOOL=OFF"):
            if flag not in cache:
                raise ValueError(f"Native build flag mismatch: {abi}: {flag}")
        matches = list((WORK / f"build-{abi}").rglob("libsherpa-onnx-jni.so"))
        if len(matches) != 1:
            raise ValueError(f"Expected one ASR JNI output: {abi}")
        source = matches[0]
        inspect_native(source, abi)
        pairs.append((source, WORK / "asr-only/jni" / abi / source.name))
    for source, destination in pairs:
        digest = sha(source)
        build["files"][str(destination.relative_to(WORK))] = digest
        if args.check:
            if not destination.is_file() or sha(destination) != digest:
                raise ValueError(f"Staged file missing/modified: {destination}")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
    for destination, data in ((assets / "manifest.json", manifest), (WORK / "asr-only/manifest.json", build)):
        content = json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        if args.check:
            if destination.read_text() != content:
                raise ValueError(f"Stale manifest: {destination}")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content, encoding="utf-8")
    expected = {destination for _, destination in pairs} | {assets / "manifest.json"}
    actual = {p for base in (WORK / "android-assets", WORK / "asr-only/jni") for p in base.rglob("*") if p.is_file()}
    if expected != actual:
        raise ValueError("Unexpected staged assets/native files; refusing to package")
    print("Verified experimental ASR-only bundle: three ABIs, pinned Zipformer, notices; phone testing still required")


if __name__ == "__main__":
    main()
