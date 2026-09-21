#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("evaluation", Path(__file__).with_name("evaluate-streaming-speech.py"))
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


class EvaluationTest(unittest.TestCase):
    def test_valid_result(self):
        self.assertEqual(("你好", 0.25), evaluation.parse_result('noise\n{"text":"你好"}\nElapsed seconds: 0.25'))

    def test_empty_text_is_a_result(self):
        self.assertEqual(("", 1.0), evaluation.parse_result('{"text":""}\nElapsed seconds: 1'))

    def test_missing_timing_rejected(self):
        with self.assertRaisesRegex(ValueError, "timing"):
            evaluation.parse_result('{"text":"hello"}')

    def test_multiple_results_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            evaluation.parse_result('{"text":"a"}\n{"text":"b"}\nElapsed seconds: 1')

    def test_malformed_result_rejected(self):
        with self.assertRaises(ValueError):
            evaluation.parse_result('{broken\n{"text":5}\nElapsed seconds: 1')


if __name__ == "__main__":
    unittest.main()
