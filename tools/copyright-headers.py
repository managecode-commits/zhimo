#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Check or mechanically add headers to first-party source and documentation."""

import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
NOTICE = "Copyright © 2026 立方田 <managecode@gmail.com>"
ROOTS = {"apps", "crates", "services", "platform", "include", "tools", "docs", ".github"}
EXCLUDED_PARTS = {"third-party", "vendor", "assets", "jniLibs", "build", "target", "data", "generated", ".gradle", ".cxx", "__pycache__"}
EXCLUDED_FILES = {
    "platform/android-ime/gradlew",
    "platform/android-ime/gradlew.bat",
    "crates/ime-handwriting/src/zinnia.rs",  # BSD-derived implementation.
    "Cargo.lock",
}


def style(path):
    if path.as_posix() in EXCLUDED_FILES or EXCLUDED_PARTS.intersection(path.parts):
        return None
    if len(path.parts) > 1 and path.parts[0] not in ROOTS:
        return None
    if path.name == "CMakeLists.txt" or path.suffix in {".py", ".sh", ".ps1", ".toml", ".yml", ".yaml"}:
        return "# "
    if path.suffix in {".rs", ".c", ".h", ".cpp", ".hpp", ".kt", ".kts", ".swift"}:
        return "// "
    if path.suffix in {".md", ".xml", ".plist"}:
        return "xml"
    if path.suffix in {".cmd", ".bat"}:
        return "@rem "
    return None


def with_header(raw, kind):
    bom = b"\xef\xbb\xbf" if raw.startswith(b"\xef\xbb\xbf") else b""
    text = raw[len(bom):].decode("utf-8")
    # CMD parses angle brackets as redirection even on REM lines.
    if kind == "@rem ":
        safe_notice = NOTICE.replace("<", "(").replace(">", ")")
        if kind + NOTICE in text:
            return bom + text.replace(kind + NOTICE, kind + safe_notice, 1).encode("utf-8")
        if kind + safe_notice in text:
            return raw
    if NOTICE in text:
        return raw
    newline = "\r\n" if "\r\n" in text else "\n"
    # Keep the XML declaration, executable shebang and Python encoding cookie first.
    lines = text.splitlines(keepends=True)
    offset = 0
    if lines and (lines[0].startswith("#!") or lines[0].startswith("<?xml")):
        offset = 1
    if kind == "# ":
        for index, line in enumerate(lines[:2]):
            if re.match(r"^\s*#.*coding[:=]\s*[-\w.]+", line):
                offset = max(offset, index + 1)
    header = "<!-- " + NOTICE.replace("<", "&lt;").replace(">", "&gt;") + " -->" if kind == "xml" else kind + NOTICE
    if kind == "@rem ":
        header = kind + safe_notice
    # XML comments use escaped angle brackets; make repeat execution idempotent.
    if header in text:
        return raw
    return bom + ("".join(lines[:offset]) + header + newline + "".join(lines[offset:])).encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="add missing headers; default is read-only verification")
    args = parser.parse_args()
    files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT)
    missing = []
    checked = 0
    for name in sorted(set(files.decode("utf-8").split("\0")) - {""}):
        relative = Path(name)
        path = ROOT / relative
        kind = style(relative)
        if kind is None or not path.is_file() or path.is_symlink():
            continue
        checked += 1
        raw = path.read_bytes()
        updated = with_header(raw, kind)
        if updated != raw:
            missing.append(name)
            if args.apply:
                path.write_bytes(updated)
    print(f"Checked {checked} files; {'updated' if args.apply else 'missing'} {len(missing)} headers.")
    for name in missing:
        print(name)
    return 0 if args.apply or not missing else 1


if __name__ == "__main__":
    raise SystemExit(main())
