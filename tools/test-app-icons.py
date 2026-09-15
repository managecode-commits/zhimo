#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


class IconTests(unittest.TestCase):
    def test_png_sizes(self):
        for size in (16, 24, 32, 48, 64, 128, 256, 512, 1024):
            with Image.open(ROOT / f"assets/branding/zhimo-{size}.png") as icon:
                self.assertEqual(icon.size, (size, size))

    def test_windows_sizes(self):
        with Image.open(ROOT / "assets/branding/zhimo.ico") as icon:
            self.assertEqual(icon.ico.sizes(), {(n, n) for n in (16, 24, 32, 48, 64, 128, 256)})

    def test_apple_catalogs(self):
        for platform in ("ios-container", "macos-inputmethod"):
            folder = ROOT / "platform" / platform / "Assets.xcassets/AppIcon.appiconset"
            for entry in json.loads((folder / "Contents.json").read_text())["images"]:
                size = int(entry["size"].split("x")[0]) * int(entry.get("scale", "1x")[0])
                with Image.open(folder / entry["filename"]) as icon:
                    self.assertEqual(icon.size, (size, size))
                    self.assertEqual(icon.convert("RGBA").getextrema()[3], (255, 255))

    def test_android_theme_icon(self):
        res = ROOT / "platform/android-ime/app/src/main/res"
        for qualifier in ("mipmap-anydpi", "mipmap-anydpi-v33"):
            tree = ET.parse(res / qualifier / "ic_launcher.xml")
            self.assertEqual(len(tree.getroot().findall("foreground")), 1)
            if qualifier.endswith("v33"):
                self.assertEqual(len(tree.getroot().findall("monochrome")), 1)
        ET.parse(res / "drawable/ic_launcher_foreground.xml")


if __name__ == "__main__":
    unittest.main()
