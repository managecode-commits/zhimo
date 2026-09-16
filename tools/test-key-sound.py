#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Signal-format regression checks, not a substitute for listening tests."""
import cmath
import math
from pathlib import Path
import struct
import unittest
import wave


class KeySoundTest(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[1] / "platform/android-ime/app/src/main/res/raw/key_soft.wav"
        with wave.open(str(path), "rb") as source:
            self.assertEqual((source.getnchannels(), source.getsampwidth(), source.getframerate()), (1, 2, 24000))
            self.samples = struct.unpack(f"<{source.getnframes()}h", source.readframes(source.getnframes()))

    def test_short_and_not_clipped(self):
        self.assertEqual(len(self.samples), 288)
        self.assertGreater(max(abs(value) for value in self.samples), 100)
        self.assertLess(max(abs(value) for value in self.samples), 32767)

    def test_edges_are_zero(self):
        self.assertEqual(self.samples[0], 0)
        self.assertEqual(self.samples[-1], 0)

    def test_no_single_dominant_tone(self):
        count = len(self.samples)
        power = [abs(sum(value * cmath.exp(-2j * math.pi * k * n / count)
                         for n, value in enumerate(self.samples))) ** 2
                 for k in range(1, count // 2)]
        self.assertLess(max(power) / sum(power), .2)


if __name__ == "__main__":
    unittest.main()
