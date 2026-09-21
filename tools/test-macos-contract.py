#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Host-independent packaging contract checks, not Swift/IMK compilation."""
import pathlib
import plistlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAC = ROOT / "platform/macos-inputmethod"


class MacContract(unittest.TestCase):
    def test_candidate_action_matches_core(self):
        core = (ROOT / "crates/ime-core/src/types.rs").read_text()
        self.assertIn("ShowCandidates(Vec<Candidate>)", core)
        for name in ("InputController.swift", "CoreProbe.swift"):
            source = (MAC / name).read_text()
            self.assertIn('["ShowCandidates"]', source)
            self.assertNotIn('["UpdateCandidates"]', source)

    def test_metadata(self):
        info = plistlib.loads((MAC / "Info.plist").read_bytes())
        self.assertEqual(info["InputMethodServerControllerClass"], "ZhimoInputController")
        self.assertEqual(info["CFBundleIdentifier"], "dev.zhimo.inputmethod")
        self.assertTrue(info["LSBackgroundOnly"])
        self.assertTrue(info["NSMicrophoneUsageDescription"])
        entitlements = plistlib.loads((MAC / "Entitlements.plist").read_bytes())
        self.assertTrue(entitlements["com.apple.security.device.audio-input"])
        self.assertNotIn("com.apple.security.cs.disable-library-validation", entitlements)

    def test_packaged_sources_and_icons(self):
        for file in ("main.swift", "InputController.swift", "MacInputPanel.swift", "CoreProbe.swift", "install.command"):
            self.assertTrue((MAC / file).is_file())
        for size in (16,32,64,128,256,512,1024):
            self.assertTrue((MAC / f"Assets.xcassets/AppIcon.appiconset/icon-{size}.png").is_file())
        for name in ("ZhimoCore", "ZhimoSpeech"):
            module = ROOT / "platform/apple" / name / "module.modulemap"
            import re
            header = re.search(r'header "([^"]+)"', module.read_text())[1]
            self.assertTrue((module.parent / header).resolve().is_file())

    def test_build_contract(self):
        script = (ROOT / "tools/build-macos.sh").read_text()
        for required in ("aarch64-apple-darwin", "x86_64-apple-darwin", "CoreProbe.swift", "codesign --verify", "notarytool submit", "stapler staple"):
            self.assertIn(required, script)
        for forbidden in ("sudo ", "killall ", "rm -rf"):
            self.assertNotIn(forbidden, script)


if __name__ == "__main__": unittest.main()
