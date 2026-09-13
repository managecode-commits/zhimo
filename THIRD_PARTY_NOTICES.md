# Third-party components

Zhimo's own code uses Apache-2.0 (see LICENSE). This does not relicense third-party components. Preserve the notices and applicable sources below when redistributing. This index is not a replacement for the original license texts or a complete binary dependency audit.

| Component | License information and source |
| --- | --- |
| Zinnia-derived Rust handwriting algorithm | BSD-3-Clause notice: `models/handwriting/ZINNIA-COPYING`; derivation and pinned upstream commit: `models/handwriting/README.md` |
| Tegaki/Tomoe simplified Chinese model and training data | LGPL-2.1 notice: `models/handwriting/zh-cn/COPYING`; corresponding XML and build metadata: `models/handwriting/zh-cn/source/` |
| PP-OCRv5 / RapidOCR model distribution | Apache-2.0 notices, model provenance and checksum: `models/handwriting-image/README.md`, `PaddleOCR-LICENSE`, `RapidOCR-LICENSE` |
| ONNX Runtime | MIT and third-party notices: `models/handwriting-image/ONNXRUNTIME-LICENSE`, `ONNXRUNTIME-ThirdPartyNotices.txt` |
| Bundled Rime schemas and dictionary | Original licenses: `platform/android-ime/app/src/main/assets/rime/LICENSE.txt`, `LICENSE-pinyin-simp.txt`; these also accompany the generated `crates/ime-engine-pinyin/data/pinyin_simp_lexicon.tsv` derived from the bundled dictionary |

Native Rime and its dependencies are fetched/built by the build tools; Rust dependencies are recorded in Cargo.lock and Android dependencies in Gradle configuration. A source repository publish is not a signed binary release or a declaration that all platform distribution requirements have been satisfied. See `docs/发布阻断与真机验收.md`.
