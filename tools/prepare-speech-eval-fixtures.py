#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Create generated silence/tone fixtures plus an upstream JFK reference, under target only."""
import array
import json
import math
from pathlib import Path
import sys
import wave

ROOT = Path(__file__).resolve().parents[1]
destination = ROOT / "target/speech-eval-fixtures"
destination.mkdir(parents=True, exist_ok=True)
entries = []
for name, samples in [("silence", [0] * 32000),
                      ("tone", [int(1200 * math.sin(2 * math.pi * 440 * i / 16000)) for i in range(32000)])]:
    audio = array.array("h", samples)
    if sys.byteorder != "little":
        audio.byteswap()
    with wave.open(str(destination / (name + ".wav")), "wb") as output:
        output.setparams((1, 2, 16000, len(audio), "NONE", "not compressed"))
        output.writeframes(audio.tobytes())
    entries.append(dict(id=name, audio=name + ".wav", reference="", language="zh", category="generated-nonspeech"))
upstream = ROOT / "target/vendor/whisper.cpp-1.9.1"
# Raw PCM copy of the same public fixture, for the opt-in streaming Android test.
with wave.open(str(upstream / "bindings/go/samples/jfk.wav"), "rb") as source:
    if (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) != (1, 2, 16000, "NONE"):
        raise ValueError("Unexpected public JFK fixture format")
    (destination / "jfk.pcm").write_bytes(source.readframes(source.getnframes()))
entries.append(dict(id="jfk", audio=str(upstream / "bindings/go/samples/jfk.wav"),
                    reference=(upstream / "tests/parakeet-expected-jfk-output.txt").read_text().strip(),
                    language="en", category="public-jfk"))
(destination / "samples.jsonl").write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in entries) + "\n")
print(destination / "samples.jsonl")
