#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Disposable offline panel. stdout contains only explicitly confirmed UTF-8."""
import array
import ctypes
import hashlib
import os
from pathlib import Path
import select
import subprocess
import sys
import threading
import time
import gi
gi.require_version("Gtk", "3.0")
from gi.repository import Gtk, GLib
from handwriting_panel import HandwritingPanel
from desktop_support import panel_lease, input_devices, audio_level


def paths():
    import sysconfig
    installed = Path(__file__).resolve().parent.parent
    if (installed / "lib/libime_ffi.so").exists() and (installed / "models").exists():
        return installed / "lib/libime_ffi.so", installed / "lib/libzhimo_speech.so", installed / "models"
    root = Path(__file__).resolve().parents[2]
    if (root / "models/speech/manifest.json").exists():
        return (Path(os.environ.get("ZHIMO_IME_LIBRARY", root / "target/release/libime_ffi.so")),
                root / "target/shared-speech/libzhimo_speech.so", root / "models")
    libraries = Path("/usr/lib") / sysconfig.get_config_var("MULTIARCH") / "zhimo"
    return libraries / "libime_ffi.so", libraries / "libzhimo_speech.so", Path("/usr/share/zhimo/models")


def verified_model(path, digest):
    with path.open("rb") as source:
        checksum = hashlib.sha256()
        for block in iter(lambda: source.read(65536), b""): checksum.update(block)
    if checksum.hexdigest() != digest:
        raise RuntimeError("离线模型校验失败，请重新安装完整模型包")
    return str(path).encode()


def transcribe(library, models, pcm, language):
    model = verified_model(models / "speech/ggml-base-q5_1.bin", "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898")
    vad = verified_model(models / "speech/ggml-silero-v5.1.2.bin", "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf")
    lib = ctypes.CDLL(str(library))
    lib.zhimo_speech_create.restype = ctypes.c_uint64
    lib.zhimo_speech_release.argtypes = [ctypes.c_uint64]
    lib.zhimo_speech_text_free.argtypes = [ctypes.c_void_p]
    lib.zhimo_speech_transcribe_options.argtypes = [ctypes.c_uint64, ctypes.c_char_p,
        ctypes.POINTER(ctypes.c_float), ctypes.c_size_t, ctypes.c_char_p, ctypes.c_char_p,
        ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
    samples = array.array("h", pcm)
    if sys.byteorder != "little": samples.byteswap()
    values = (ctypes.c_float * len(samples))(*(n / 32768.0 for n in samples))
    session = lib.zhimo_speech_create()
    output = ctypes.c_void_p()
    try:
        code = lib.zhimo_speech_transcribe_options(session, model, values, len(values),
                    language.encode(), b"", vad, ctypes.byref(output))
        if code: raise RuntimeError(f"转录失败（{code}），请重试或检查模型")
        return ctypes.string_at(output).decode("utf-8") if output.value else ""
    finally:
        if output.value: lib.zhimo_speech_text_free(output)
        lib.zhimo_speech_release(session)


class SpeechPanel:
    def __init__(self, library, models, commit):
        self.library, self.models, self.commit = library, models, commit
        self.closed, self.busy = False, False
        self.stop = threading.Event()
        self.text = ""
        self.devices = [(None, "系统默认麦克风")]
        self.device_index = 0
        self.window = Gtk.Window(title="知墨 · 离线语音输入")
        self.window.set_accept_focus(False)
        self.window.set_focus_on_map(False)
        self.window.set_default_size(480, 230)
        self.window.connect("delete-event", lambda *_: self.close() or True)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin=12)
        self.window.add(box)
        self.status = Gtk.Label(label="点击录音 · 最长 60 秒 · 仅在确认后上屏")
        box.pack_start(self.status, False, False, 0)
        self.device_button = Gtk.Button(label="麦克风：系统默认 · 点击切换")
        self.device_button.set_can_focus(False)
        self.device_button.connect("clicked", lambda *_: self.next_device())
        box.pack_start(self.device_button, False, False, 0)
        self.level = Gtk.ProgressBar()
        self.level.set_show_text(True)
        self.level.set_text("输入音量")
        box.pack_start(self.level, False, False, 0)
        refresh = Gtk.Button(label="刷新麦克风列表")
        refresh.set_can_focus(False)
        refresh.connect("clicked", lambda *_: self.refresh_devices())
        box.pack_start(refresh, False, False, 0)
        self.preview = Gtk.Label(label="识别结果将在这里显示")
        self.preview.set_line_wrap(True)
        self.preview.set_max_width_chars(50)
        box.pack_start(self.preview, True, True, 0)
        row = Gtk.Box(spacing=6)
        for label, callback in [("中文录音", lambda: self.record("zh")),
                                ("英文录音", lambda: self.record("en")),
                                ("停止并识别", self.stop.set),
                                ("确认上屏", self.confirm), ("取消", self.close)]:
            button = Gtk.Button(label=label)
            button.set_can_focus(False)
            button.connect("clicked", lambda _, fn=callback: fn())
            row.pack_start(button, True, True, 0)
        box.pack_start(row, False, False, 0)
        self.window.show_all()

    def next_device(self):
        if self.busy or self.closed: return
        self.device_index = (self.device_index + 1) % len(self.devices)
        self.device_button.set_label("麦克风：" + self.devices[self.device_index][1] + " · 点击切换")

    def refresh_devices(self):
        if self.busy or self.closed: return
        def work():
            try: GLib.idle_add(self.set_devices, input_devices(), "麦克风列表已更新")
            except Exception: GLib.idle_add(self.set_devices, [], "无法读取音频设备，请检查 PulseAudio／PipeWire 音频服务")
        threading.Thread(target=work, daemon=True).start()

    def set_devices(self, devices, message):
        if not self.closed and not self.busy:
            self.devices = [(None, "系统默认麦克风")] + devices
            self.device_index = -1
            self.next_device()
            self.status.set_text(message)
        return False

    def update_level(self, value):
        if not self.closed and self.busy: self.level.set_fraction(value)
        return False

    def record(self, language):
        if self.busy or self.closed: return
        self.busy = True
        self.text = ""
        self.preview.set_text("")
        self.stop.clear()
        device = self.devices[self.device_index][0]
        self.level.set_fraction(0)
        self.status.set_text("正在录音；点击停止，或等待 60 秒自动停止")
        def work():
            audio = bytearray()
            try:
                command = ["/usr/bin/parec", "--record", "--raw", "--format=s16le",
                        "--rate=16000", "--channels=1", "--latency-msec=100"]
                if device: command.append("--device=" + device)
                with subprocess.Popen(command,
                        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as capture:
                    try:
                        start = time.monotonic()
                        while not self.stop.is_set() and time.monotonic() - start < 60 and len(audio) < 1920000:
                            if capture.poll() is not None: raise RuntimeError("麦克风无法打开，请检查默认设备与权限")
                            if select.select([capture.stdout], [], [], 0.1)[0]:
                                chunk = os.read(capture.stdout.fileno(), min(8192, 1920000 - len(audio)))
                                if not chunk: raise RuntimeError("麦克风连接中断")
                                audio.extend(chunk)
                                GLib.idle_add(self.update_level, audio_level(chunk))
                    finally:
                        capture.terminate()
                        try: capture.wait(timeout=2)
                        except subprocess.TimeoutExpired: capture.kill(); capture.wait()
                if self.closed: return
                if len(audio) < 3200: raise RuntimeError("录音太短，请重试")
                GLib.idle_add(self.progress, "正在离线识别，可随时取消")
                text = transcribe(self.library, self.models, audio[:len(audio)//2*2], language)
                GLib.idle_add(self.finish, text, "请检查结果后确认上屏" if text else "未检测到有效语音，请重录")
            except Exception as error:
                GLib.idle_add(self.finish, "", str(error))
        threading.Thread(target=work, daemon=True).start()

    def progress(self, message):
        if not self.closed: self.status.set_text(message)
        return False

    def finish(self, text, message):
        if not self.closed:
            self.busy = False
            self.level.set_fraction(0)
            self.text = text
            self.preview.set_text(text)
            self.status.set_text(message)
        return False

    def confirm(self):
        if not self.closed and not self.busy and self.text: self.commit(self.text)

    def close(self):
        if self.closed: return
        self.closed = True
        self.stop.set()
        self.window.destroy()
        if Gtk.main_level(): Gtk.main_quit()


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("handwriting", "speech"): return 2
    try: lease = panel_lease()
    except BlockingIOError:
        print("已有 Zhimo 手写／语音面板，请先关闭它", file=sys.stderr)
        return 2
    except Exception as error:
        print(str(error), file=sys.stderr)
        return 2
    runtime, speech, models = paths()
    def commit(text):
        value = text.encode("utf-8")
        if not value or len(value) > 16384 or b"\0" in value: return False
        sys.stdout.buffer.write(value)
        sys.stdout.buffer.flush()
        Gtk.main_quit()
        return True
    if sys.argv[1] == "speech": panel = SpeechPanel(speech, models, commit)
    else:
        panel = HandwritingPanel(ctypes.CDLL(str(runtime)), commit)
        panel.model = models / "handwriting/zh-cn/handwriting-zh_CN.model"
        panel.window.connect("destroy", lambda *_: Gtk.main_quit() if Gtk.main_level() else None)
    Gtk.main()
    panel.close()
    os.close(lease)
    return 0


if __name__ == "__main__":
    code = main()
    sys.stdout.flush()
    os._exit(code)  # no waiting for cancelled inference during process teardown
