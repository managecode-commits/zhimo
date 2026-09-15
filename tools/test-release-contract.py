#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Host-independent release checks. Not a substitute for Windows installation."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('packager', ROOT / 'tools/package-windows-test.py')
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


class ReleaseContract(unittest.TestCase):
    def test_dotted_archive_name_is_preserved(self):
        self.assertEqual(str(packager.archive_path(Path('/tmp/zhimo-v0.1.2-preview.3-windows'))),
                         '/tmp/zhimo-v0.1.2-preview.3-windows.zip')

    def test_android_version_source(self):
        version = json.loads((ROOT / 'release/version.json').read_text())
        self.assertGreater(version['android_version_code'], 1)
        self.assertLess(version['android_version_code'], 2100000000)
        self.assertRegex(version['version'], r'^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.]+)?$')
        self.assertIn('versionCode = appVersionCode', (ROOT / 'platform/android-ime/app/build.gradle.kts').read_text())

    def test_installer_requires_explicit_trust_without_policy_bypass(self):
        script = (ROOT / 'platform/windows-tsf/install.cmd').read_text()
        self.assertIn('UNBLOCK', script)
        self.assertIn('MachinePolicy', script)
        self.assertIn('UserPolicy', script)
        self.assertNotIn('ExecutionPolicy Bypass', script)
        self.assertNotIn('Set-ExecutionPolicy', script)

    def test_prebuilt_binary_provenance_is_not_assumed(self):
        source = (ROOT / 'tools/package-windows-test.py').read_text()
        self.assertIn('"binary_source_verified": False', source)
        self.assertIn('"source_head": "unverified-prebuilt-inputs"', source)


if __name__ == '__main__':
    unittest.main()
