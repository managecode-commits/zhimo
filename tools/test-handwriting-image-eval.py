#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Run in the isolated image-evaluation environment; does not load user images."""
import itertools
from pathlib import Path
import runpy
import unittest
import numpy as np

MODULE = runpy.run_path(str(Path(__file__).with_name("evaluate-handwriting-image.py")))


class ImageEvaluationTest(unittest.TestCase):
    def test_ctc_matches_enumerated_paths(self):
        probabilities = np.array([[.5, .3, .2], [.1, .7, .2], [.6, .1, .3]])
        expected = np.zeros(2)
        for path in itertools.product(range(3), repeat=3):
            tokens = [c for i, c in enumerate(path) if c and (i == 0 or c != path[i-1])]
            if len(tokens) == 1:
                expected[tokens[0]-1] += np.prod([probabilities[i, c] for i, c in enumerate(path)])
        np.testing.assert_allclose(MODULE["single_character_ctc"](probabilities), expected)

    def test_writer_split_and_label_guards(self):
        case = dict(id="a", label="制", writer="one", split="development")
        MODULE["validate_cases"]([case])
        for invalid in [[case, case], [case, dict(case, id="b", split="train")],
                        [dict(case, split="test")], [dict(case, label="制清")]]:
            with self.assertRaises(ValueError):
                MODULE["validate_cases"](invalid)
        MODULE["validate_cases"]([dict(case, split="test", label_confirmed=True)])


if __name__ == "__main__":
    unittest.main()
