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

**Combined workflow: one dataset against a constraint set, no mapping CSV:**
```
java -jar CimPal-CLI.jar validate ^
  --workflow combined ^
  --constraint-files C:\Data\constraints\EQ.ttl,C:\Data\constraints\SSH.ttl ^
  --data-files C:\Data\models\IGM.zip,C:\Data\models\BoundarySet.zip ^
  --output C:\Data\output
```
Or start from `configs/validate-combined.json`.

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

### `combined`

Validates one dataset without a mapping CSV: the GUI's *Validate selected files together*. All the data files are merged into one dataset, so references between them (SSH to EQ, or to the boundary set) resolve, and the dataset is validated against all the constraint files at once.

Required inputs: `--constraint-files`, `--data-files`, `--output`. Optional: `--constraints-root`.

- **Constraint files:** `.ttl` or `.rdf` files, or ZIP archives of them. Together they form one shapes graph, so a shape in one file can be deactivated or extended by another, and an `owl:imports` that several files share is read once. In a ZIP, every `.ttl` and `.rdf` file is used and relative imports resolve inside the archive, as in a folder; other RDF files in it are named in the warnings. Relative imports are also resolved against `--constraints-root` when it is given, otherwise against the first constraint file's folder.
- **Data files:** RDF/XML (`.xml`) files, or ZIP archives of them, nested archives included (up to three levels). A ZIP without any `.xml` file stops the run instead of validating nothing.
- **Report:** one `validation_report__<date>_<time>.xlsx` in `--output`, in the mapping report's layout, with one validation row per constraint file. Each finding is listed under the file that declares its shape, and a file whose shapes found nothing is marked as conforming. With `--export-turtle`, the SHACL report graph is written beside it as `validation_report__<date>_<time>.ttl`.
- **No partial pass:** an input that can't be read stops the validation with exit code 2 and writes nothing. That includes a file that doesn't parse (the message names it), an `owl:imports` that can't be found, and a path outside the allowed roots under `serve`, `mcp` or `run`. So does a run that would check nothing: no active shape has a target, or the data holds no triples. `--violations-exit-code` never changes exit 2. A report that can't be written (a full disk, a locked workbook) ends with exit 3.

Use it for the acceptance check of a dataset you received. For many datasets, or for data that arrives per timestamp, use `mapping` or `timestamped`.

### Removed: `manual`

The former `manual` workflow and its `--shacl-files` flag are gone. It did not validate datasets: it tested the rules of a constraint set against a folder of Conform / NonConform test models, and it is now the GUI's **SHACL ▸ Constraints Operations ▸ Test SHACL rules against Conform / NonConform models** (see the help page of that tab). `--workflow manual` exits with code 2 and says so.

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. |
| `--workflow` | `mapping` / `timestamped` / `combined` | `mapping` | Which validation mode to run. |
| `--mapping-csv` | file path | — | The CSV mapping file. Required for mapping and timestamped. |
| `--models` | folder path | — | Root folder containing model files (ZIP, XML). Required for mapping and timestamped. |
| `--constraints-root` | folder path | — | Root folder for constraint files. Paths in the mapping CSV are relative to this. Required for mapping and timestamped. Optional for combined: relative `owl:imports` are then also resolved against it. |
| `--constraint-files` | file paths, comma-separated | — | Constraint files (`.ttl`, `.rdf`) or ZIP archives of them, which together form one shapes graph. Required for combined. Config key `constraintFiles`: a JSON array of paths. |
| `--data-files` | file paths, comma-separated | — | Instance data files (`.xml`) or ZIP archives of them, which together form one dataset. Required for combined. Config key `dataFiles`: a JSON array of paths. |
| `--output` | folder path | — | Where to write the Excel report, ZIP bundles, and Turtle reports. Created if it does not exist. Required for every workflow. |
| `--datatype-map` | preset or file path | `CGMES30NC25` | See **Datatype map** section below. |
| `--xml-base` | URI | `http://iec.ch/TC57/CIM100` | Base URI used when parsing RDF/XML model files. See **XML base** section below. |
| `--engine` | see below | `APACHE_JENA` | SHACL evaluation engine. |
| `--workers` | integer | `0` (auto) | Parallel validation workers. `0` = memory-aware auto-sizing. For combined, the workers check the shapes in parallel on the one dataset, and `0` means all processors but one. |
| `--max-results` | integer | `0` (unlimited) | Cap SHACL results per source shape. `10` is useful for quick checks. `0` = run fully. |
| `--format` | `text` / `json` | `text` | Output format. `json` writes a machine-readable summary to stdout. |
| `--samples` | integer | `3` when JSON is produced (`--format json` or `--summary-file`) / `0` otherwise | Per-shape detail in JSON output: max focus-node samples per shape group. `0` disables shape groups entirely. For the mapping workflow, positive values auto-enable `--export-turtle`; combined takes the detail from the results in memory. |
| `--export-turtle` | flag | off | Write a `.ttl` SHACL validation report beside each Excel report. Combined writes one, named like its workbook. |
| `--previous-comparison` | file path | — | XLSX from a previous timestamped run, for the comparison workbook. Timestamped workflow only. |
| `--stats` | flag | off | Report the run's resource use: wall and CPU time, peak heap, GC time, triples loaded. A `stats` field in JSON output on stdout, otherwise a `[STATS]` line on stderr. Config key `stats`. See [resource statistics](README.md#resource-statistics---stats). |
| `--summary-file` | file path | — | Also write the JSON result (exactly what `--format json` prints) to this file, atomically, creating parent folders. Works with any `--format`. Config key `summaryFile`. See [automation options](README.md#automation-options---summary-file---violations-exit-code). |
| `--violations-exit-code` | 0–255 | `1` | Exit code when violations are found. `0` lets a scheduler (Airflow, CI) treat findings as data; exits 2 and 3 are unchanged, and so is exit 1 when a row failed with an error. Config key `violationsExitCode`. Ignored under `serve`, `mcp` and `run`. |
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

**How shape detail works:** for the mapping workflow, when `--samples > 0` and `--format json`, the Turtle validation report files are auto-enabled. After validation, these `.ttl` files are parsed to aggregate per-shape stats. The TTL files remain on disk alongside the Excel report and can be opened in the AI Assistant for targeted analysis. The combined workflow groups the results it holds in memory instead, and writes no Turtle unless `--export-turtle` is set.

### Combined workflow

`run.workflow` is `combined`, and `run.inputs` lists `constraintFiles`, `dataFiles`, `constraintsRoot` (`null` when not set) and `outputDir`. `totals` count the workbook's rows, one per constraint file, as the mapping workflow's count its rows:

- `conforming`: files whose shapes found nothing.
- `violations`: files with at least one finding of any severity.
- `errors`: always 0, because a run that can't read its input exits with 2 instead.
- `total`: the number of rows.

These fields are added:

```json
  "partial": false,
  "results": {"total": 3, "violations": 1, "warnings": 2, "infos": 0},
  "byConstraintFile": [
    {"constraintFile": "EQ.ttl", "conforms": false, "results": {"total": 1, "violations": 1, "warnings": 0, "infos": 0}},
    {"constraintFile": "SSH.ttl", "conforms": false, "results": {"total": 2, "violations": 0, "warnings": 2, "infos": 0}}
  ],
  "warnings": [],
  "turtleReport": "C:/Data/output/validation_report__20261007_094424.ttl",
  "report": "C:/Data/output/validation_report__20261007_094424.xlsx"
```

- **`partial`**: true when `--max-results` cut the run short. No file is then shown as conforming.
- **`results`**: the findings by severity, counted the way the workbook counts them.
- **`byConstraintFile`**: one entry per workbook row. With a Python engine, findings on anonymous shapes can't be matched to their file, and the report falls back to one row that names all the constraint files.
- **`warnings`**: problems that did not stop the run, for example RDF files in a constraints ZIP that are neither `.ttl` nor `.rdf`. They are also printed on stderr.
- **`turtleReport`**: present only with `--export-turtle`.

---

## Workers and memory

The `--workers 0` default sizes workers from the JVM heap: roughly one worker per 6 GB, capped at 12 for the mapping workflow. This is safe for most machines. Override with an explicit number if you see out-of-memory errors (reduce it) or want to push throughput on a machine with plenty of RAM (increase it).

Each worker loads a full combined data graph into memory. A CGMES 3.0 full-grid model typically occupies 2–4 GB per worker.

The combined workflow is different: it loads all its data files into one graph, and its workers share that graph and check its shapes in parallel. Its memory follows the size of the merged dataset, not the worker count.

`--stats` reports what a run actually used (peak heap, triples loaded, CPU time). For heap and core recommendations by model size, see the [sizing guide](../guide/sizing.md). A run that runs out of memory ends with exit code 3, never 1.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Validation ran — no violations |
| 1 | Validation ran — violations found (check the Excel report) |
| 2 | Bad input — missing file, wrong path, unknown workflow name. For combined, also an input that can't be read (a file that doesn't parse, an `owl:imports` that can't be found, an archive without the files it should hold) and a run that would check nothing |
| 3 | Internal error — unexpected exception (check stderr); out of memory included (one `[ERROR] Out of memory` line on stderr; see [resource statistics](README.md#resource-statistics---stats)) |
