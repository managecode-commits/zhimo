# Copyright © 2026 立方田 <managecode@gmail.com>
import importlib.util
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("stage_ibus", ROOT / "tools/stage-linux-ibus.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class StagingTest(unittest.TestCase):
    def test_payload_and_escaped_prefix(self):
        with tempfile.TemporaryDirectory(prefix="zhimo-stage-test-") as temporary:
            destination = pathlib.Path(temporary) / "payload"
            component = module.stage(ROOT, ROOT / "target/debug/libime_ffi.so",
                                     destination, "/opt/Zhimo & input")
            self.assertEqual(ET.parse(component).findtext("exec"),
                             "'/opt/Zhimo & input/share/zhimo/bin/zhimo-ibus' --ibus")
            root = destination / "opt/Zhimo & input/share/zhimo"
            self.assertTrue((root / "lib/libime_ffi.so").is_file())
            self.assertTrue((root / "bin/handwriting_panel.py").is_file())
            self.assertTrue((root / "models/handwriting/zh-cn/handwriting-zh_CN.model").is_file())
            self.assertTrue((root / "LICENSE").is_file())
            with self.assertRaises(FileExistsError):
                module.stage(ROOT, ROOT / "target/debug/libime_ffi.so", destination, "/usr")

    def test_reject_unsafe_prefix(self):
        for prefix in ["/", "relative", "/usr/../etc"]:
            with self.assertRaises(ValueError):
                module.stage(ROOT, ROOT / "target/debug/libime_ffi.so", "/unused", prefix)


if __name__ == "__main__":
    unittest.main()
