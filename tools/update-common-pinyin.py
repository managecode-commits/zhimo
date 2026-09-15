#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Generate a pinned, low-priority phrase overlay for Rime and the shared engine."""
import hashlib
from pathlib import Path
import re
import unicodedata
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REVISION = "cee0ed6e6e4898580cafd2bd5e3723e20b214aa0"
BASE = f"https://raw.githubusercontent.com/mozillazg/phrase-pinyin-data/{REVISION}"


def fetch(name, digest):
    data = urllib.request.urlopen(f"{BASE}/{name}", timeout=60).read()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"unexpected hash: {name}")
    return data.decode("utf-8")


def normalize(reading):
    decomposed = unicodedata.normalize("NFD", reading.lower()).replace("u\u0308", "v")
    return "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")


def main():
    source = fetch("pinyin.txt", "dcc769607c220b312fea3e71cb63421298b4b891b1f7356a95ab58f2c96fff81")
    license_text = fetch("LICENSE", "89ac55df747e4776088c3e77531ef61b973a1a59dd8e6a4548a58996da9a4f70")
    data_dir = ROOT / "crates/ime-engine-pinyin/data"
    existing = set()
    for filename in ("pinyin_simp_lexicon.tsv", "reference_lexicon.tsv"):
        for line in (data_dir / filename).read_text().splitlines():
            fields = line.split("\t")
            if len(fields) == 3 and not line.startswith("#"):
                existing.add((fields[1], fields[0]))
    entries = set()
    for line in source.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line or ":" not in line:
            continue
        word, reading = line.split(":", 1)
        word, reading = word.strip(), normalize(reading.strip())
        if not re.fullmatch(r"[\u3400-\u9fff]{2,8}", word):
            continue
        if not re.fullmatch(r"[a-z]+(?: [a-z]+)*", reading) or len(reading.split()) != len(word):
            continue
        if (word, reading) not in existing:
            entries.add((word, reading))
    ordered = sorted(entries)
    header = f"# Generated from mozillazg/phrase-pinyin-data {REVISION}; MIT.\n# Tone removal, ü -> v, 2–8 Han characters, deduplicated; weight 1 (no frequency data).\n"
    (data_dir / "common_phrase_lexicon.tsv").write_text(header + "".join(f"{reading}\t{word}\t1\n" for word, reading in ordered))
    assets = ROOT / "platform/android-ime/app/src/main/assets/rime"
    (assets / "zhimo_common.dict.yaml").write_text(header + '---\nname: zhimo_common\nversion: "1.0"\nsort: by_weight\n...\n' + "".join(f"{word}\t{reading}\t1\n" for word, reading in ordered))
    (assets / "LICENSE-phrase-pinyin-data.txt").write_text(license_text)
    (data_dir / "LICENSE-phrase-pinyin-data.txt").write_text(license_text)
    print(f"Added {len(ordered)} unique phrase/readings; existing {len(existing)}; combined {len(existing | entries)}")


if __name__ == "__main__":
    main()
