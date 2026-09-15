#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deb_packager', Path(__file__).with_name('package-linux-deb.py'))
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


class PackageTests(unittest.TestCase):
    def test_version_numeric_comparison(self):
        self.assertLess(packager.version_tuple('2.9'), packager.version_tuple('2.36'))

    def test_invalid_version_rejected(self):
        for value in ['2', '2.36.1', '2.36;true', '../2.36']:
            with self.assertRaises(ValueError):
                packager.version_tuple(value)

    @patch.object(packager, 'run', return_value='GLIBC_2.9 GLIBC_2.36 GLIBC_2.17 GLIBCXX_3.4.30')
    def test_symbol_ceiling(self, _):
        self.assertEqual(packager.required_glibc(Path('fixture.so')), (2, 36))


if __name__ == '__main__':
    unittest.main()
