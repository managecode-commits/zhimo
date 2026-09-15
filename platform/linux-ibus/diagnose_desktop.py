#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Read-only installation checks; no editor text, recording or configuration changes."""
import os
from pathlib import Path
import shutil
import subprocess


def main():
    print("Zhimo Linux 只读诊断：不会录音、读取文档或修改配置")
    for key in ("XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", "GTK_IM_MODULE", "QT_IM_MODULE", "XMODIFIERS"):
        print(key + ": " + os.environ.get(key, "未设置"))
    for name in ("ibus", "fcitx5", "parec", "pactl"):
        print(name + ": " + ("已安装" if shutil.which(name) else "未找到"))
    for name in ("zhimo-core", "zhimo-ibus", "fcitx5-zhimo"):
        result = subprocess.run(["dpkg-query", "-W", "-f=${Status} ${Version}", name], capture_output=True, text=True, timeout=3)
        print(name + ": " + (result.stdout if result.returncode == 0 else "未安装"))
    active = []
    for name in ("ibus-daemon", "fcitx5"):
        result = subprocess.run(["pgrep", "-u", str(os.getuid()), "-x", name], capture_output=True, timeout=3)
        if result.returncode == 0: active.append(name)
    print("活动框架：" + ", ".join(active))
    if len(active) > 1: print("警告：检测到两种框架，请确认同一会话没有并行接管输入")
    for name in ("handwriting/zh-cn/handwriting-zh_CN.model", "speech/ggml-base-q5_1.bin", "speech/ggml-silero-v5.1.2.bin"):
        print(name + ": " + ("存在" if (Path("/usr/share/zhimo/models") / name).is_file() else "缺失"))
    print("若状态菜单不可见，请检查桌面输入源设置；Wayland 的面板焦点需在当前桌面实测。")


if __name__ == "__main__": main()
