#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Deterministically export the checked-in SVG; requires cairosvg and Pillow."""
import io
import json
from pathlib import Path
import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "assets/branding"


def main():
    source = (BRAND / "zhimo.svg").read_text()
    def render(size, square=False):
        svg = source.replace('rx="23"', 'rx="0"') if square else source
        return cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)

    for size in (16, 24, 32, 48, 64, 128, 256, 512, 1024):
        (BRAND / f"zhimo-{size}.png").write_bytes(render(size))
    image = Image.open(io.BytesIO(render(256)))
    image.save(BRAND / "zhimo.ico", sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
    # App Store icons are opaque squares; the OS applies its own mask.
    for platform in ("ios-container", "macos-inputmethod"):
        dest = ROOT / "platform" / platform / "Assets.xcassets/AppIcon.appiconset"
        dest.mkdir(parents=True, exist_ok=True)
        entries = []
        specs = [("universal", "1024x1024", "1x")] if platform == "ios-container" else [
            ("mac", f"{n}x{n}", f"{scale}x") for n in (16, 32, 128, 256, 512) for scale in (1, 2)]
        for idiom, size, scale in specs:
            pixels = int(size.split("x")[0]) * int(scale[0])
            filename = f"icon-{pixels}.png"
            (dest / filename).write_bytes(render(pixels, square=True))
            entry = dict(idiom=idiom, size=size, filename=filename)
            if idiom == "universal":
                entry["platform"] = "ios"
            else:
                entry["scale"] = scale
            entries.append(entry)
        (dest / "Contents.json").write_text(json.dumps(dict(images=entries, info=dict(author="xcode", version=1)), indent=2) + "\n")
    print("Exported Zhimo PNG, ICO and Apple asset catalogs")


if __name__ == "__main__":
    main()
