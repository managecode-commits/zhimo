<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# Third-party components

Zhimo's own code uses Apache-2.0 (see LICENSE). This does not relicense third-party components. Preserve the notices and applicable sources below when redistributing. This index is not a replacement for the original license texts or a complete binary dependency audit.

| Component | License information and source |
| --- | --- |
| Zinnia-derived Rust handwriting algorithm | BSD-3-Clause notice: `models/handwriting/ZINNIA-COPYING`; derivation and pinned upstream commit: `models/handwriting/README.md` |
| Tegaki/Tomoe simplified Chinese model and training data | LGPL-2.1 notice: `models/handwriting/zh-cn/COPYING`; corresponding XML and build metadata: `models/handwriting/zh-cn/source/` |
| PP-OCRv5 / RapidOCR model distribution | Apache-2.0 notices, model provenance and checksum: `models/handwriting-image/README.md`, `PaddleOCR-LICENSE`, `RapidOCR-LICENSE` |
| ONNX Runtime | MIT and third-party notices: `models/handwriting-image/ONNXRUNTIME-LICENSE`, `ONNXRUNTIME-ThirdPartyNotices.txt` |
| Whisper multilingual base Q5_1 weights | MIT: `models/speech/WHISPER-MODEL-LICENSE`; converted model origin, revision and SHA-256: `models/speech/manifest.json` |
| whisper.cpp 1.9.1 and vendored ggml | MIT: `models/speech/WHISPER-CPP-LICENSE`; source archive pinned in `tools/prepare-offline-speech.sh`, linked into Android `libzhimo_speech.so` |
| Bundled Rime schemas and dictionary | Original licenses: `platform/android-ime/app/src/main/assets/rime/LICENSE.txt`, `LICENSE-pinyin-simp.txt`; these also accompany the generated `crates/ime-engine-pinyin/data/pinyin_simp_lexicon.tsv` derived from the bundled dictionary |
| Supplemental phrase readings | mozillazg/phrase-pinyin-data `pinyin.txt`, revision `cee0ed6e6e4898580cafd2bd5e3723e20b214aa0`, MIT; bundled `LICENSE-phrase-pinyin-data.txt` in the Rime assets and shared engine data. `tools/update-common-pinyin.py` pins source/license hashes and generates deduplicated, tone-free, low-weight entries. Does not import `large_pinyin.txt` or its additional mixed-source datasets. |
| Administrative names | Chen-Yuanmeng/China-Administrative-Divisions snapshot `a189d17addb29a568335df46c79f5a6a3c02d4f3`, data CC0-1.0; 32 missing direct-administered county-level names supplemented from modood/Administrative-divisions-of-China `c49d495b40ac73eb1a66f6eeae5f8fd10696f035`, WTFPL-2.0. Original snapshots, licenses and hashes: `crates/ime-engine-pinyin/data/geography/`. |
| Geographic-name conversion | Build-time pypinyin 0.55.0 / pinyin-data / phrase-pinyin-data (MIT), opencc-python-reimplemented 0.1.7 (Apache-2.0). Generated Rime/shared overlay retains source notices; combined licenses ship in `platform/android-ime/app/src/main/assets/rime/LICENSE-geography.txt`. These Python libraries are not runtime dependencies of the IME. |

Native Rime and its dependencies are fetched/built by the build tools; Rust dependencies are recorded in Cargo.lock and Android dependencies in Gradle configuration. A source repository publish is not a signed binary release or a declaration that all platform distribution requirements have been satisfied. See `docs/发布阻断与真机验收.md`.
# 离线人声检测补充

Silero VAD v5.1.2（MIT，Copyright (c) 2020-present Silero Team），使用 ggml-org/whisper-vad 转换权重。
固定来源、版本及 SHA-256 见 models/speech/manifest.json；许可证随源码和 APK 保留在 models/speech/SILERO-LICENSE。
