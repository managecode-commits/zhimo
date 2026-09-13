"""Non-focus-taking GTK single-character pad using the shared, offline C ABI.

The owner must close this pad on editor focus loss or a sensitive-field transition.
No clipboard, simulated keystrokes, networking or persistent ink.
"""
import ctypes
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, GLib, Gtk


class HandwritingPanel:
    def __init__(self, library, commit):
        self.library, self.commit = library, commit
        self.library.ime_handwriting_new.argtypes = [ctypes.c_char_p]
        self.library.ime_handwriting_new.restype = ctypes.c_void_p
        self.library.ime_handwriting_free.argtypes = [ctypes.c_void_p]
        self.library.ime_handwriting_recognize_json.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        self.library.ime_handwriting_recognize_json.restype = ctypes.c_char_p
        self.executor = ThreadPoolExecutor(max_workers=1)
        self.handle = None  # worker-owned
        self.closed, self.busy = False, False
        self.revision, self.timer = 0, None
        self.strokes, self.pending = [], []
        development = Path(__file__).resolve().parents[2] / "models/handwriting/zh-cn/handwriting-zh_CN.model"
        installed = Path(__file__).resolve().parent.parent / "models/handwriting/zh-cn/handwriting-zh_CN.model"
        self.model = development if development.exists() else installed
        self.window = Gtk.Window(title="知墨 Zhimo 离线手写 · 一次写一字")
        self.window.set_accept_focus(False)
        self.window.set_focus_on_map(False)
        self.window.set_skip_taskbar_hint(True)
        self.window.set_type_hint(Gdk.WindowTypeHint.UTILITY)
        self.window.connect("delete-event", lambda *_: self.close() or True)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.window.add(box)
        self.status = Gtk.Label(label="内置模型；写完后点选候选上屏")
        box.pack_start(self.status, False, False, 0)
        self.choices = Gtk.Box(spacing=4)
        box.pack_start(self.choices, False, False, 0)
        self.canvas = Gtk.DrawingArea()
        self.canvas.set_size_request(380, 260)
        self.canvas.add_events(Gdk.EventMask.BUTTON_PRESS_MASK | Gdk.EventMask.BUTTON_RELEASE_MASK | Gdk.EventMask.POINTER_MOTION_MASK)
        self.canvas.connect("draw", self.draw)
        self.canvas.connect("button-press-event", self.down)
        self.canvas.connect("motion-notify-event", self.move)
        self.canvas.connect("button-release-event", self.up)
        box.pack_start(self.canvas, True, True, 0)
        actions = Gtk.Box(spacing=8)
        for label, callback in [("撤一笔", self.undo), ("清空", self.clear), ("返回键盘", self.close)]:
            button = Gtk.Button(label=label)
            button.set_can_focus(False)
            button.connect("clicked", lambda _, action=callback: action())
            actions.pack_start(button, True, True, 0)
        box.pack_start(actions, False, False, 0)
        self.window.show_all()

    def invalidate(self):
        self.revision += 1
        if self.timer is not None:
            GLib.source_remove(self.timer)
            self.timer = None
        for button in self.choices.get_children():
            self.choices.remove(button)

    def point(self, event):
        return dict(x=max(0, min(event.x, self.canvas.get_allocated_width())),
                    y=max(0, min(event.y, self.canvas.get_allocated_height())), time_ms=GLib.get_monotonic_time() // 1000)

    def down(self, _, event):
        if event.button != 1 or len(self.strokes) >= 64:
            return False
        self.invalidate()
        self.pending = [self.point(event)]
        return True

    def move(self, _, event):
        if self.pending and len(self.pending) < 4096 and sum(map(len, self.strokes)) + len(self.pending) < 32768:
            self.pending.append(self.point(event))
            self.canvas.queue_draw()
        return True

    def up(self, _, event):
        if event.button == 1 and self.pending:
            self.move(None, event)
            self.strokes.append(self.pending)
            self.pending = []
            self.invalidate()
            self.timer = GLib.timeout_add(400, self.recognize)
        return True

    def draw(self, _, cr):
        cr.set_source_rgb(1, 1, 1); cr.paint()
        cr.set_source_rgb(0.15, 0.15, 0.15); cr.set_line_width(3)
        for stroke in self.strokes + [self.pending]:
            for i, point in enumerate(stroke):
                (cr.move_to if i == 0 else cr.line_to)(point["x"], point["y"])
            cr.stroke()

    def recognize(self):
        self.timer = None
        if self.closed or not self.strokes or self.pending:
            return False
        if self.busy:
            self.timer = GLib.timeout_add(100, self.recognize)
            return False
        token = self.revision
        request = json.dumps(dict(width=self.canvas.get_allocated_width(), height=self.canvas.get_allocated_height(), strokes=self.strokes)).encode()
        self.busy = True
        def work():
            if not self.handle:
                self.handle = self.library.ime_handwriting_new(str(self.model).encode())
            if not self.handle:
                raise RuntimeError("model unavailable")
            result = self.library.ime_handwriting_recognize_json(self.handle, request)
            if not result:
                raise RuntimeError("invalid ink")
            return json.loads(result)
        def finish(future):
            self.busy = False
            if self.closed or token != self.revision:
                return False
            try:
                values = future.result()
            except Exception:
                self.status.set_text("内置模型不可用或笔迹超限，请清空重试")
                return False
            for candidate in values[:5]:
                text = candidate["text"]
                button = Gtk.Button(label=text)
                button.set_can_focus(False)
                def select(_, value=text):
                    if not self.closed and token == self.revision and self.commit(value):
                        self.clear()
                button.connect("clicked", select)
                self.choices.pack_start(button, True, True, 0)
            self.choices.show_all()
            return False
        self.executor.submit(work).add_done_callback(lambda future: GLib.idle_add(finish, future))
        return False

    def clear(self):
        self.invalidate(); self.strokes = []; self.pending = []; self.canvas.queue_draw()

    def undo(self):
        self.invalidate(); self.pending = []
        if self.strokes: self.strokes.pop()
        self.canvas.queue_draw()
        self.timer = GLib.timeout_add(400, self.recognize)

    def close(self):
        if self.closed: return
        self.closed = True
        self.clear(); self.window.destroy()
        def release():
            if self.handle: self.library.ime_handwriting_free(self.handle)
            self.handle = None
        self.executor.submit(release)
        self.executor.shutdown(wait=False)
