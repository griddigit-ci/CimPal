# `compare-instances` — Instance Data Comparison Command

Compares two sets of CGMES instance-data model files and reports differences. Useful for auditing what changed between two model submissions, or verifying that a conversion or transformation preserved all data.

---

## Quick examples

**Compare two model sets:**
```
java -jar CimPal-CLI.jar compare-instances ^
  --models-a C:\Data\before\EQ.xml,C:\Data\before\TP.xml,C:\Data\before\SV.xml ^
  --models-b C:\Data\after\EQ.xml,C:\Data\after\TP.xml,C:\Data\after\SV.xml
```

**Compare, ignoring state variables (SV) and topology (TP):**
```
java -jar CimPal-CLI.jar compare-instances ^
  --models-a C:\Data\v1\EQ.xml ^
  --models-b C:\Data\v2\EQ.xml ^
  --ignore-sv --ignore-tp ^
  --output C:\Data\eq-diff.xlsx
```

**Machine-readable output for CI:**
```
java -jar CimPal-CLI.jar compare-instances ^
  --models-a C:\Data\before\EQ.xml ^
  --models-b C:\Data\after\EQ.xml ^
  --format json > diff.json
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. |
| `--models-a` | comma-separated paths | — | First set of instance-data files (the "before" or reference version). **Required.** |
| `--models-b` | comma-separated paths | — | Second set of instance-data files (the "after" or changed version). **Required.** |
| `--xml-base` | URI | `http://iec.ch/TC57/CIM100` | Base URI for parsing RDF/XML model files. Use `http://iec.ch/TC57/2013/CIM-schema-cim16` for CGMES 2.4. |
| `--ignore-sv` | flag | off | Ignore differences in SV (state variable) profile objects: `SvVoltage`, `SvPowerFlow`, `SvInjection`, `SvStatus`, `SvSwitch`, `SvTapStep`, `SvShuntCompensatorSections`, `TopologicalIsland`. |
| `--ignore-tp` | flag | off | Ignore differences in TP (topology) profile objects. |
| `--ignore-dl` | flag | off | Ignore differences in DL (diagram layout) profile objects. |
| `--output` | file path | — | Write results to a file. Use `.xlsx` for Excel; any other extension for CSV. If omitted, results go to stdout. |
| `--format` | `text` / `json` / `csv` | `text` | Output format for stdout. Ignored when `--output` is set. |
| `--dry-run` | flag | off | Print resolved config and exit without comparing. |

---

## What gets compared

All files in `--models-a` are merged into one combined graph; all files in `--models-b` are merged into another. The comparison then runs as a two-pass graph diff:

1. Forward pass: every object in A is checked against B — reports additions (in A, not in B) and modifications (in both, different value)
2. Reverse pass: every object in B is checked against A — reports deletions (in B, not in A)

For power-flow results (`SvVoltage`, `SvPowerFlow`, etc.), the comparison is value-based with numeric tolerance rather than exact string matching, since floating-point representation may differ between tools.

---

## Ignore flags

Use the ignore flags when comparing models where only the equipment network (EQ) changed and the state variable / topology results are expected to differ due to re-simulation.

`--ignore-sv` is the most commonly needed flag when comparing two EQ snapshots from different time steps.

---

## Output formats

Same format options as the `compare` command:
- **Text** — pipe-separated table (item | type | property | model-a | model-b)
- **JSON** — `{"schema":"cimpal-compare-instances-result/1","totalDifferences":N,"differences":[...]}`
- **CSV** — RFC 4180
- **Excel** — `.xlsx` with header row

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Model sets are identical |
| 1 | Differences found |
| 2 | Bad input (file not found, no files specified) |
| 3 | Internal error |

---

## JSON config format

```json
{
  "_comment": "Compare two CGMES model sets",

  "modelsA": [
    "REPLACE_WITH_PATH/before/EQ.xml",
    "REPLACE_WITH_PATH/before/TP.xml",
    "REPLACE_WITH_PATH/before/SV.xml"
  ],
  "_note_modelsA": "Files in the first (before / reference) set. All loaded and merged into one graph. Accepts .xml, .rdf, .ttl, or .zip.",

  "modelsB": [
    "REPLACE_WITH_PATH/after/EQ.xml",
    "REPLACE_WITH_PATH/after/TP.xml",
    "REPLACE_WITH_PATH/after/SV.xml"
  ],
  "_note_modelsB": "Files in the second (after / changed) set.",

  "xmlBase": "http://iec.ch/TC57/CIM100",
  "_note_xmlBase": "For CGMES 2.4 use http://iec.ch/TC57/2013/CIM-schema-cim16",

  "ignoreSv": false,
  "_note_ignoreSv": "true to skip SvVoltage, SvPowerFlow, SvInjection, SvStatus, SvSwitch, SvTapStep, SvShuntCompensatorSections, TopologicalIsland",

  "ignoreTp": false,
  "ignoreDl": false,

  "output": "REPLACE_WITH_PATH/diff.xlsx",
  "_note_output": "Use .xlsx for Excel. Remove this field to print to stdout instead.",

  "format": "text"
}
```
