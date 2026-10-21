<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# ID mapping query library

The ID mapping function (`IdMapper`, CLI `id-map`) reads each model only through these SPARQL
SELECT queries. All CIM schema knowledge (class names, associations, which class is a switch or
a line) is here, and the Java matcher only sees the result tables. The queries are plain SPARQL 1.1,
so they also run in CimPal's SPARQL tab or any other SPARQL tool.

| Query | One row per | Columns |
| --- | --- | --- |
| `IdMap01_Objects.rq` | CIM object | `object type mRID name shortName description` |
| `IdMap02_Terminals.rq` | Terminal | `terminal equipment equipmentType role sequenceNumber node` |
| `IdMap03_Containment.rq` | contained equipment or node | `object container containerType voltageLevel substation` |
| `IdMap04_VoltageLevels.rq` | VoltageLevel | `voltageLevel substation nominalVoltage` |
| `IdMap05_EquipmentParameters.rq` | conducting equipment | `equipment nominalVoltage r x bch length` |
| `IdMap06_TransformerEnds.rq` | PowerTransformerEnd | `end transformer endNumber ratedU ratedS terminal` |
| `IdMap07_Dependents.rq` | dependent object and owner | `dependent owner association key` |
| `IdMap08_References.rq` | reference to a non-topology object | `referrer property target` |

The column names are the contract with the matcher. To change behaviour (for example which
classes count as switches, or more dependent classes), copy this folder, edit the queries and
point the mapping at the copy (CLI `--query-folder`). A file missing from that folder is taken
from the bundled set.

## Namespaces

The queries use the CGMES 3.0 namespace `http://iec.ch/TC57/CIM100#`. When CimPal loads models for
ID mapping, it rewrites the other known CIM namespaces to it first (CGMES 2.4.15
`http://iec.ch/TC57/2013/CIM-schema-cim16#` and `https://cim.ucaiug.io/ns#`, and the European
extensions `http://entsoe.eu/CIM/SchemaExtension/3/1#` to `http://iec.ch/TC57/CIM100-European#`).
So one query set serves both CGMES versions, and a union of files that mixes namespaces still
joins. To run a query by hand on CGMES 2.4.15 data, change the `cim:` prefix line.
