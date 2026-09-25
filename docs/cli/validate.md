<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `validate` — SHACL Validation Command

Validates CGMES/CIM model files against SHACL constraint files. Produces an Excel report and, optionally, Turtle validation report files.

---

## Quick examples

**Mapping workflow from a config file (most common):**
```
java -jar CimPal-CLI.jar validate --config configs/validate-mapping-cgmes30.json
```

**Same thing with flags only:**
```
java -jar CimPal-CLI.jar validate ^
  --workflow mapping ^
  --mapping-csv C:\Data\mapping.csv ^
  --models C:\Data\models ^
  --constraints-root C:\Data\constraints ^
  --output C:\Data\output ^
  --datatype-map CGMES30NC25 ^
  --xml-base "http://iec.ch/TC57/CIM100"
```
*(Use `\` instead of `^` for line continuation in bash.)*

**Check what would run without actually running it:**
```
java -jar CimPal-CLI.jar validate --config configs/validate-mapping-cgmes30.json --dry-run
```

**Machine-readable JSON output for CI:**
```
java -jar CimPal-CLI.jar validate --config nightly.json --format json > result.json
```

---

## Workflows

The `--workflow` flag selects one of three validation modes. Defaults to `mapping`.

### `mapping` (default)

Reads a CSV mapping file that links model files to their SHACL constraint files. Validates each combination and produces one aggregated Excel report plus ZIP bundles.

Required inputs: `--mapping-csv`, `--models`, `--constraints-root`, `--output`

The mapping CSV has three columns (no header required):
- **Column 1 — xml_inputs**: comma-separated model file names (relative to `--models`) or GitHub raw URLs
- **Column 2 — ttl**: path to the constraint `.ttl` file, relative to `--constraints-root`
- **Column 3 — notes**: free text label, appears in the report

Example mapping CSV row:
```
EQ.xml,SV.xml,TP.xml | constraints/QoCDC/Level2.ttl | Level 2 quality checks
```

### `timestamped`

Like `mapping`, but discovers files by timestamp embedded in filenames. Groups them by timestamp, runs the mapping once per group, and produces:
- One report per timestamp
- An aggregated summary across all timestamps
- An optional comparison workbook showing deltas vs. a previous run

Required inputs: same as mapping, plus `--previous-comparison` (optional)

Use this when model files are named with a date/time stamp and you want trend tracking across runs.

### `manual`

No mapping CSV. You pick the SHACL constraint files directly; the CLI scans the `--models` folder for ZIP model archives and validates each one against all selected shapes.

Results go to the Output pane (stderr in CLI mode). Excel reports are written beside each ZIP archive.

Required inputs: `--shacl-files`, `--models`

Use this when developing or testing a specific shape — it is the fastest way to check one set of constraints against a folder of test models.

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. |
| `--workflow` | `mapping` / `timestamped` / `manual` | `mapping` | Which validation mode to run. |
| `--mapping-csv` | file path | — | The CSV mapping file. Required for mapping and timestamped. |
| `--models` | folder path | — | Root folder containing model files (ZIP, XML). Required always. |
| `--constraints-root` | folder path | — | Root folder for constraint files. Paths in the mapping CSV are relative to this. Required for mapping and timestamped. |
| `--output` | folder path | — | Where to write the Excel report, ZIP bundles, and Turtle reports. Created if it does not exist. Required for mapping and timestamped. |
| `--shacl-files` | comma-separated file paths | — | SHACL `.ttl` files for the manual workflow. Multiple files are unioned into one shapes graph. |
| `--datatype-map` | preset or file path | `CGMES30NC25` | See **Datatype map** section below. |
| `--xml-base` | URI | `http://iec.ch/TC57/CIM100` | Base URI used when parsing RDF/XML model files. See **XML base** section below. |
| `--engine` | see below | `APACHE_JENA` | SHACL evaluation engine. |
| `--workers` | integer | `0` (auto) | Parallel validation workers. `0` = memory-aware auto-sizing. |
| `--max-results` | integer | `0` (unlimited) | Cap SHACL results per source shape. `10` is useful for quick checks. `0` = run fully. |
| `--format` | `text` / `json` | `text` | Output format. `json` writes a machine-readable summary to stdout. |
| `--samples` | integer | `3` (json mode) / `0` (text mode) | Per-shape detail in JSON output: max focus-node samples per shape group. `0` disables shape groups entirely. Positive values auto-enable `--export-turtle`. |
| `--export-turtle` | flag | off | Write a `.ttl` SHACL validation report beside each Excel report. |
| `--previous-comparison` | file path | — | XLSX from a previous timestamped run, for the comparison workbook. Timestamped workflow only. |
| `--dry-run` | flag | off | Print the resolved config and exit without running anything. |

---

## Datatype map

The datatype map tells the RDF parser which XSD type to assign to each CIM literal. Without it, every literal arrives as a plain string, and constraints that check numeric ranges or boolean values silently pass because there is nothing to compare — a string `"true"` is not the same as a typed boolean `true`.

**Preset names** (use these as the `--datatype-map` value):

| Preset | Covers |
|---|---|
| `CGMES30NC25` | CIM17 / CGMES 3.0 / NC 2.5 — **use this by default for CGMES 3 work** |
| `CGMES30NC24` | CIM17 / CGMES 3.0 / NC 2.4 |
| `CGMES24NC22` | CIM16 / CGMES 2.4 / NC 2.2 — use this for CGMES 2.4 datasets |

**Custom map:** supply a path to a `.properties` file in the same format as the bundled ones. The bundled maps are inside the JAR but can be extracted if you need them as a starting point.

---

## XML base

The XML base URI is used when parsing RDF/XML files that use relative URIs in `rdf:about` attributes (which is standard in CGMES). Without the correct base, all subject URIs resolve to something wrong and the model appears empty or broken.

**Common values:**

| Dataset | XML base URI |
|---|---|
| CGMES 3.0 (CIM17) | `http://iec.ch/TC57/CIM100` — **default** |
| CGMES 2.4 (CIM16) | `http://iec.ch/TC57/2013/CIM-schema-cim16` |
| Stable CIM (ucaiug) | `https://cim.ucaiug.io/ns` |

If models load but produce zero violations on rules you know should fire, a wrong XML base is the first thing to check.

---

## Validation engines

| Value | Description |
|---|---|
| `APACHE_JENA` | Default. Runs inside the JVM. Validated against QoCDC/Coreso shape sets. Use this for all production runs. |
| `PYSHACL` | Experimental. Requires Python + `pyshacl` installed. Uses RDFLib SPARQL. Results may differ from Jena on complex SPARQL constraints. |
| `PYSHACL_OXIGRAPH` | Experimental. pySHACL with Oxigraph SPARQL backend. Requires `pip install "pyshacl[oxigraph]"`. |
| `RUST_SHACL` | Experimental. Rust-based SHACL evaluator via PyPI. Fast for its supported core subset; rejects some valid SPARQL expressions used by QoCDC shapes. |

Install Python engines: `py -3 -m pip install "pyshacl[oxigraph]" shacl`

---

## JSON output format

When `--format json`, the command prints a JSON object to stdout and all progress messages to stderr. Redirect stdout to capture it:

```
java -jar CimPal-CLI.jar validate --config run.json --format json > result.json
```

The JSON structure:
```json
{
  "schema": "cimpal-validate-summary/1",
  "run": {
    "timestamp": "2026-09-18T10:30:00Z",
    "workflow": "mapping",
    "inputs": {
      "mappingCsv": "C:/Data/mapping.csv",
      "modelsDir": "C:/Data/models",
      "constraintsRoot": "C:/Data/constraints",
      "outputDir": "C:/Data/output"
    },
    "options": {
      "datatypeMap": "CGMES30NC25",
      "xmlBase": "http://iec.ch/TC57/CIM100",
      "engine": "APACHE_JENA",
      "workers": 4,
      "maxResultsPerConstraint": 0
    }
  },
  "totals": {
    "conforming": 5,
    "violations": 42,
    "errors": 0,
    "total": 47
  },
  "hasViolations": true,
  "report": "C:/Data/output/validation_report__20260918_103045.xlsx"
}
```

**`totals.conforming`** — number of mapping rows that passed (no violations)  
**`totals.violations`** — number of mapping rows with at least one violation  
**`totals.errors`** — number of mapping rows that errored during validation  
**`shapes`** — per-shape violation groups (present when `--samples > 0`, which is the default in JSON mode). Each group:
- `shapeId` — the shape URI that fired
- `constraint` — the SHACL constraint component (e.g. `sh:MinCountConstraintComponent`)
- `path` — the property path the constraint fires on
- `count` — total violation count for this shape+constraint across all validated models
- `sampleFocusNodes` — up to `--samples` focus node URIs (the instances that violated the constraint)

**`report`** — absolute path to the generated Excel workbook (always written, even in JSON mode)

**Example `shapes` block:**
```json
"shapes": [
  {
    "shapeId": "http://example.org/shapes/EQ#VoltageLevel.highVoltageLimit",
    "constraint": "sh:MinCountConstraintComponent",
    "path": "http://iec.ch/TC57/CIM100#VoltageLevel.highVoltageLimit",
    "count": 15,
    "sampleFocusNodes": [
      "http://example.org#_uuid-vl-1",
      "http://example.org#_uuid-vl-2",
      "http://example.org#_uuid-vl-3"
    ]
  }
]
```

**`--samples 0`** disables shape groups entirely (shorter JSON, faster post-processing). Use when only the totals matter.

**How shape detail works:** when `--samples > 0` and `--format json`, the Turtle validation report files are auto-enabled. After validation, these `.ttl` files are parsed to aggregate per-shape stats. The TTL files remain on disk alongside the Excel report and can be opened in the AI Assistant for targeted analysis.

---

## Workers and memory

The `--workers 0` default sizes workers from the JVM heap: roughly one worker per 6 GB, capped at 12 for the mapping workflow. This is safe for most machines. Override with an explicit number if you see out-of-memory errors (reduce it) or want to push throughput on a machine with plenty of RAM (increase it).

Each worker loads a full combined data graph into memory. A CGMES 3.0 full-grid model typically occupies 2–4 GB per worker.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Validation ran — no violations |
| 1 | Validation ran — violations found (check the Excel report) |
| 2 | Bad input — missing file, wrong path, unknown workflow name |
| 3 | Internal error — unexpected exception (check stderr) |
