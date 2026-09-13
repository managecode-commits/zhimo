# Experimental offline image handwriting

This model is bundled in the Android APK and enabled by default; an explicit user opt-out is preserved.
It is NOT a release-quality replacement for the trajectory engine. No screenshot,
chat text, user ink, evaluation tensor or test case is included in this directory.

- Original weights: PaddlePaddle `PP-OCRv5_mobile_rec`; model card labels weights Apache-2.0.
- Pinned original model card: https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec/tree/682f20538d8c086cb2128e5cfac775e6c4904e85
- ONNX conversion distributed by RapidAI: https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv5/rec/ch_PP-OCRv5_rec_mobile.onnx
- SHA-256: `5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5`, matches RapidOCR's model manifest. File retained without modification, renamed for the asset path.
- Original character dictionary is embedded in ONNX metadata `character`. CTC blank and final space are added by the decoder; no separately guessed character table.
- PaddleOCR and RapidOCR Apache-2.0 notices, original model card, ONNX Runtime 1.22.0 MIT license and third-party notices accompany the APK.
- Runtime: Maven `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`. CPU only, two intra-op threads, serial background provider. Runtime/weight licenses are separate from the LGPL trajectory model.

Images are rendered locally from complete strokes, centered with uniform scaling,
and fed to a 1 x 3 x 48 x 320 recognition tensor. The decoder sums CTC paths yielding
one token, preserving the single-character product boundary. It does not claim
calibrated confidence, stroke learning, continuous text or image/trajectory score fusion.

The local desktop experiment recognized three user-supplied screenshot crops as 制、清、存.
These labels remain provisional and from one writer; this is not held-out accuracy.
Phone memory/latency, independent-writer quality, nonstandard stroke styles and
accessibility still require independent release-quality validation despite the current default.

Do not remove notices when distributing. If weights are replaced or converted again,
record new provenance, exact checksum, operator/runtime compatibility and tests.
