#!/usr/bin/env python3
"""Deterministic model/ABI regression, NOT a held-out handwriting accuracy benchmark."""
import ctypes
import hashlib
import json
from pathlib import Path
import struct
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "models/handwriting/zh-cn/handwriting-zh_CN.model"
EXPECTED = "e16153d1ff267cd479aea260d6f71a3edda8b4ba06db2d121513adda65a4449e"


def main():
    if len(sys.argv) > 2:
        raise SystemExit("Usage: verify-handwriting-model.py [LIBRARY]. Production now normalizes ink; raw upstream parity is historical, not this pipeline's contract.")
    payload = MODEL.read_bytes()
    assert hashlib.sha256(payload).hexdigest() == EXPECTED
    class_count = struct.unpack_from("<I", payload, 8)[0]
    library = ctypes.CDLL(sys.argv[1] if len(sys.argv) > 1 else str(ROOT / "target/release/libime_ffi.so"))
    library.ime_handwriting_new.argtypes = [ctypes.c_char_p]
    library.ime_handwriting_new.restype = ctypes.c_void_p
    library.ime_handwriting_free.argtypes = [ctypes.c_void_p]
    library.ime_handwriting_recognize_json.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    library.ime_handwriting_recognize_json.restype = ctypes.c_char_p
    handle = library.ime_handwriting_new(str(MODEL).encode())
    assert handle, "bundled model load failed"
    wanted = set("一二三人大中国你好字语输入键盘手写识别文本测试")
    tested, top1, top5, durations = set(), 0, 0, []
    try:
        for entry in ET.parse(MODEL.parent / "source/handwriting-zh_CN.xml").getroot().findall("character"):
            label = entry.findtext("utf8")
            if label not in wanted:
                continue
            strokes = [[dict(x=int(p.attrib["x"]), y=int(p.attrib["y"]), time_ms=i)
                        for i, p in enumerate(stroke.findall("point"))] for stroke in entry.findall("strokes/stroke")]
            ink = json.dumps(dict(width=1000, height=1000, strokes=strokes)).encode()
            start = time.perf_counter()
            response = library.ime_handwriting_recognize_json(handle, ink)
            durations.append((time.perf_counter() - start) * 1000)
            assert response, f"recognition failed: {label}"
            choices = [candidate["text"] for candidate in json.loads(response)]
            tested.add(label)
            top1 += choices[0] == label
            top5 += label in choices[:5]
            assert label in choices[:5], f"{label} missing from top 5: {choices[:5]}"
        assert tested == wanted, f"missing model source samples: {wanted - tested}"
        print(json.dumps(dict(model_classes=class_count, source_fixture_count=len(tested),
                              top1=top1, top5=top5, max_call_ms=round(max(durations), 2),
                              normalized_ink=True,
                              note="Training-source regression only; not real-user accuracy."), ensure_ascii=False))
    finally:
        library.ime_handwriting_free(handle)


if __name__ == "__main__":
    main()
