#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Local-only ONNX experiment; no uploads, model downloads, or stored cropped ink.

Input JSON array: [{id, path, box:[left,top,right,bottom], label, writer, split}].
Labels must be confirmed by the writer for a release-quality evaluation.
Use only consented screenshots; crop to the writing area, excluding editor text.
"""
import argparse
import hashlib
import json
import time
import resource
import unicodedata
from pathlib import Path

from PIL import Image
import numpy as np
import onnxruntime as ort

EXPECTED = "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5"


def preprocess(path, box):
    with Image.open(path) as source:
        image = np.asarray(source.convert("RGB"))
    left, top, right, bottom = box
    if not (0 <= left < right <= image.shape[1] and 0 <= top < bottom <= image.shape[0]):
        raise ValueError("invalid writing-area crop")
    # Screenshots have a light grid. Keep dark ink only; no editor/OCR detection.
    crop = image[top:bottom, left:right]
    mask = np.max(crop, axis=2) < 110
    ys, xs = np.where(mask)
    if not len(xs):
        raise ValueError("empty ink")
    ink = np.where(mask[min(ys):max(ys)+1, min(xs):max(xs)+1], 0, 255).astype(np.uint8)
    side = max(ink.shape)
    pad = max(1, round(side * .125))
    square = np.full((side + 2 * pad, side + 2 * pad), 255, np.uint8)
    y, x = (square.shape[0] - ink.shape[0]) // 2, (square.shape[1] - ink.shape[1]) // 2
    square[y:y+ink.shape[0], x:x+ink.shape[1]] = ink
    resized = np.asarray(Image.fromarray(square).resize((48, 48), Image.Resampling.BILINEAR)).astype(np.float32) / 127.5 - 1
    tensor = np.zeros((1, 3, 48, 320), dtype=np.float32)
    tensor[0, :, :, :48] = resized
    return tensor


def single_character_ctc(probabilities):
    """Exact CTC probability for each one-token label; not framewise max scores."""
    active = np.zeros(probabilities.shape[1] - 1, dtype=np.float64)
    ended = active.copy()
    before = 1.0
    for row in probabilities:
        ended = (ended + active) * row[0]
        active = (active + before) * row[1:]
        before *= row[0]
    return active + ended


def validate_cases(records):
    writer_splits = {}
    ids = set()
    for case in records:
        if case["id"] in ids:
            raise ValueError("duplicate case id")
        ids.add(case["id"])
        if len(case["label"]) != 1:
            raise ValueError("single-character label required")
        split = case.get("split", "development")
        if split not in ("train", "development", "test"):
            raise ValueError("invalid split")
        writer = case["writer"]
        if not writer or writer in writer_splits and writer_splits[writer] != split:
            raise ValueError("writer missing or appears in multiple splits")
        if split == "test" and not case.get("label_confirmed", False):
            raise ValueError("test labels must be confirmed by writer")
        writer_splits[writer] = split


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("cases", type=Path)
    parser.add_argument("--export-tensors", type=Path, help="Explicit local development export; contains private ink, never commit/package")
    args = parser.parse_args()
    assert hashlib.sha256(args.model.read_bytes()).hexdigest() == EXPECTED, "unverified experiment model"
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    started = time.perf_counter()
    session = ort.InferenceSession(str(args.model), options, providers=["CPUExecutionProvider"])
    cold_ms = (time.perf_counter() - started) * 1000
    metadata = session.get_modelmeta().custom_metadata_map
    dictionary = metadata["character"].splitlines()
    # RapidOCR models store the original dictionary without CTC blank and trailing space.
    characters = [""] + dictionary + [" "]
    records = json.loads(args.cases.read_text())
    validate_cases(records)
    results = []
    for case in records:
        split = case.get("split", "development")
        tensor = preprocess(case["path"], case["box"])
        if args.export_tensors:
            if not case["id"].replace("-", "").isalnum():
                raise ValueError("unsafe case id")
            args.export_tensors.mkdir(parents=True, exist_ok=True)
            tensor.astype("<f4").tofile(args.export_tensors / (case["id"] + ".f32"))
        # Warm-up separated from measured inference, no network.
        session.run(None, {session.get_inputs()[0].name: tensor})
        started = time.perf_counter()
        output = session.run(None, {session.get_inputs()[0].name: tensor})[0][0]
        elapsed = (time.perf_counter() - started) * 1000
        assert output.shape[1] == len(characters), (output.shape, len(characters))
        assert np.allclose(output.sum(axis=1), 1, atol=.01), "expected probabilities"
        tokens = output.argmax(axis=1).tolist()
        greedy = "".join(characters[t] for i, t in enumerate(tokens) if t and (i == 0 or tokens[i-1] != t))
        scores = single_character_ctc(output)
        order = np.argsort(-scores)
        choices = [characters[i+1] for i in order if len(characters[i+1]) == 1 and
                   unicodedata.name(characters[i+1], "").startswith(("CJK UNIFIED IDEOGRAPH", "CJK COMPATIBILITY IDEOGRAPH"))][:100]
        label = case["label"]
        results.append(dict(id=case["id"], label=label, split=split, label_confirmed=case.get("label_confirmed", False),
                            greedy=greedy, top5=choices[:5], rank=choices.index(label)+1 if label in choices else None,
                            inference_ms=round(elapsed, 2)))
    confirmed = [r for r in results if r["split"] == "test" and r["label_confirmed"]]
    metrics = {f"top{k}": sum(r["rank"] is not None and r["rank"] <= k for r in confirmed) / len(confirmed)
               if confirmed else None for k in (1, 5, 20, 100)}
    print(json.dumps(dict(model_sha256=EXPECTED, cold_load_ms=round(cold_ms, 2), results=results,
                         confirmed_test_count=len(confirmed), confirmed_test_metrics=metrics,
                         process_peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                         note="Local image experiment, no trajectory/baseline equivalence or phone performance claim."), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
