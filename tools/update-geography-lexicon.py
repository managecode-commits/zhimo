#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Reproduce/check the pinned administrative-name overlay; no runtime networking."""
import argparse
from collections import Counter
import hashlib
from importlib.metadata import version
import json
from pathlib import Path
import re

from opencc import OpenCC
from pypinyin import lazy_pinyin

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "crates/ime-engine-pinyin/data"
SOURCE = DATA / "geography"
ASSETS = ROOT / "platform/android-ime/app/src/main/assets/rime"
REVISION = "a189d17addb29a568335df46c79f5a6a3c02d4f3"
SUPPLEMENT_REVISION = "c49d495b40ac73eb1a66f6eeae5f8fd10696f035"
HASHES = {
    "provinces.json": "9e23f106ac558921d26a86659b18786974d6516e557cb593fc7357f0dbe7dc00",
    "cities.json": "19f427df6f217e7153972bcdf9ffb37e3ee323209c5079b669137bc18da3d0d7",
    "areas.json": "a266f7568f43eba252b09122d488914c7776bc6b9781bd3a992699c7be9ddd26",
    "HK-MO-TW.json": "6126b445e023beb303b52f98cd4bf4761ee2a3eee65e89977562c69f0cc26242",
    "supplement-areas.json": "fbe1575eecba4ffd4d50c3d2d6887bd873ceca6a203fb2d66698b5007826b6b6",
    "LICENSE-upstream.md": "dec3d06a7e19c0725c669f434079490da871f790671f60f9956b21eacd0e4638",
    "LICENSE-supplement.txt": "ee820ff0db4ce628569e0975ac27dc926052a9f85d102b101edb104311ef4d90",
    "LICENSE-pypinyin.txt": "1e6c90014b4912815c296ee64bb6f6280af47e6d4c5d80e86232dfc5defe764c",
    "LICENSE-pinyin-data.txt": "9c048697be2502a16e8bcb282d5d465a07295b2def0ffb05a269c5d39dbe1586",
    "LICENSE-opencc.txt": "18f3fedf9eac72e6054260489c764c1e54f63cf2b16055523f561ffeec7bc908",
}
PLACEHOLDERS = {"市辖区", "县", "省辖县", "省直辖县级行政区划", "自治区直辖县级行政区划"}
# Pronunciations are scoped to geographic names, never global character rules.
OVERRIDES = {
    "单县": "shan xian", "繁峙": "fan shi", "荥经": "ying jing",
    "浚县": "xun xian", "涡阳": "guo yang", "中牟": "zhong mou",
    "珲春": "hun chun", "宕昌": "tan chang", "六安": "lu an",
    "乐清": "yue qing", "乐亭": "lao ting", "长子": "zhang zi",
    "尉犁": "yu li", "尉氏": "wei shi", "蔚县": "yu xian",
    "铅山": "yan shan", "泌阳": "bi yang", "荥阳": "xing yang",
    "枞阳": "zong yang", "歙县": "she xian", "牟平": "mu ping",
    "重庆": "chong qing", "东莞": "dong guan", "蚌埠": "beng bu",
}
ETHNICITIES = "蒙古 藏 羌 彝 壮 回 维吾尔 哈萨克 柯尔克孜 土家 苗 布依 侗 瑶 白 哈尼 傣 景颇 傈僳 怒 拉祜 佤 纳西 普米 保安 东乡 撒拉 土 黎 满 朝鲜 锡伯 达斡尔 鄂温克 鄂伦春 仫佬 毛南 仡佬 水 畲 塔吉克 裕固 独龙 门巴 珞巴 布朗 阿昌 德昂 京 塔塔尔 乌孜别克 俄罗斯 高山 赫哲 基诺".split()
CONVERTER = OpenCC("t2s")


def names(raw):
    """Keep official/source spelling plus simplified spelling and parenthetic aliases."""
    parts = [p.strip().strip('“”"') for p in re.split(r"[（()）]", raw) if p.strip()]
    return sorted({p for part in parts for p in (part, CONVERTER.convert(part))})


def short_name(name):
    for suffix in ("特别行政区", "自治区", "自治州", "自治县", "自治旗", "地区", "林区", "新区", "特区", "省", "市", "县", "区", "盟", "旗"):
        if name.endswith(suffix):
            value = name[:-len(suffix)]
            if suffix.startswith("自治"):
                ethnic = "(?:" + "|".join(sorted(ETHNICITIES, key=len, reverse=True)) + ")(?:族)?"
                value = re.sub("(?:" + ethnic + ")+$", "", value)
            return value if len(value) >= 2 else name
    return name


def reading(name):
    simplified = CONVERTER.convert(name)
    syllables = lazy_pinyin(simplified)
    for prefix in sorted(OVERRIDES, key=len, reverse=True):
        if simplified.startswith(prefix):
            syllables[:len(prefix)] = OVERRIDES[prefix].split()
            break
    result = " ".join(syllables)
    if len(syllables) != len(name) or not re.fullmatch(r"[a-z]+(?: [a-z]+)*", result):
        raise ValueError(f"Unresolved geographic reading: {name!r}: {result!r}")
    return result


def load_records():
    load = lambda filename: json.loads((SOURCE / filename).read_text(encoding="utf-8"))
    provinces, cities, areas = (load(n + ".json") for n in ("provinces", "cities", "areas"))
    province_names = {r["code"]: r["name"] for r in provinces}
    city_names = {r["code"]: r["name"] for r in cities if r["name"] not in PLACEHOLDERS}
    records = [(r["name"], "", "province") for r in provinces]
    records += [(r["name"], province_names[r["provinceCode"]], "prefecture") for r in cities if r["name"] not in PLACEHOLDERS]
    # Upstream repeats cities without counties as artificial county placeholders.
    records += [(r["name"], city_names.get(r["cityCode"], province_names[r["provinceCode"]]), "county")
                for r in areas if r["name"] != city_names.get(r["cityCode"])]
    codes = {r["code"] for r in areas}
    direct = [r for r in load("supplement-areas.json") if r["cityCode"].endswith("90") and r["code"] not in codes]
    records += [(r["name"], province_names[r["provinceCode"]], "direct_county") for r in direct]
    for province, children in load("HK-MO-TW.json").items():
        for city, districts in children.items():
            if city in PLACEHOLDERS:
                records.extend((district, province, "supplement_county") for district in districts)
                continue
            records.append((city, province, "supplement_city_or_region"))
            records.extend((district if province != "香港特别行政区" or district.endswith(("区", "區")) else district + "區",
                            city, "supplement_district") for district in districts)
    assert len(provinces) == 34 and len(direct) == 32
    return records


def outputs():
    assert version("pypinyin") == "0.55.0"
    assert version("opencc-python-reimplemented") == "0.1.7"
    for filename, expected in HASHES.items():
        assert hashlib.sha256((SOURCE / filename).read_bytes()).hexdigest() == expected, filename
    records = load_records()
    parents = {}
    for raw, parent, _ in records:
        for name in names(raw):
            parents.setdefault(name, set()).add(CONVERTER.convert(parent))
    entries = {}
    full_names = set()
    for raw, parent, level in records:
        weight = {"province": 500, "prefecture": 250}.get(level, 80)
        for name in names(raw):
            full_names.add(name)
            forms = {name, short_name(name)}
            if len(parents[name]) > 1 and parent:
                forms.add(CONVERTER.convert(parent) + name)
            for form in forms:
                code = reading(form) if form in {name, short_name(name)} else reading(CONVERTER.convert(parent)) + " " + reading(name)
                entries[(form, code)] = max(weight, entries.get((form, code), 0))
    ordered = sorted(entries)
    header = "# Generated geographic names; source, snapshot and licenses: geography/manifest.json.\n"
    tsv = header + "".join(f"{code}\t{name}\t{entries[(name, code)]}\n" for name, code in ordered)
    rime = header + '---\nname: zhimo_geography\nversion: "2026.09.14"\nsort: by_weight\n...\n' + "".join(f"{name}\t{code}\t{entries[(name, code)]}\n" for name, code in ordered)
    manifest = {
        "generated_on": "2026-09-14", "upstream_snapshot_commit_date": "2026-03-15",
        "source": f"https://github.com/Chen-Yuanmeng/China-Administrative-Divisions/tree/{REVISION}",
        "direct_county_supplement": f"https://github.com/modood/Administrative-divisions-of-China/tree/{SUPPLEMENT_REVISION}",
        "snapshot_not_live_official_register": True, "raw_sha256": HASHES,
        "generators": {"pypinyin": "0.55.0", "opencc-python-reimplemented": "0.1.7"},
        "record_counts": dict(sorted(Counter(r[2] for r in records).items())),
        "unique_full_names": len(full_names), "entries_with_aliases": len(entries),
        "geography_tsv_sha256": hashlib.sha256(tsv.encode()).hexdigest(),
        "polyphonic_overrides": OVERRIDES,
    }
    metadata = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    license_text = "Geographic-name overlay: see geography-manifest.json for pinned sources.\n\n" + "\n\n".join(
        filename + "\n" + (SOURCE / filename).read_text(encoding="utf-8") for filename in HASHES if filename.startswith("LICENSE"))
    license_text += "\n\nPhrase readings (MIT):\n" + (DATA / "LICENSE-phrase-pinyin-data.txt").read_text(encoding="utf-8")
    return {
        DATA / "geography_lexicon.tsv": tsv,
        ASSETS / "zhimo_geography.dict.yaml": rime,
        SOURCE / "manifest.json": metadata,
        ASSETS / "geography-manifest.json": metadata,
        ASSETS / "LICENSE-geography.txt": license_text,
    }, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="regenerate committed outputs; otherwise check only")
    args = parser.parse_args()
    generated, manifest = outputs()
    for path, content in generated.items():
        if args.write:
            path.write_text(content, encoding="utf-8")
        elif not path.is_file() or path.read_bytes() != content.encode():
            raise SystemExit(f"Stale generated file: {path}")
    print(json.dumps({key: manifest[key] for key in ("record_counts", "unique_full_names", "entries_with_aliases")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
