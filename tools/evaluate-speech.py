#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Offline ASR evaluation. JSONL: id, audio, reference, language (zh/en), category.
Only use authorized recordings. Reports omit recordings and transcript text.
Chinese CER uses Unicode code points; English WER uses whitespace-delimited words.
Punctuation is excluded; numbers and traditional/simplified forms are NOT conflated.
"""
import argparse
import array
import ctypes
import hashlib
import json
import math
from pathlib import Path
import sys
import time
import unicodedata
import wave


def tokens(text, language):
    text = unicodedata.normalize("NFKC", text).casefold()
    text = "".join(c for c in text if not unicodedata.category(c).startswith("P"))
    return list("".join(text.split())) if language == "zh" else text.split()


def distance(a, b):
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        new = [i]
        for j, y in enumerate(b, 1):
            new.append(min(row[j] + 1, new[-1] + 1, row[j - 1] + (x != y)))
        row = new
    return row[-1]


def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--library", required=True, type=Path)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--vad", type=Path)
    parser.add_argument("--prompt", default="")
    args = parser.parse_args()
    lib = ctypes.CDLL(str(args.library.resolve(strict=True)))
    lib.zhimo_speech_create.restype = ctypes.c_uint64
    lib.zhimo_speech_release.argtypes = [ctypes.c_uint64]
    lib.zhimo_speech_release.restype = None
    lib.zhimo_speech_text_free.argtypes = [ctypes.c_void_p]
    lib.zhimo_speech_text_free.restype = None
    run = lib.zhimo_speech_transcribe_options
    run.argtypes = [ctypes.c_uint64, ctypes.c_char_p, ctypes.POINTER(ctypes.c_float), ctypes.c_size_t,
                   ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
    run.restype = ctypes.c_int
    entries = [json.loads(line) for line in args.dataset.read_text().splitlines() if line.strip()]
    if not entries or len({e["id"] for e in entries}) != len(entries):
        parser.error("Dataset must be nonempty with unique IDs")
    results = []
    for entry in entries:
        language = entry["language"]
        if language not in ("zh", "en") or not isinstance(entry["reference"], str):
            parser.error("Each sample requires zh/en and a human-verified reference string")
        path = (args.dataset.parent / entry["audio"]).resolve(strict=True)
        with wave.open(str(path)) as source:
            if (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) != (1, 2, 16000, "NONE"):
                parser.error("Audio must be PCM16 mono 16 kHz")
            if not 1600 <= source.getnframes() <= 960000:
                parser.error("Audio must be 0.1..60 seconds")
            samples = array.array("h", source.readframes(source.getnframes()))
        if sys.byteorder != "little":
            samples.byteswap()
        pcm = (ctypes.c_float * len(samples))(*(x / 32768 for x in samples))
        session = lib.zhimo_speech_create()
        output = ctypes.c_void_p()
        start = time.monotonic()
        try:
            status = run(session, str(args.model.resolve(strict=True)).encode(), pcm, len(pcm), language.encode(),
                         args.prompt.encode(), str(args.vad.resolve(strict=True)).encode() if args.vad else b"", ctypes.byref(output))
            elapsed = time.monotonic() - start
            hypothesis = ctypes.string_at(output).decode() if output else ""
        finally:
            lib.zhimo_speech_text_free(output)
            lib.zhimo_speech_release(session)
        ref = tokens(entry["reference"], language)
        hyp = tokens(hypothesis, language)
        results.append(dict(id=entry["id"], language=language, category=entry.get("category", "unspecified"),
                            status=status, errors=distance(ref, hyp), units=len(ref), insertions_on_empty=len(hyp) if not ref else 0,
                            seconds=elapsed, audio_seconds=len(samples)/16000, rtf=elapsed/(len(samples)/16000)))
    groups = {}
    for item in results:
        groups.setdefault(item["language"] + ":" + item["category"], []).append(item)
    report = {}
    for name, rows in groups.items():
        units = sum(r["units"] for r in rows)
        report[name] = dict(samples=len(rows), failures=sum(r["status"] != 0 for r in rows),
                            metric="CER" if name.startswith("zh:") else "WER",
                            error_rate=sum(r["errors"] for r in rows)/units if units else None,
                            empty_reference_insertions=sum(r["insertions_on_empty"] for r in rows),
                            latency_p50=percentile([r["seconds"] for r in rows], .5),
                            latency_p95=percentile([r["seconds"] for r in rows], .95))
    print(json.dumps(dict(model_sha256=hashlib.sha256(args.model.read_bytes()).hexdigest(),
                         vad_sha256=hashlib.sha256(args.vad.read_bytes()).hexdigest() if args.vad else None,
                         runtime_abi=lib.zhimo_speech_abi_version(), groups=report, samples=results,
                         note="First non-silent sample is cold; subsequent samples may reuse model. Errors include failed jobs; inspect failures."),
                     ensure_ascii=False, indent=2))
    return int(any(r["status"] != 0 for r in results))


if __name__ == "__main__":
    sys.exit(main())
