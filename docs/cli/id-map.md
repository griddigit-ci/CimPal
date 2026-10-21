<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `id-map`: map the IDs of one model onto another

Two CIM/CGMES models of the same grid often use different IDs, for example two EQ files from one tool and one merged EQ built by another. Any CIM/CGMES models will do; they are called **model 1** (whose IDs are mapped) and **model 2** (whose IDs they map to). `id-map` finds which object in one model is which object in the other, and writes a workbook with the mapping and with everything it could not map, so a person can finish the job.

The same function is in the GUI under **Dataset ▸ ID Mapping**.

---

## Quick examples

**Two EQ files against one merged EQ, with the boundary set and the common data:**
```
java -jar CimPal-CLI.jar id-map ^
  --model1 C:\Data\Area1_EQ.xml,C:\Data\Area2_EQ.xml ^
  --model2 C:\Data\Merged_EQ.xml ^
  --common C:\Data\BoundaryEQ.zip ^
  --output C:\Data\id_mapping.xlsx
```

**Substations named differently in the two models (code in one, full name in the other):**
```
java -jar CimPal-CLI.jar id-map --config id-map.json --name-aliases C:\Data\station-names.csv
```

**Summary as JSON for a script:**
```
java -jar CimPal-CLI.jar id-map --config id-map.json --format json > summary.json
```

---

## The workbook

**Matched**: one row per mapped model 1 object.

| Column | Content |
|---|---|
| `Model1_ID` | ID of the object in model 1 (the `rdf:ID` without the leading `_`, which is the mRID in CGMES) |
| `Element_type` | its CIM class, e.g. `ACLineSegment`, `Breaker`, `ConnectivityNode` |
| `Model2_ID` | ID of the same object in model 2 |
| `Model2_2nd_name` | name of the model 2 object (`--second-name` picks `name`, `shortName` or `description`). A line section in a Line container gets the Line's name, with its own name in brackets when that differs: `AGANYGAL-DERRYBRIEN (A)` |
| `Confidence` | `certain`: the evidence leaves no other reading. `probable`: likely right, check it (the Note says why). See *Confidence* below |
| `Model1_name` | name of the model 1 object |
| `Model2_type` | class of the model 2 object when it differs (e.g. a Breaker in one model, a Switch in the other) |
| `Method` | how the match was found, see below |
| `Note` | what a reviewer should know: values that differ (line voltage or parameters, names), or weaker evidence ("surroundings agree 2 step(s) deep only", "told apart by name") |

**Unmatched**: one row per object of either model left unmapped: `Side` (Model 1 or Model 2), `ID`, `Element_type`, `Name`, `Substation`, `Reason` and `Candidates` (the objects of the other model it could be, as `ID (name)`). Classes that only one model has (for example measurements, `Analog`, `Discrete`, in an EMS model) cannot be matched and are not listed object by object; the Summary counts them.

**Summary**: first the overview: for each model, over the classes both models have, how many objects are matched with certainty, how many probably, and how many are not matched, with percentages. Then the inputs and options, the counts per class (certain, probable and unmatched on each side, and whether both models have the class), counts per method, and model statistics (substations, stations grouping several substations, logical lines, line junctions, nodes outside substations).

A name ending in `.xls` gives the old binary format (at most 65,535 rows a sheet); any other name gives `.xlsx`.

---

## How objects are matched

Every step needs a unique answer on both sides. When two candidates are equally good, nothing is guessed: the objects go to the Unmatched sheet with the candidates.

1. **Same ID** (`same-id`): objects with the same ID and class on both sides. Objects of the common files (boundary nodes, base voltages) are the same on both sides and anchor the rest.
2. **Substations by name** (`name`): names unique on both sides, compared case- and punctuation-insensitively, through the alias table if given.
3. **Lines between substations** (`line-structure`). Series elements (ACLineSegment, SeriesCompensator, EquivalentBranch, and switches outside substations) are chained through line-interior nodes into logical lines: `Node1(substation 1) – ACLineSegment 1 – Node2(Line) – ACLineSegment 2 – Node3(substation 2)` is one line from substation 1 to substation 2, also when the Line container is missing. Lines between the same matched substations match. Parallel lines are told apart by name, then by voltage and parameters (r, x, bch, length within the tolerance), then by voltage. A line matched by its ends but with a different voltage or parameters is still matched, with a note.
4. **Substations from lines** (`line-topology`): the far end of a matched line whose near end is matched; the substation where two matched lines meet; a substation whose matched neighbours are unique.
5. **Lines by parameters** (`line-parameters`), only when the structure gives nothing more: lines with the same voltage and parameters (unique on both sides) propose the substations at their ends. Two such lines must agree before a substation is matched, because two different lines can have the same values.
6. **Inside each matched substation** (`topology`): busbars, switches, transformers, loads, machines and nodes are placed relative to what is already matched (the line ends first). An element matches the one element of the other model in the same place. When that place is only partly confirmed (the models differ a few steps away), the note says how deep the surroundings agree. Symmetric elements (two identical bays) are told apart by name if names allow it, else reported. A switch of another class (Breaker against Switch) matches only when its whole surroundings agree.
7. **What follows** (`equipment`, `terminals`, `transformer`, `containment`, `owner`, `references`, `name-only`): terminals of matched equipment; nodes whose terminals all match; transformer ends by their terminals or rated-voltage rank; bays, voltage levels, Line containers and converter units by the matched objects they hold; dependent objects (tap changers and their table points, limit sets and limits, regulating controls, generating units, curves) under their matched owner; limit types, load groups, regions and similar by the matched objects that refer to them; other objects by unique class and name.

Steps 3 to 6 repeat while they find more. A line end whose position is only partly confirmed is matched last, after everything stronger: such a match also pairs the two lines, so on parallel circuits it waits for the end where the bays tell them apart.

**Stations.** Substations joined by a transformer or a switch are treated as one station (`--no-group-stations` turns this off). One model may keep each voltage level of a station as a substation of its own (`FINGLAS`, `FINGLAS1`, `FINGLAS2`) where the other has one (`Finglas`). The station is then matched as a whole; the model 1 substation maps to the part whose name matches it (or else the largest), the Note lists the parts, and the other parts are reported as part of the matched station.

### Confidence

A match is **certain** when:
- it has the same ID, or a substation has the same name (through the alias table), or one name abbreviates the other (`AGANYGAL` and `Agannygal`, `BLYLMFRD` and `Ballylumford 110`: the letters of the code appear in order in the name, starting with the same letter);
- or a substation was found by structure and all its lines match one to one, and at least 90 % of its inside is confirmed element by element;
- or a line connects certain substations, one line on each side or told apart by name, at the same voltage;
- or an element inside a certain substation (or along a certain line) is in a place that only certain matches already pin down: its whole surroundings agree, its class is the same, and a symmetric twin, if any, has another name;
- or it follows from a certain match: terminals of certain equipment at certain nodes, ends of a certain transformer, dependents of a certain owner, containers or referenced objects that all certain members agree on.

Everything else matched is **probable**: substations found by structure whose names differ and whose lines or inside do not fully agree, parallel lines told apart by parameters or voltage alone, positions that agree only a few steps deep, a class that differs, majority (not unanimous) votes, name-only matches, and anything that follows from a probable match.

On the Relicap checks below, no certain match was ever wrong; the few wrong matches were all probable, with a Note.

**Line junctions.** A substation that holds only line ends, switches and at most one busbar section (no transformer, load, machine or other equipment), with at least two line ends and fewer breakers than line ends, is treated as part of the line. PowerFactory puts such a pseudo-substation at every line terminal (`Term_...`), and cable sealing ends and tee points look the same. Its contents are then matched with the line. A switching station has a breaker in every line bay and stays a substation. Turn this off with `--no-collapse-junctions`.

**Split lines.** When the model 1 line has two sections and the model 2 one has one, both model 1 sections map to the one model 2 section (note "model 1 line has 2 sections, model 2 has 1"). When the model 1 line is whole and the model 2 one is split (e.g. a tie line kept as two halves at the boundary), the model 1 section maps to the model 2 section at its first end, and the note names the others.

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file | — | JSON config; individual flags override it. Keys starting with `_` are documentation. |
| `--model1` | comma-separated files | — | The model whose IDs are mapped (Model1_ID). RDF/XML files or ZIPs. **Required.** |
| `--model2` | comma-separated files | — | The model the IDs are mapped to (Model2_ID). **Required.** |
| `--common` | comma-separated files | — | Loaded into both sides, not mapped: boundary set, common data with the BaseVoltages. |
| `--output` | file | — | The workbook to write. **Required.** |
| `--query-folder` | folder | bundled | Edited copies of the SPARQL queries (see below). |
| `--name-aliases` | file | — | CSV of substation name aliases: one substation per line, its names separated by `;`, `,` or tab. |
| `--[no-]names` | flag | on | Seed with substations of the same name. |
| `--[no-]same-ids` | flag | on | Match objects with the same ID and class first. |
| `--[no-]collapse-junctions` | flag | on | Treat line-junction substations as part of the line. |
| `--[no-]group-stations` | flag | on | Treat substations joined by a transformer or switch as one station. |
| `--[no-]terminals` | flag | on | Map and report terminals. |
| `--[no-]nodes` | flag | on | Map and report connectivity (or topological) nodes. |
| `--[no-]dependents` | flag | on | Map and report dependent objects. |
| `--[no-]others` | flag | on | Map and report objects outside the topology (limit types, regions, load groups, ...). |
| `--parameter-tolerance` | number | `0.05` | Relative tolerance for line r, x, bch and length. |
| `--voltage-tolerance` | kV | `1` | Tolerance for nominal voltages. |
| `--second-name` | `name`, `shortName`, `description` | `name` | What fills `Model2_2nd_name`. |
| `--xml-base` | URI | `http://iec.ch/TC57/CIM100` | Base URI for parsing RDF/XML. |
| `--format` | `text`, `json` | `text` | Summary on stdout. With `json`, stdout holds only the JSON (`cimpal-id-map-summary/2`: certain, probable and unmatched per model and per class); progress goes to stderr. |
| `--dry-run` | flag | — | Print the resolved configuration and exit. |

Config keys: `model1Files`, `model2Files`, `commonFiles` (arrays), `output`, `queryFolder`, `nameAliases`, `useNames`, `matchSameIds`, `collapseJunctions`, `groupStations`, `includeTerminals`, `includeNodes`, `includeDependents`, `includeOthers`, `parameterTolerance`, `voltageTolerance`, `secondName`, `xmlBase`, `format`. Relative paths are resolved against the config file's folder. Template: `CimPal-CLI/configs/id-map.json`.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Every model 1 object was mapped. |
| 1 | The workbook was written and some objects are unmatched (a result, not a failure). |
| 2 | Bad input: a missing file, a file that does not parse, an invalid query in the query folder. |
| 3 | Internal error, or the workbook could not be written. |

---

## The query library

The models are read only through SPARQL SELECT queries bundled under `CimPal-Core/src/main/resources/idmapping/queries/`. All CIM knowledge is in them (which classes are switches or series elements, which associations make an object depend on another), and they run unchanged in CimPal's SPARQL tab or any SPARQL tool. The folder's `README.md` lists the columns each query must return.

| Query | Reads |
|---|---|
| `IdMap01_Objects.rq` | every CIM object: class, mRID, name, shortName, description |
| `IdMap02_Terminals.rq` | terminals with their equipment, its role and the node (ConnectivityNode, else TopologicalNode) |
| `IdMap03_Containment.rq` | the container, voltage level and substation of equipment and nodes |
| `IdMap04_VoltageLevels.rq` | voltage levels with substation and nominal voltage |
| `IdMap05_EquipmentParameters.rq` | nominal voltage, r, x, bch and length of equipment |
| `IdMap06_TransformerEnds.rq` | transformer windings with rated voltage and terminal |
| `IdMap07_Dependents.rq` | dependent objects and their owner |
| `IdMap08_References.rq` | references to objects outside the topology |

To change the behaviour, copy the folder, edit a query (keeping its columns) and pass `--query-folder`; queries missing from the folder are taken from the bundled set. `SERVICE` clauses are refused.

The queries use the CGMES 3.0 namespace. CGMES 2.4.15 files (and files in `https://cim.ucaiug.io/ns#`) are read with their CIM namespace rewritten to it, so one query set serves both, and a mix of the two in one model still joins.

---

## Inputs to give

- **Common files.** BaseVoltages often live outside the EQ: in the EQ boundary file for CGMES 2.4.15, in the common data for CGMES 3.0. Without them, nominal voltages are unknown: the matching still works by structure, but cannot use voltages to tell lines apart. Boundary nodes from the boundary set anchor the tie lines.
- **Bus-branch models.** If the EQ has no ConnectivityNodes, load the TP file too; terminals then fall back to their TopologicalNode.
- **Memory.** Each side is loaded, read through the queries and dropped before the other. The largest cost is the RDF graph of one side; for a national model give the JVM a few GB (`-Xmx4g`).

## Limitations

- DC topology (DC busbars, DC line segments and their DC terminals) is not matched; converters, their DC terminals, DC nodes and converter units are.
- Two identical elements at the same place with the same name (e.g. two equivalent injections named alike at one boundary node) cannot be told apart and are reported.
- A model where substation nodes lack their container *and* sit between two line segments with no switch reads those nodes as line joints.

## How it was checked

Synthetic grid tests (`IdMapperTest`) and runs on the Relicap grid: the Espheim and Svedala EQ files (two files, 16,851 objects) against one merged copy with every ID replaced, changed as the first column says. Percentages are of the model 1 objects; "wrong" counts matches that point to the wrong object.

| Change | Certain | Probable | Not matched | Wrong certain | Wrong probable |
|---|---|---|---|---|---|
| none (new IDs only) | 99.4% | 0.6% | 0.0% | 0 | 0 |
| all substation, line and voltage level names scrambled | 79.8% | 19.2% | 0.9% | 0 | 0 |
| substation names as station codes (`AGNNYGL` for Agannygal) | 97.1% | 2.1% | 0.9% | 0 | 0 |
| 40% of multi-level stations split into one substation per level | 99.3% | 0.6% | 0.0% | 0 | 0 |
| model 1: `Term_` junctions with a busbar on 30% of the lines | 90.4% | 4.0% | 5.6% (the junctions) | 0 | 0 |
| 5% of the disconnectors removed, 30% of substation names scrambled | 49.8% | 47.8% | 2.4% | 0 | 0 |
| 5% of the disconnectors removed and every name scrambled | 27.3% | 38.2% | 34.5% | 0 | 7 |
| junctions + split stations + codes + impedances × 1.1 + 5% fewer disconnectors | 46.6% | 43.7% | 9.7% | 0 | 1 |

All nine Relicap areas (18,154 objects) with new IDs only: 98.9% certain, 0.7% probable, none wrong, in about 5 seconds; with all the changes of the last row: 49.2% certain (none wrong), 42.8% probable (7 wrong).
