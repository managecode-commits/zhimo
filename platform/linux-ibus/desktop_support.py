# Copyright © 2026 立方田 <managecode@gmail.com>
"""Offline desktop helpers; no editor access or persistent audio/ink."""
import array
import fcntl
import json
import math
import os
from pathlib import Path
import stat
import subprocess
import sys


def panel_lease():
    directory = Path(os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}"))
    info = directory.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077 or not stat.S_ISDIR(info.st_mode):
        raise RuntimeError("不安全的运行目录，无法打开输入面板")
    fd = os.open(directory / "zhimo-desktop-panel.lock", os.O_CREAT | os.O_RDWR | os.O_CLOEXEC | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if info.st_uid != os.getuid() or not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
            raise RuntimeError("输入面板锁权限异常")
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return fd  # held until the disposable panel exits; never unlink a live lock
    except Exception:
        os.close(fd)
        raise


def input_devices():
    result = subprocess.run(["/usr/bin/pactl", "--format=json", "list", "sources"],
        capture_output=True, text=True, timeout=3, check=True)
    return [(item["name"], item.get("description") or item["name"])
        for item in json.loads(result.stdout)
        if isinstance(item.get("name"), str) and not item["name"].endswith(".monitor")]


def audio_level(pcm):
    values = array.array("h", pcm[:len(pcm)//2*2])
    if sys.byteorder != "little": values.byteswap()
    return min(1.0, math.sqrt(sum(float(v)*v for v in values)/len(values))/32768) if values else 0.0
