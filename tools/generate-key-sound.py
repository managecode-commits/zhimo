#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Generate Zhimo's original, short, windowed keyclick (no third-party sample)."""
import math
import random
from pathlib import Path
import struct
import wave

destination = Path(__file__).resolve().parents[1] / "platform/android-ime/app/src/main/res/raw/key_soft.wav"
destination.parent.mkdir(parents=True, exist_ok=True)
rate, count = 24000, 288
samples = []
rng = random.Random(20260915)
fast, slow = 0.0, 0.0
for index in range(count):
    t = index / rate
    # A brief, band-limited contact transient, not a pitched notification beep.
    noise = rng.uniform(-1, 1)
    fast += .5 * (noise - fast)
    slow += .13 * (noise - slow)
    envelope = (1 - math.exp(-t / .00025)) * math.exp(-t / .002) * (1 - index / count) ** 2
    samples.append(round(18000 * envelope * (fast - slow)))
samples[0] = samples[-1] = 0
with wave.open(str(destination), "wb") as output:
    output.setparams((1, 2, rate, count, "NONE", "not compressed"))
    output.writeframes(struct.pack(f"<{count}h", *samples))
