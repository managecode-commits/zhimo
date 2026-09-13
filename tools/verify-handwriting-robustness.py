#!/usr/bin/env python3
"""Synthetic perturbations of training-source ink; NOT real-user accuracy.

Usage: python3 tools/verify-handwriting-robustness.py NEW_LIBRARY [OLD_LIBRARY]
"""
import ctypes
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "models/handwriting/zh-cn/handwriting-zh_CN.model"


def evaluate(path):
    library = ctypes.CDLL(str(Path(path).resolve()))
    library.ime_handwriting_new.argtypes = [ctypes.c_char_p]
    library.ime_handwriting_new.restype = ctypes.c_void_p
    library.ime_handwriting_free.argtypes = [ctypes.c_void_p]
    library.ime_handwriting_recognize_json.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    library.ime_handwriting_recognize_json.restype = ctypes.c_char_p
    handle = library.ime_handwriting_new(str(MODEL).encode())
    assert handle
    wanted = set("一二三人大中国你好字语输入键盘手写识别文本测试")
    entries = [e for e in ET.parse(MODEL.parent / "source/handwriting-zh_CN.xml").getroot().findall("character")
               if e.findtext("utf8") in wanted]
    variants = {
        "original": (1, 0, 0, 1000, 1000),
        "small": (.25, 300, 350, 1000, 1000),
        "wide_canvas": (.5, 650, 10, 1200, 520),
        "edge_small": (.125, 1000, 150, 1200, 300),
        "jitter": (1, 0, 0, 1000, 1000),
        "reverse_stroke_order": (1, 0, 0, 1000, 1000),
    }
    report = {}
    try:
        for name, (scale, dx, dy, width, height) in variants.items():
            counts = dict(top1=0, top5=0, top20=0, top100=0)
            missing = []
            for entry in entries:
                strokes = []
                for stroke in entry.findall("strokes/stroke"):
                    points = []
                    for i, p in enumerate(stroke.findall("point")):
                        jitter = ((i % 3) - 1) * 12 if name == "jitter" else 0
                        points.append(dict(x=max(0, min(width, int(p.attrib["x"]) * scale + dx + jitter)),
                                           y=max(0, min(height, int(p.attrib["y"]) * scale + dy - jitter)), time_ms=i))
                    strokes.append(points)
                if name == "reverse_stroke_order":
                    strokes.reverse()
                response = library.ime_handwriting_recognize_json(handle, json.dumps(dict(width=width, height=height, strokes=strokes)).encode())
                assert response, name
                choices = [c["text"] for c in json.loads(response)]
                label = entry.findtext("utf8")
                for k in (1, 5, 20, 100):
                    counts[f"top{k}"] += label in choices[:k]
                if label not in choices:
                    missing.append(label)
            report[name] = dict(samples=len(entries), **counts, missing="".join(missing))
    finally:
        library.ime_handwriting_free(handle)
    return report


if __name__ == "__main__":
    result = {"new": evaluate(sys.argv[1]), "note": "Synthetic training-source perturbations, not held-out accuracy."}
    if len(sys.argv) > 2:
        result["old"] = evaluate(sys.argv[2])
    print(json.dumps(result, ensure_ascii=False, indent=2))
