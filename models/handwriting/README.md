# Bundled offline handwriting model

This directory is copied as one unit into Android assets and desktop/Apple resource packages.
No runtime downloads are required. Only simplified Chinese **single-character** recognition is exposed.
The model does not establish continuous/overlapping, traditional Chinese, Latin, or arbitrary Unicode support.

## Provenance and licenses

- Original project: https://tegaki.github.io/ ; model is derived from Tomoe.
- Source distribution: `tegaki-zinnia-simplified-chinese_0.3.orig.tar.gz`
- Immutable version mirror: https://deb.debian.org/debian/pool/main/t/tegaki-zinnia-simplified-chinese/tegaki-zinnia-simplified-chinese_0.3.orig.tar.gz
- Archive SHA-256: `63f29e7d7c7a71e94ab003353924304ab2a870a8e220a77800cf984c0620d41f`, matches Debian 0.3-3 source manifest.
- Unmodified model SHA-256: `e16153d1ff267cd479aea260d6f71a3edda8b4ba06db2d121513adda65a4449e`.
- Unmodified XML source SHA-256: `969862706c883c89ae6b945549b141e0051847930e04d8fa8a26aad2b3d4db11`.
- Model/data license: LGPL 2.1, full original notice in `zh-cn/COPYING`.
- Corresponding model source, metadata, original Makefile, README and version are in `zh-cn/source` and accompany the APK. The original README's Japanese example is upstream text; use `handwriting-zh_CN.xml` and `.meta` for this model.
- Rust feature extraction is adapted from BSD-3-Clause Zinnia by Taku Kudo, upstream commit `581faa8f6f15e4a7b21964be3a5ec36265c80e5b`; full notice in `ZINNIA-COPYING`. No Tegaki GPL application code is included.

Do not remove license notices or corresponding source when distributing the model.
The project's Apache license does not replace the LGPL data license or BSD algorithm attribution.
Model modifications must retain their applicable license, include changed source and modification notices,
and update the manifest/checksum constants and regression tests before rebuilding. Signed/checksummed release
models are intentionally verified at runtime; no assumption of compatible custom model weights is made.

Original model rebuild: install the Tegaki training tools and run from `zh-cn/source`:

```
tegaki-build -t handwriting-zh_CN.xml zinnia handwriting-zh_CN.meta
```

The prebuilt upstream model is pinned, not claimed to be bit-for-bit reproducible with arbitrary tool versions.
Preserve a complete matching source distribution when shipping any modified model.

Runtime integration: `crates/ime-handwriting/src/zinnia.rs`, C ABI 1.3 in `include/zhimo_ime.h`.
Verification: `cargo test -p ime-handwriting -p ime-ffi`, then `tools/verify-handwriting-model.py` against a built library.
The XML sample checks are training-source regression, **not** a held-out recognition accuracy benchmark.
