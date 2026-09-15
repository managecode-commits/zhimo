#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("evaluate_speech", Path(__file__).with_name("evaluate-speech.py"))
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


class MetricsTest(unittest.TestCase):
    def test_edits(self):
        self.assertEqual(evaluation.distance("中国", "中果"), 1)
        self.assertEqual(evaluation.distance("中国", "中"), 1)
        self.assertEqual(evaluation.distance("中国", "中国人"), 1)

    def test_silence(self):
        self.assertEqual(evaluation.distance([], list("谢谢")), 2)
        self.assertEqual(evaluation.distance([], []), 0)

    def test_normalization(self):
        self.assertEqual(evaluation.tokens("Hello,   WORLD!", "en"), ["hello", "world"])
        self.assertEqual(evaluation.tokens("中国，１２３。", "zh"), list("中国123"))
        self.assertNotEqual(evaluation.tokens("一百", "zh"), evaluation.tokens("100", "zh"))
        self.assertNotEqual(evaluation.tokens("國", "zh"), evaluation.tokens("国", "zh"))

    def test_percentiles(self):
        self.assertEqual(evaluation.percentile([4, 1, 3, 2], .5), 2)
        self.assertEqual(evaluation.percentile([4, 1, 3, 2], .95), 4)


if __name__ == "__main__":
    unittest.main()
