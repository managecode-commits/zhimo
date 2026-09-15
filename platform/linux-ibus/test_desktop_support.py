# Copyright © 2026 立方田 <managecode@gmail.com>
import os
import tempfile
import unittest
from unittest.mock import patch
from desktop_support import panel_lease, input_devices, audio_level


class SupportTests(unittest.TestCase):
    def test_exclusive_and_release(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, XDG_RUNTIME_DIR=directory):
            first = panel_lease()
            try:
                with self.assertRaises(BlockingIOError): panel_lease()
            finally: os.close(first)
            second = panel_lease(); os.close(second)

    def test_unsafe_runtime(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, XDG_RUNTIME_DIR=directory):
            os.chmod(directory, 0o755)
            with self.assertRaises(RuntimeError): panel_lease()

    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, XDG_RUNTIME_DIR=directory):
            os.symlink("/dev/null", directory + "/zhimo-desktop-panel.lock")
            with self.assertRaises(OSError): panel_lease()

    def test_level(self):
        self.assertEqual(audio_level(b""), 0)
        self.assertEqual(audio_level(b"\0\0"*20), 0)
        self.assertAlmostEqual(audio_level(b"\0\x40"*20), .5)
        self.assertEqual(audio_level(b"\0"), 0)

    def test_devices_no_monitor(self):
        with patch("desktop_support.subprocess.run") as run:
            run.return_value.stdout = '[{"name":"mic","description":"USB"},{"name":"output.monitor"}]'
            self.assertEqual(input_devices(), [("mic", "USB")])
            self.assertEqual(run.call_args.kwargs["timeout"], 3)


if __name__ == "__main__": unittest.main()
