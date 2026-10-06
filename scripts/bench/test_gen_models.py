# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""Self-test of gen_models.py: deterministic output and exact triple accounting.

    python -m unittest discover -s scripts/bench -p "test_*.py"
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_models  # noqa: E402

RDF = "{http://www.w3.org/1999/02/22-rdf-syntax-ns#}"


def unique_triples(files: list[Path]) -> int:
    """Unique triples of the merged files, for the flat RDF/XML gen_models writes."""
    triples = set()
    for f in files:
        for el in ET.parse(f).getroot():
            subject = el.get(RDF + "about") or "#" + el.get(RDF + "ID")
            triples.add((subject, RDF + "type", el.tag))
            for prop in el:
                obj = prop.get(RDF + "resource") or ("literal", prop.text)
                triples.add((subject, prop.tag, obj))
    return len(triples)


class GenModelsTest(unittest.TestCase):

    def generate(self, out: Path, **kwargs) -> dict:
        args = {"target_triples": 20_000, "seed": 7, "out": out, "rate": 0.05}
        args.update(kwargs)
        return gen_models.generate(**args)

    def test_triple_count_matches_the_files_and_the_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = self.generate(Path(tmp))
            files = sorted((Path(tmp) / "models").rglob("*.xml"))
            self.assertEqual(manifest["triples"], unique_triples(files))
            self.assertLessEqual(manifest["triples"], 20_000)
            self.assertGreater(manifest["triples"], 20_000 * 0.98)

    def test_same_seed_gives_identical_files(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            self.generate(Path(a))
            self.generate(Path(b))
            names = sorted(p.relative_to(a) for p in Path(a).rglob("*") if p.is_file())
            self.assertEqual(names, sorted(p.relative_to(b) for p in Path(b).rglob("*") if p.is_file()))
            for name in names:
                self.assertEqual((Path(a) / name).read_bytes(), (Path(b) / name).read_bytes(), str(name))

    def test_another_seed_changes_the_values(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            self.generate(Path(a), seed=1)
            self.generate(Path(b), seed=2)
            eq = Path("models/g001/g001_EQ.xml")
            self.assertNotEqual((Path(a) / eq).read_bytes(), (Path(b) / eq).read_bytes())

    def test_violations_are_spread_over_every_kind(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = self.generate(Path(tmp))
            counts = manifest["expectedViolations"]
            self.assertEqual(set(counts), set(gen_models.VIOLATION_KINDS))
            self.assertTrue(all(c > 0 for c in counts.values()), counts)
            self.assertEqual(manifest["expectedViolationsTotal"], sum(counts.values()))
            # Rate 0.05 of about 680 units: roughly 34 violations.
            self.assertTrue(15 <= manifest["expectedViolationsTotal"] <= 60, counts)

    def test_rows_split_the_model_into_groups_with_a_mapping_row_each(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = self.generate(Path(tmp), rows=3)
            self.assertEqual(3, len(manifest["groups"]))
            rows = (Path(tmp) / "mapping.csv").read_text(encoding="utf-8").splitlines()
            self.assertEqual("xml_inputs,ttl,notes", rows[0])
            self.assertEqual(["g001/*EQ*.xml;g001/*SSH*.xml", "bench-shapes.ttl"], rows[1].split(",")[:2])
            self.assertEqual(4, len(rows))
            stored = json.loads((Path(tmp) / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest, stored)

    def test_counts_parse_suffixes(self):
        self.assertEqual(100_000, gen_models.parse_count("100k"))
        self.assertEqual(1_000_000, gen_models.parse_count("1M"))
        self.assertEqual(2_500_000, gen_models.parse_count("2.5m"))
        self.assertEqual(1234, gen_models.parse_count("1234"))

    def test_bad_parameters_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                self.generate(Path(tmp), target_triples=10)
            with self.assertRaises(ValueError):
                self.generate(Path(tmp), rate=1.5)
            with self.assertRaises(ValueError):
                self.generate(Path(tmp), rows=0)


if __name__ == "__main__":
    unittest.main()
