#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Evaluate pinned sherpa CLI on authorized JSONL fixtures; reports contain no transcripts.

This is a desktop file-decode benchmark, NOT Android microphone latency. Each sample
starts a fresh process. process_seconds includes model startup; decode_seconds is
the rounded value printed by the upstream CLI. Do not compare it to warm Whisper
timings as an acceleration ratio.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import wave

spec = importlib.util.spec_from_file_location("metrics", Path(__file__).with_name("evaluate-speech.py"))
metrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)


def parse_result(output):
    hypotheses = []
    for line in output.splitlines():
        if line.lstrip().startswith("{"):
            try:
                obj = json.loads(line)
                if isinstance(obj, dict) and isinstance(obj.get("text"), str):
                    hypotheses.append(obj["text"])
            except json.JSONDecodeError:
                continue
    if len(hypotheses) != 1:
        raise ValueError("Expected exactly one result; upstream output format changed")
    elapsed = re.search(r"Elapsed seconds: ([0-9.]+)", output)
    if not elapsed:
        raise ValueError("Missing upstream decode timing")
    return hypotheses[0], float(elapsed[1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", required=True, type=Path)
    parser.add_argument("--model-directory", required=True, type=Path)
    parser.add_argument("--model", choices=("zipformer", "paraformer"), required=True)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--threads", choices=(1, 2, 4), type=int, default=2)
    args = parser.parse_args()
    binary = args.binary.resolve(strict=True)
    directory = args.model_directory.resolve(strict=True)
    command = [str(binary), f"--tokens={directory / 'tokens.txt'}", f"--num-threads={args.threads}"]
    if args.model == "zipformer":
        command += [f"--encoder={directory / 'encoder-epoch-99-avg-1.int8.onnx'}",
                    f"--decoder={directory / 'decoder-epoch-99-avg-1.onnx'}",
                    f"--joiner={directory / 'joiner-epoch-99-avg-1.onnx'}"]
    else:
        command += [f"--paraformer-encoder={directory / 'encoder.int8.onnx'}",
                    f"--paraformer-decoder={directory / 'decoder.int8.onnx'}"]
    environment = os.environ.copy()
    environment["LD_LIBRARY_PATH"] = str(binary.parent.parent / "lib")
    rows = [json.loads(line) for line in args.dataset.read_text().splitlines() if line.strip()]
    if not rows or len({r["id"] for r in rows}) != len(rows):
        parser.error("Dataset must be nonempty with unique IDs")
    results = []
    for row in rows:
        if row["language"] not in ("zh", "en") or not isinstance(row["reference"], str):
            parser.error("Expected zh/en and a human-verified reference")
        audio = (args.dataset.parent / row["audio"]).resolve(strict=True)
        with wave.open(str(audio)) as source:
            if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, 16000):
                parser.error("Fixtures must be mono PCM16 16kHz")
            duration = source.getnframes() / 16000
        if not 0.1 <= duration <= 60:
            parser.error("Fixture duration must be 0.1..60s")
        start = time.monotonic()
        process = subprocess.run(command + [str(audio)], env=environment, capture_output=True, timeout=180)
        elapsed = time.monotonic() - start
        if process.returncode:
            raise SystemExit(f"Recognizer failed for sample {row['id']}; exit={process.returncode}; transcript omitted")
        text, decode_seconds = parse_result((process.stdout+process.stderr).decode("utf-8"))
        reference = metrics.tokens(row["reference"], row["language"])
        hypothesis = metrics.tokens(text, row["language"])
        results.append(dict(id=row["id"], language=row["language"], units=len(reference),
                            errors=metrics.distance(reference, hypothesis), audio_seconds=duration,
                            process_seconds=elapsed, decode_seconds=decode_seconds,
                            decode_rtf=decode_seconds/duration))
    report = dict(engine=args.model, threads=args.threads, device="build-host-not-Android",
                  timing="cold-process-per-sample; decode time excludes startup and is upstream-rounded",
                  samples=results)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
    print(f"Wrote metrics without transcripts: {args.output}")


if __name__ == "__main__":
    main()
