#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Prepare immutable, verified streaming ASR evaluation models (no APK-side downloads)."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MODELS = {
    "zipformer": {
        "repo": "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
        "revision": "98590b7ed6443e77b714204da2757d75e1a642f4",
        "files": {
            "encoder-epoch-99-avg-1.int8.onnx": "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b",
            "decoder-epoch-99-avg-1.onnx": "2e3b5ec371f8899ee6acd829fd753ba45772df57a91bdf37cde3136354e7db7d",
            "joiner-epoch-99-avg-1.onnx": "5f2adc585dd1bec6421c8bb8660d2a73fc8b9ceb24491ef51399ba2a2f0fc31b",
            "tokens.txt": "git:980dd6cd2d71532898b1eac4a4ac9a91302083b6",
            "README.md": "git:81530df8c89dc7f7e5238112a2c3a5855af43f8d",
        },
    },
    "paraformer": {
        "repo": "csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en",
        "revision": "8e40c43232a1c5c66c82111efc5820d3accca11b",
        "files": {
            "encoder.int8.onnx": "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a",
            "decoder.int8.onnx": "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f",
            "tokens.txt": "git:57bc045ddda0434ed4440c38e14287c595b258d9",
            "README.md": "git:23c9cb2b0edb172e6790f2ed5ff398fdba6e233a",
        },
    },
}


def verify(path, expected):
    if expected.startswith("git:"):
        content = path.read_bytes()  # Small Git blobs; ONNX weights use streamed SHA-256 below.
        value = hashlib.sha1(f"blob {len(content)}\0".encode() + content).hexdigest()
        valid = value == expected[4:]
    else:
        with path.open("rb") as source:
            valid = hashlib.file_digest(source, "sha256").hexdigest() == expected
    if not valid:
        raise ValueError(f"Model integrity mismatch: {path.name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", choices=MODELS, default="zipformer")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    model = MODELS[args.model]
    directory = ROOT / "target/streaming-speech/models" / args.model
    directory.mkdir(parents=True, exist_ok=True)
    manifest = {"model": args.model, "repo": model["repo"], "revision": model["revision"], "files": {}}
    for name, expected in model["files"].items():
        destination = directory / name
        if not destination.exists():
            if args.check:
                raise SystemExit(f"Missing model file: {destination}")
            with tempfile.TemporaryDirectory(prefix="download-", dir=directory) as temporary:
                part = Path(temporary) / name
                url = f'https://huggingface.co/{model["repo"]}/resolve/{model["revision"]}/{name}'
                subprocess.run(["curl", "-fL", "--proto", "=https", "--proto-redir", "=https",
                                "--retry", "3", "--retry-all-errors", "--connect-timeout", "15",
                                "--max-time", "600", "-o", str(part), url], check=True)
                verify(part, expected)
                part.replace(destination)
        verify(destination, expected)
        with destination.open("rb") as source:
            manifest["files"][name] = hashlib.file_digest(source, "sha256").hexdigest()
    expected_manifest = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    target = directory / "manifest.json"
    if args.check:
        if not target.exists() or target.read_text() != expected_manifest:
            raise SystemExit("Generated model manifest is stale")
    else:
        target.write_text(expected_manifest, encoding="utf-8")
    print(f"Verified model: {directory}")


if __name__ == "__main__":
    main()
