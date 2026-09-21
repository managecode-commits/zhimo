# Copyright © 2026 立方田 <managecode@gmail.com>
"""Offline, worker-owned image/trajectory fusion. No editor access or ink storage."""
import ctypes
from dataclasses import dataclass, field
import hashlib
import json
import math
from pathlib import Path
import sys

MODEL_SHA256 = "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5"


def validate(ink):
    width, height, strokes = ink["width"], ink["height"], ink["strokes"]
    if not all(math.isfinite(v) and v > 0 for v in (width, height)):
        raise ValueError("invalid canvas")
    if not 1 <= len(strokes) <= 64 or sum(map(len, strokes)) > 32768:
        raise ValueError("invalid stroke count")
    for stroke in strokes:
        if not 1 <= len(stroke) <= 4096:
            raise ValueError("invalid stroke")
        previous = -1
        for p in stroke:
            if not all(math.isfinite(p[k]) and 0 <= p[k] <= bound for k, bound in (("x", width), ("y", height))):
                raise ValueError("invalid coordinate")
            time = p.get("time_ms", 0)
            if not isinstance(time, int) or time < previous:
                raise ValueError("invalid time")
            previous = time


def is_han(text):
    return len(text) == 1 and ("\u3400" <= text <= "\u9fff" or "\uf900" <= text <= "\ufaff" or "\U00020000" <= text <= "\U0002fa1f")


def merge(image, trajectory):
    scores = {}
    for values in (image, trajectory):
        for index, value in enumerate(dict.fromkeys(v for v in values if is_han(v))):
            scores[value] = scores.get(value, 0) + 1 / (8 + index)
    return sorted(scores, key=lambda value: -scores[value])[:100]


def render(ink):
    """Android-equivalent 512 square, 8px ink, integer area average to 48 square."""
    import cairo
    import numpy as np
    validate(ink)
    points = [p for stroke in ink["strokes"] for p in stroke]
    left, right = min(p["x"] for p in points), max(p["x"] for p in points)
    top, bottom = min(p["y"] for p in points), max(p["y"] for p in points)
    scale = 400 / max(right - left, bottom - top, 1)
    cx, cy = (left + right) / 2, (top + bottom) / 2
    surface = cairo.ImageSurface(cairo.FORMAT_RGB24, 512, 512)
    cr = cairo.Context(surface)
    cr.set_source_rgb(1, 1, 1); cr.paint()
    cr.set_source_rgb(0, 0, 0); cr.set_line_width(8)
    cr.set_line_cap(cairo.LINE_CAP_ROUND); cr.set_line_join(cairo.LINE_JOIN_ROUND)
    for stroke in ink["strokes"]:
        coords = [((p["x"] - cx) * scale + 256, (p["y"] - cy) * scale + 256) for p in stroke]
        if len(set(coords)) == 1:
            cr.arc(*coords[0], 4, 0, math.tau); cr.fill()
        else:
            cr.move_to(*coords[0])
            for point in coords[1:]: cr.line_to(*point)
            cr.stroke()
    surface.flush()
    pixels = np.frombuffer(surface.get_data(), dtype=np.uint8).reshape(512, surface.get_stride())
    # RGB channels are equal for grayscale, independent of Cairo byte order.
    gray = pixels[:, :2048].reshape(512, 512, 4)[:, :, 1]
    tensor = np.zeros((1, 3, 48, 320), dtype=np.float32)
    for y in range(48):
        for x in range(48):
            value = gray[y*512//48:(y+1)*512//48, x*512//48:(x+1)*512//48].mean() / 127.5 - 1
            tensor[0, :, y, x] = value
    return tensor


def decode(frames, labels):
    import numpy as np
    if frames.ndim != 2 or frames.shape[1] != len(labels) or not len(frames) or not np.isfinite(frames).all() or (frames < 0).any() or (frames > 1).any():
        raise ValueError("invalid probabilities")
    active = np.zeros(len(labels) - 1, dtype=np.float64)
    ended = active.copy()
    before = 1.0
    for row in frames:
        ended = (ended + active) * row[0]
        active = (active + before) * row[1:]
        before *= row[0]
    scores = active + ended
    indices = [i for i, label in enumerate(labels[1:]) if is_han(label) and scores[i] > 0]
    return [labels[i + 1] for i in sorted(indices, key=lambda i: (-scores[i], i))[:100]]


class ImageRecognizer:
    def __init__(self, model):
        private = Path(__file__).resolve().parent.parent / "python"
        if private.is_dir() and str(private) not in sys.path:
            sys.path.insert(0, str(private))
        import onnxruntime as ort
        if hashlib.sha256(model.read_bytes()).hexdigest() != MODEL_SHA256:
            raise ValueError("image model checksum mismatch")
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(model), options, providers=["CPUExecutionProvider"])
        self.labels = [""] + self.session.get_modelmeta().custom_metadata_map["character"].rstrip("\r\n").splitlines() + [" "]

    def recognize(self, ink):
        output = self.session.run(None, {self.session.get_inputs()[0].name: render(ink)})[0][0]
        return decode(output, self.labels)


@dataclass
class Recognition:
    choices: list
    status: str
    parts: dict = field(default_factory=dict)


class FusedRecognizer:
    def __init__(self, library, model):
        self.library, self.model = library, model
        library.ime_handwriting_new.argtypes = [ctypes.c_char_p]
        library.ime_handwriting_new.restype = ctypes.c_void_p
        library.ime_handwriting_free.argtypes = [ctypes.c_void_p]
        library.ime_handwriting_recognize_json.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        library.ime_handwriting_recognize_json.restype = ctypes.c_char_p
        self.handle = library.ime_handwriting_new(str(model).encode())
        try: self.image = ImageRecognizer(model.parents[2] / "handwriting-image/pp-ocrv5-mobile-rec.onnx")
        except Exception: self.image = None

    def recognize(self, ink):
        validate(ink)
        image, trajectory = [], []
        image_ok = trajectory_ok = False
        if self.image:
            try: image = self.image.recognize(ink); image_ok = True
            except Exception: pass
        if self.handle:
            try:
                raw = self.library.ime_handwriting_recognize_json(self.handle, json.dumps(ink).encode())
                trajectory = [item["text"] for item in json.loads(raw)]
                trajectory_ok = True
            except Exception: pass
        if not image_ok and not trajectory_ok:
            raise RuntimeError("离线模型均不可用，请检查安装包")
        status = "字形＋笔迹融合" if image_ok and trajectory_ok else "仅字形识别（笔迹引擎不可用）" if image_ok else "仅笔迹识别（字形引擎不可用，请升级完整核心包）"
        return Recognition(merge(image, trajectory), status)

    def close(self):
        self.image = None
        if self.handle: self.library.ime_handwriting_free(self.handle)
        self.handle = None
