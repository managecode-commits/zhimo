# Copyright © 2026 立方田 <managecode@gmail.com>
"""Private child-pipe IPC. Parent cancels on editor/context changes."""
import os
from pathlib import Path
import signal
import subprocess
import threading
import time
from gi.repository import GLib


class DesktopClient:
    def __init__(self, mode, commit, eligible=lambda: True):
        self.closed = False
        self.commit = commit
        self.eligible = eligible
        self.output = bytearray()
        self.started = time.monotonic()
        self.process = subprocess.Popen(["/usr/bin/python3", str(Path(__file__).with_name("desktop_panel.py")), mode],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, start_new_session=True)
        os.set_blocking(self.process.stdout.fileno(), False)
        self.timer = GLib.timeout_add(50, self.poll)

    def poll(self):
        if self.closed: return False
        if not self.eligible(): self.close(); return False
        eof = False
        try:
            while True:
                data = os.read(self.process.stdout.fileno(), 4096)
                if not data: eof = True; break
                self.output.extend(data)
                if len(self.output) > 16384: raise ValueError("oversize result")
        except BlockingIOError: pass
        except Exception: self.close(); return False
        status = self.process.poll()
        if status is None or not eof:
            if time.monotonic() - self.started > 300: self.close(); return False
            return True
        try:
            text = self.output.decode("utf-8")
            if status == 0 and text and "\0" not in text: self.commit(text)
        except UnicodeError: pass
        self.timer = None
        self.close()
        return False

    def close(self):
        if self.closed: return
        self.closed = True
        if self.timer is not None: GLib.source_remove(self.timer); self.timer = None
        try: os.killpg(self.process.pid, signal.SIGKILL)
        except ProcessLookupError: pass
        self.process.stdout.close()
        threading.Thread(target=self.process.wait, daemon=True).start()
