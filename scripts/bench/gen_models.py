# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""Synthetic, scalable CGMES-like models for the CimPal benchmark (DEP-2).

Writes, for a requested number of triples and a seed:

    <out>/models/gNNN/gNNN_EQ.xml     equipment: Substation, VoltageLevel, ACLineSegment,
                                      Terminal, ConnectivityNode (CIM100, rdf:ID)
    <out>/models/gNNN/gNNN_SSH.xml    steady-state hypothesis: Terminal.connected (rdf:about)
    <out>/constraints/bench-shapes.ttl  SHACL with minCount, datatype, class and SPARQL constraints
    <out>/mapping.csv                 one validation row per group, for `validate --workflow mapping`
    <out>/manifest.json               triples, expected violations, seed and the parameters

The output is deterministic for a given seed and parameters. About `violation_rate` of the line
"units" (one ACLineSegment with its two terminals and two connectivity nodes) break exactly one
constraint each, round-robin over five kinds, so a validation does realistic work and the number
of SHACL results is known in advance.

Triple counts are of unique triples in the merged EQ+SSH graph of a group, which is what
`validate --stats` reports as triplesLoaded for the mapping workflow.

Standard library only (Python 3.10+). Usage:

    python scripts/bench/gen_models.py --triples 1000000 --seed 1 --out target/bench/1M
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from dataclasses import dataclass, field
from pathlib import Path
from xml.sax.saxutils import escape

GENERATOR_VERSION = 1
CIM = "http://iec.ch/TC57/CIM100#"
MD = "http://iec.ch/TC57/61970-552/ModelDescription/1#"
EQ_PROFILE = "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"
SSH_PROFILE = "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0"
SCENARIO_TIME = "2026-01-01T00:00:00Z"
CREATED = "2026-01-01T00:00:00Z"

# Unique triples per element (see the module docstring).
TRIPLES_PER_UNIT = 29         # line 7 + 2 terminals x 6 + 2 nodes x 4 + 2 SSH connected x 1
TRIPLES_PER_CONTAINER = 7     # VoltageLevel 4 + Substation 3
TRIPLES_PER_HEADER = 4        # md:FullModel type, scenarioTime, created, profile
UNITS_PER_VOLTAGE_LEVEL = 100

VIOLATION_KINDS = (
    "missingLength",       # ACLineSegment without Conductor.length (sh:minCount)
    "badSequenceNumber",   # Terminal.sequenceNumber that is no integer (sh:datatype)
    "wrongContainer",      # EquipmentContainer is a ConnectivityNode (sh:class)
    "rAboveX",             # r > x (sh:sparql)
    "missingConnected",    # SSH Terminal without ACDCTerminal.connected (sh:minCount)
)

SHAPES = f"""# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
# Benchmark shapes written by scripts/bench/gen_models.py (DEP-2).
@prefix sh:   <http://www.w3.org/ns/shacl#> .
@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
@prefix cim:  <{CIM}> .
@prefix bench: <urn:cimpal:bench:> .

bench:ACLineSegmentShape a sh:NodeShape ;
    sh:targetClass cim:ACLineSegment ;
    sh:property [ sh:path cim:IdentifiedObject.name ; sh:minCount 1 ; sh:datatype xsd:string ] ;
    sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 1 ; sh:datatype xsd:float ] ;
    sh:property [ sh:path cim:ACLineSegment.x ; sh:minCount 1 ; sh:datatype xsd:float ] ;
    sh:property [ sh:path cim:Conductor.length ; sh:minCount 1 ; sh:datatype xsd:float ] ;
    sh:property [ sh:path cim:Equipment.EquipmentContainer ; sh:minCount 1 ; sh:class cim:VoltageLevel ] ;
    sh:sparql [
        sh:message "ACLineSegment.r is greater than ACLineSegment.x" ;
        sh:select \"\"\"
            PREFIX cim: <{CIM}>
            SELECT $this WHERE {{
                $this cim:ACLineSegment.r ?r ; cim:ACLineSegment.x ?x .
                FILTER (?r > ?x)
            }}\"\"\"
    ] .

bench:TerminalShape a sh:NodeShape ;
    sh:targetClass cim:Terminal ;
    sh:property [ sh:path cim:Terminal.ConductingEquipment ; sh:minCount 1 ; sh:class cim:ACLineSegment ] ;
    sh:property [ sh:path cim:Terminal.ConnectivityNode ; sh:minCount 1 ; sh:class cim:ConnectivityNode ] ;
    sh:property [ sh:path cim:ACDCTerminal.sequenceNumber ; sh:minCount 1 ; sh:datatype xsd:integer ] ;
    sh:property [ sh:path cim:ACDCTerminal.connected ; sh:minCount 1 ; sh:datatype xsd:boolean ] .

bench:ConnectivityNodeShape a sh:NodeShape ;
    sh:targetClass cim:ConnectivityNode ;
    sh:property [ sh:path cim:IdentifiedObject.name ; sh:minCount 1 ] ;
    sh:property [ sh:path cim:ConnectivityNode.ConnectivityNodeContainer ; sh:minCount 1 ; sh:class cim:VoltageLevel ] .
"""


@dataclass
class GroupResult:
    name: str
    triples: int = 0
    units: int = 0
    violations: dict[str, int] = field(default_factory=lambda: {k: 0 for k in VIOLATION_KINDS})


def parse_count(text: str) -> int:
    """'100k', '1M', '5m', '250000' -> int."""
    t = text.strip().lower().replace("_", "")
    factor = 1
    if t.endswith("k"):
        factor, t = 1_000, t[:-1]
    elif t.endswith("m"):
        factor, t = 1_000_000, t[:-1]
    return int(float(t) * factor)


def triples_for(units: int) -> int:
    """Unique triples of a group with this many units (before violations remove any)."""
    containers = -(-units // UNITS_PER_VOLTAGE_LEVEL)
    return 2 * TRIPLES_PER_HEADER + containers * TRIPLES_PER_CONTAINER + units * TRIPLES_PER_UNIT


def units_for(target_triples: int) -> int:
    """The largest unit count whose group stays at or under the target (at least 1)."""
    units = max(1, (target_triples - 2 * TRIPLES_PER_HEADER) // (TRIPLES_PER_UNIT + 1))
    while triples_for(units + 1) <= target_triples:
        units += 1
    while units > 1 and triples_for(units) > target_triples:
        units -= 1
    return units


def header(about: str, profile: str) -> str:
    return (f'  <md:FullModel rdf:about="urn:uuid:{about}">\n'
            f"    <md:Model.scenarioTime>{SCENARIO_TIME}</md:Model.scenarioTime>\n"
            f"    <md:Model.created>{CREATED}</md:Model.created>\n"
            f"    <md:Model.profile>{profile}</md:Model.profile>\n"
            "  </md:FullModel>\n")


def open_rdf(path: Path):
    f = path.open("w", encoding="utf-8", newline="\n")
    f.write('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"\n'
            f'         xmlns:cim="{CIM}"\n'
            f'         xmlns:md="{MD}">\n')
    return f


def write_group(out: Path, name: str, units: int, rng: random.Random, rate: float,
                kind_counter: list[int]) -> GroupResult:
    result = GroupResult(name)
    group_dir = out / "models" / name
    group_dir.mkdir(parents=True, exist_ok=True)
    eq = open_rdf(group_dir / f"{name}_EQ.xml")
    ssh = open_rdf(group_dir / f"{name}_SSH.xml")
    try:
        eq.write(header(f"{name}-eq", EQ_PROFILE))
        ssh.write(header(f"{name}-ssh", SSH_PROFILE))
        result.triples += 2 * TRIPLES_PER_HEADER
        vl = None
        for u in range(units):
            if u % UNITS_PER_VOLTAGE_LEVEL == 0:
                n = u // UNITS_PER_VOLTAGE_LEVEL
                sub, vl = f"_{name}_SUB{n}", f"_{name}_VL{n}"
                eq.write(f'  <cim:Substation rdf:ID="{sub}">\n'
                         f"    <cim:IdentifiedObject.name>Substation {n}</cim:IdentifiedObject.name>\n"
                         f"    <cim:IdentifiedObject.mRID>{sub[1:]}</cim:IdentifiedObject.mRID>\n"
                         "  </cim:Substation>\n"
                         f'  <cim:VoltageLevel rdf:ID="{vl}">\n'
                         f"    <cim:IdentifiedObject.name>Voltage level {n}</cim:IdentifiedObject.name>\n"
                         f"    <cim:IdentifiedObject.mRID>{vl[1:]}</cim:IdentifiedObject.mRID>\n"
                         f'    <cim:VoltageLevel.Substation rdf:resource="#{sub}"/>\n'
                         "  </cim:VoltageLevel>\n")
                result.triples += TRIPLES_PER_CONTAINER

            kind = None
            if rng.random() < rate:
                kind = VIOLATION_KINDS[kind_counter[0] % len(VIOLATION_KINDS)]
                kind_counter[0] += 1
                result.violations[kind] += 1

            line = f"_{name}_L{u}"
            nodes = [f"_{name}_CN{u}_{side}" for side in (1, 2)]
            terminals = [f"_{name}_T{u}_{side}" for side in (1, 2)]
            r = round(rng.uniform(0.01, 0.5), 4)
            x = round(rng.uniform(0.6, 5.0), 4)
            if kind == "rAboveX":
                r, x = x + 1.0, x
            container = nodes[0] if kind == "wrongContainer" else vl

            parts = [f'  <cim:ACLineSegment rdf:ID="{line}">\n',
                     f"    <cim:IdentifiedObject.name>{escape('Line ' + str(u))}</cim:IdentifiedObject.name>\n",
                     f"    <cim:IdentifiedObject.mRID>{line[1:]}</cim:IdentifiedObject.mRID>\n",
                     f"    <cim:ACLineSegment.r>{r}</cim:ACLineSegment.r>\n",
                     f"    <cim:ACLineSegment.x>{x}</cim:ACLineSegment.x>\n"]
            if kind != "missingLength":
                parts.append(f"    <cim:Conductor.length>{round(rng.uniform(1, 120), 2)}</cim:Conductor.length>\n")
            parts.append(f'    <cim:Equipment.EquipmentContainer rdf:resource="#{container}"/>\n'
                         "  </cim:ACLineSegment>\n")
            eq.write("".join(parts))
            result.triples += TRIPLES_PER_UNIT - (1 if kind == "missingLength" else 0)

            for side, (node, term) in enumerate(zip(nodes, terminals), start=1):
                seq = "first" if (kind == "badSequenceNumber" and side == 1) else str(side)
                eq.write(f'  <cim:ConnectivityNode rdf:ID="{node}">\n'
                         f"    <cim:IdentifiedObject.name>Node {u}.{side}</cim:IdentifiedObject.name>\n"
                         f"    <cim:IdentifiedObject.mRID>{node[1:]}</cim:IdentifiedObject.mRID>\n"
                         f'    <cim:ConnectivityNode.ConnectivityNodeContainer rdf:resource="#{vl}"/>\n'
                         "  </cim:ConnectivityNode>\n"
                         f'  <cim:Terminal rdf:ID="{term}">\n'
                         f"    <cim:IdentifiedObject.name>Terminal {u}.{side}</cim:IdentifiedObject.name>\n"
                         f"    <cim:IdentifiedObject.mRID>{term[1:]}</cim:IdentifiedObject.mRID>\n"
                         f'    <cim:Terminal.ConductingEquipment rdf:resource="#{line}"/>\n'
                         f'    <cim:Terminal.ConnectivityNode rdf:resource="#{node}"/>\n'
                         f"    <cim:ACDCTerminal.sequenceNumber>{seq}</cim:ACDCTerminal.sequenceNumber>\n"
                         "  </cim:Terminal>\n")
                if kind == "missingConnected" and side == 1:
                    ssh.write(f'  <cim:Terminal rdf:about="#{term}"/>\n')
                    result.triples -= 1
                else:
                    connected = "true" if rng.random() < 0.97 else "false"
                    ssh.write(f'  <cim:Terminal rdf:about="#{term}">\n'
                              f"    <cim:ACDCTerminal.connected>{connected}</cim:ACDCTerminal.connected>\n"
                              "  </cim:Terminal>\n")
            result.units += 1
        eq.write("</rdf:RDF>\n")
        ssh.write("</rdf:RDF>\n")
    finally:
        eq.close()
        ssh.close()
    return result


def generate(target_triples: int, seed: int, out: Path, rate: float = 0.01, rows: int = 1) -> dict:
    """Writes the model set and returns the manifest."""
    if target_triples < 100:
        raise ValueError("--triples must be at least 100")
    if not 0 <= rate <= 1:
        raise ValueError("--violation-rate must be between 0 and 1")
    if rows < 1:
        raise ValueError("--rows must be at least 1")
    out.mkdir(parents=True, exist_ok=True)
    (out / "constraints").mkdir(exist_ok=True)
    (out / "constraints" / "bench-shapes.ttl").write_text(SHAPES, encoding="utf-8", newline="\n")

    rng = random.Random(seed)
    kind_counter = [0]
    groups = []
    per_group = target_triples // rows
    for g in range(rows):
        name = f"g{g + 1:03d}"
        groups.append(write_group(out, name, units_for(per_group), rng, rate, kind_counter))

    with (out / "mapping.csv").open("w", encoding="utf-8", newline="\n") as m:
        m.write("xml_inputs,ttl,notes\n")
        for gr in groups:
            m.write(f"{gr.name}/*EQ*.xml;{gr.name}/*SSH*.xml,bench-shapes.ttl,benchmark group {gr.name}\n")

    violations = {k: sum(gr.violations[k] for gr in groups) for k in VIOLATION_KINDS}
    manifest = {
        "generator": "scripts/bench/gen_models.py",
        "generatorVersion": GENERATOR_VERSION,
        "seed": seed,
        "requestedTriples": target_triples,
        "triples": sum(gr.triples for gr in groups),
        "rows": rows,
        "violationRate": rate,
        "groups": [{"name": gr.name, "triples": gr.triples, "units": gr.units} for gr in groups],
        "expectedViolations": violations,
        "expectedViolationsTotal": sum(violations.values()),
        "xmlBase": "http://example.com/data",
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
    return manifest


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--triples", required=True, help="target triple count, e.g. 100k, 1M, 5M")
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--out", required=True, type=Path)
    p.add_argument("--violation-rate", type=float, default=0.01)
    p.add_argument("--rows", type=int, default=1, help="validation rows (groups); default 1, one model")
    a = p.parse_args(argv)
    manifest = generate(parse_count(a.triples), a.seed, a.out, a.violation_rate, a.rows)
    print(json.dumps({k: manifest[k] for k in ("triples", "rows", "expectedViolationsTotal")}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
