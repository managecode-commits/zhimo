#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("runtime", Path(__file__).with_name("prepare-streaming-speech-runtime.py"))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeValidationTest(unittest.TestCase):
    def archive(self, directory, missing=False, unsafe=False):
        path = Path(directory) / "fixture.aar"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.jar", b"test fixture, not an executable runtime")
            for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
                if not (missing and abi == "armeabi-v7a"):
                    archive.writestr(f"jni/{abi}/libsherpa-onnx-jni.so", b"fixture")
            if unsafe:
                archive.writestr("../outside", b"fixture")
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        return path, digest

    def test_correct_structure(self):
        with tempfile.TemporaryDirectory() as directory:
            path, digest = self.archive(directory)
            runtime.validate(path, digest)

    def test_wrong_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            path, _ = self.archive(directory)
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                runtime.validate(path, "0" * 64)

    def test_missing_abi(self):
        with tempfile.TemporaryDirectory() as directory:
            path, digest = self.archive(directory, missing=True)
            with self.assertRaisesRegex(ValueError, "ABI"):
                runtime.validate(path, digest)

    def test_unsafe_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            path, digest = self.archive(directory, unsafe=True)
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                runtime.validate(path, digest)


if __name__ == "__main__":
    unittest.main()
