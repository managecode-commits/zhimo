#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("geography", Path(__file__).with_name("update-geography-lexicon.py"))
geo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(geo)


class GeographyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.outputs, cls.manifest = geo.outputs()
        cls.words = {line.split('\t')[1] for line in cls.outputs[geo.DATA / "geography_lexicon.tsv"].splitlines() if not line.startswith('#')}

    def test_every_source_name_is_covered(self):
        for raw, _, _ in geo.load_records():
            for name in geo.names(raw):
                self.assertIn(name, self.words)

    def test_all_provinces_and_direct_counties(self):
        self.assertEqual(34, self.manifest["record_counts"]["province"])
        self.assertEqual(333, self.manifest["record_counts"]["prefecture"])
        self.assertEqual(32, self.manifest["record_counts"]["direct_county"])
        for name in ["白杨市", "济源市", "神农架林区", "琼海市", "金门县", "连江县", "湾仔区", "黄大仙区"]:
            self.assertIn(name, self.words)
        for placeholder in geo.PLACEHOLDERS:
            self.assertNotIn(placeholder, self.words)

    def test_polyphones_aliases_and_qualified_names(self):
        for word, reading in {"单县": "shan xian", "中牟县": "zhong mou xian", "珲春市": "hun chun shi", "宕昌县": "tan chang xian"}.items():
            self.assertEqual(reading, geo.reading(word))
        for word in ["广西", "新疆", "恩施", "北京", "北京市朝阳区"]:
            self.assertIn(word, self.words)

    def test_committed_outputs_are_reproducible(self):
        for path, text in self.outputs.items():
            self.assertEqual(text.encode(), path.read_bytes(), str(path))

    def test_rime_and_shared_engine_use_identical_entries(self):
        tsv = self.outputs[geo.DATA / "geography_lexicon.tsv"].splitlines()[1:]
        rime = self.outputs[geo.ASSETS / "zhimo_geography.dict.yaml"].split("...\n", 1)[1].splitlines()
        self.assertEqual(sorted('\t'.join([f[1], f[0], f[2]]) for f in map(lambda s: s.split('\t'), tsv)), sorted(rime))
        self.assertIn("  - zhimo_geography", (geo.ASSETS / "zhimo_pinyin.dict.yaml").read_text())
        self.assertNotEqual("4", (geo.ASSETS / "version.txt").read_text().strip())


if __name__ == "__main__":
    unittest.main()
