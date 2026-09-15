#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""IPC tests require no GUI, microphone or editor registration."""
import subprocess
import sys
import time
import unittest
from unittest.mock import patch
from desktop_client import DesktopClient


class DesktopTests(unittest.TestCase):
    def run_child(self, code, cancel=False):
        popen = subprocess.Popen
        def child(_args, **kwargs): return popen([sys.executable, "-c", code], **kwargs)
        output=[]
        with patch("desktop_client.subprocess.Popen", child):
            client=DesktopClient("speech", output.append)
        if cancel: client.close()
        deadline=time.monotonic()+5
        while not client.closed and time.monotonic()<deadline:
            client.poll(); time.sleep(0.01)
        client.close()
        return output

    def test_confirmed_utf8(self):
        self.assertEqual(self.run_child("import sys;sys.stdout.buffer.write('你好'.encode())"), ["你好"])

    def test_nonzero_exit_cannot_commit(self):
        self.assertEqual(self.run_child("import sys;print('bad');sys.exit(1)"), [])

    def test_invalid_utf8_cannot_commit(self):
        self.assertEqual(self.run_child("import sys;sys.stdout.buffer.write(b'\\xff')"), [])

    def test_oversize_cannot_commit(self):
        self.assertEqual(self.run_child("print('a'*20000)"), [])

    def test_cancel_cannot_commit(self):
        self.assertEqual(self.run_child("import time;time.sleep(2);print('late')",True), [])

    def test_nul_cannot_commit(self):
        self.assertEqual(self.run_child("import sys;sys.stdout.buffer.write(b'a\\0b')"), [])

    def test_complete_result_drained(self):
        self.assertEqual(self.run_child("import sys,time;sys.stdout.write('a'*8000);sys.stdout.flush();time.sleep(.05);sys.stdout.write('b'*8000)"), ["a"*8000+"b"*8000])

    def test_lost_eligibility_cancels(self):
        popen = subprocess.Popen
        output = []
        with patch("desktop_client.subprocess.Popen", lambda _args, **kwargs: popen([sys.executable, "-c", "import time;time.sleep(.2);print('late')"], **kwargs)):
            client = DesktopClient("speech", output.append, lambda: False)
        self.assertFalse(client.poll())
        self.assertTrue(client.closed)
        self.assertEqual(output, [])


if __name__=="__main__": unittest.main()
