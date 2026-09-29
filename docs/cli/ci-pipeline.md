<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# CI Pipeline — Command Collection

This file collects the exact CLI commands for a complete validation CI run. It is organized as a sequence of steps from data preparation through report generation.

**How to use this file:**
- Copy the command blocks into your CI script (PowerShell, bash, or a CI YAML file).
- Replace every `REPLACE_WITH_...` placeholder with your actual path or value.
- The format notes after each placeholder tell you exactly what is expected.
- Commands that are already finalized can be run as-is once placeholders are filled.
- Steps marked `[NOT YET IMPLEMENTED]` need additional CLI commands that will be added in future phases.

---

## Variables to set at the top of your script

Set these once and reuse them throughout. All commands below reference these variables.

```powershell
# PowerShell

# Path to the CimPal CLI fat JAR
$JAR = "REPLACE_WITH_PATH\CimPal-CLI.jar"
# NOTE: Full path to the built JAR file.
#       Example: "C:\Tools\CimPal\CimPal-CLI.jar"
#       Build it first with: mvn package -pl CimPal-CLI -am -DskipTests

# Root folder containing the CGMES model ZIP/XML archives to validate
$MODELS_DIR = "REPLACE_WITH_PATH\models"
# NOTE: This folder is scanned recursively (up to depth 3) for .zip files.
#       Each .zip should contain one or more CGMES instance files (EQ, TP, SV, etc.).
#       Example: "C:\Data\CGMES\models\20260918"

# The CSV mapping file that links model sets to their constraint files
$MAPPING_CSV = "REPLACE_WITH_PATH\mapping.csv"
# NOTE: The mapping CSV has three columns (no header row required):
#         Column 1: model file names, comma-separated (relative to MODELS_DIR), or GitHub URLs
#         Column 2: path to the .ttl constraint file, relative to CONSTRAINTS_ROOT
#         Column 3: a label for the report (free text)
#       Example row: "EQ.xml,TP.xml,SV.xml | QoCDC/Level2-IGM.ttl | IGM Level 2 check"
#       Example: "C:\Data\CGMES\mapping.csv"

# Root folder containing all SHACL constraint (.ttl) files
$CONSTRAINTS_ROOT = "REPLACE_WITH_PATH\constraints"
# NOTE: Constraint file paths in the mapping CSV are resolved relative to this folder.
#       Typically this is a checkout of a shapes repository (e.g., the Coreso shapes repo).
#       Example: "C:\Data\CGMES\constraints"

# Folder where validation reports and ZIPs will be written
$OUTPUT_DIR = "REPLACE_WITH_PATH\output"
# NOTE: Created automatically if it does not exist.
#       The main output is an Excel workbook named validation_report__<timestamp>.xlsx
#       and ZIP archives of the model files grouped by mapping row.
#       Example: "C:\Data\CGMES\output\20260918"

# Which CGMES/CIM version combination the models use
$DATATYPE_MAP = "CGMES30NC25"
# NOTE: Must be one of:
#         CGMES30NC25 — CGMES 3.0, NC 2.5 (use for current CGMES 3 work)
#         CGMES30NC24 — CGMES 3.0, NC 2.4
#         CGMES24NC22 — CGMES 2.4, NC 2.2 (use for older datasets)
#       Or a path to a custom .properties datatype map file.

# The base URI to use when parsing the RDF/XML model files
$XML_BASE = "http://iec.ch/TC57/CIM100"
# NOTE: Must match the namespace declared in the model files.
#       Common values:
#         CGMES 3.0 (CIM17): "http://iec.ch/TC57/CIM100"  <- this is the default
#         CGMES 2.4 (CIM16): "http://iec.ch/TC57/2013/CIM-schema-cim16"
#       A wrong base URI causes models to load empty (all relative URIs resolve nowhere).

# Number of parallel validation workers (0 = auto-sized from available RAM)
$WORKERS = 0
# NOTE: Each worker loads a full combined data graph.
#       Rule of thumb: 1 worker per 6 GB of heap (-Xmx).
#       Increase only if you have confirmed enough memory.
#       For CI machines with limited RAM, set to 1 or 2 explicitly.

# Maximum violations to collect per source shape (0 = collect all)
$MAX_RESULTS = 0
# NOTE: 0 runs a complete validation — use for official runs.
#       10 is useful for quick checks during shape development.

# Where to write the JSON summary output
$JSON_OUTPUT = "REPLACE_WITH_PATH\validation-result.json"
# NOTE: The JSON summary is a machine-readable version of the totals.
#       Recommended: keep it in the same output folder as the Excel report.
#       Example: "$OUTPUT_DIR\validation-result.json"
```

```bash
# bash equivalent

JAR="REPLACE_WITH_PATH/CimPal-CLI.jar"
MODELS_DIR="REPLACE_WITH_PATH/models"
MAPPING_CSV="REPLACE_WITH_PATH/mapping.csv"
CONSTRAINTS_ROOT="REPLACE_WITH_PATH/constraints"
OUTPUT_DIR="REPLACE_WITH_PATH/output"
DATATYPE_MAP="CGMES30NC25"
XML_BASE="http://iec.ch/TC57/CIM100"
WORKERS=0
MAX_RESULTS=0
JSON_OUTPUT="REPLACE_WITH_PATH/validation-result.json"
```

---

## Step 1 — Generate manifest (if required by the toolchain)

Only needed if your downstream system requires a `manifest.ttl` before it will accept the model files.

```powershell
java -jar $JAR manifest `
  --dir $MODELS_DIR `
  --output "$OUTPUT_DIR\manifest.ttl"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Manifest generation failed (exit $LASTEXITCODE)"
    exit $LASTEXITCODE
}
```

```bash
java -jar "$JAR" manifest \
  --dir "$MODELS_DIR" \
  --output "$OUTPUT_DIR/manifest.ttl"

[ $? -ne 0 ] && { echo "Manifest generation failed"; exit 1; }
```

---

## Step 2 — Run validation (mapping workflow)

This is the main step. Validates all model sets in the mapping CSV and produces the Excel report.

```powershell
java -jar $JAR validate `
  --workflow mapping `
  --mapping-csv $MAPPING_CSV `
  --models $MODELS_DIR `
  --constraints-root $CONSTRAINTS_ROOT `
  --output $OUTPUT_DIR `
  --datatype-map $DATATYPE_MAP `
  --xml-base $XML_BASE `
  --workers $WORKERS `
  --max-results $MAX_RESULTS `
  --format json `
  --export-turtle > $JSON_OUTPUT 2>&1

$EXIT = $LASTEXITCODE
```

```bash
java -jar "$JAR" validate \
  --workflow mapping \
  --mapping-csv "$MAPPING_CSV" \
  --models "$MODELS_DIR" \
  --constraints-root "$CONSTRAINTS_ROOT" \
  --output "$OUTPUT_DIR" \
  --datatype-map "$DATATYPE_MAP" \
  --xml-base "$XML_BASE" \
  --workers "$WORKERS" \
  --max-results "$MAX_RESULTS" \
  --format json \
  --export-turtle > "$JSON_OUTPUT" 2>&1

EXIT=$?
```

---

## Step 3 — Check exit code and report

```powershell
switch ($EXIT) {
    0 {
        Write-Host "Validation passed — no violations found."
        Write-Host "Report: $OUTPUT_DIR"
    }
    1 {
        Write-Warning "Validation completed — violations found."
        Write-Warning "Open the Excel report in: $OUTPUT_DIR"
        # NOTE: Exit code 1 is a validation result, not a tool failure.
        #       Decide here whether to fail the CI build or just flag it.
        #       To fail the build on violations: uncomment the next line.
        # exit 1
    }
    2 {
        Write-Error "Bad input — check the paths and config values above."
        exit 2
    }
    default {
        Write-Error "CimPal CLI internal error (exit $EXIT). Check stderr output."
        exit $EXIT
    }
}
```

```bash
case $EXIT in
  0) echo "Validation passed — no violations found." ;;
  1) echo "WARNING: Violations found. Check the Excel report in: $OUTPUT_DIR"
     # Uncomment to fail the build on violations:
     # exit 1
     ;;
  2) echo "ERROR: Bad input. Check paths and config."; exit 2 ;;
  *) echo "ERROR: Internal error (exit $EXIT). Check stderr."; exit $EXIT ;;
esac
```

---

## Step 4 — Parse JSON summary (optional, for CI dashboards)

The JSON written by `--format json` contains the totals. Read it in the next CI step to populate a dashboard or send a notification.

```powershell
$result = Get-Content $JSON_OUTPUT | ConvertFrom-Json
Write-Host "Conforming:  $($result.totals.conforming)"
Write-Host "Violations:  $($result.totals.violations)"
Write-Host "Errors:      $($result.totals.errors)"
Write-Host "Report path: $($result.report)"
```

```bash
# requires jq: https://jqlang.github.io/jq/
conforming=$(jq '.totals.conforming' "$JSON_OUTPUT")
violations=$(jq '.totals.violations' "$JSON_OUTPUT")
echo "Conforming: $conforming  Violations: $violations"
```

---

## Step 5 — Timestamped validation (if tracking trends over time)

Use this step instead of Step 2 when model files are named with timestamps and you want to track violations over time.

```powershell
# Path to the comparison workbook from the previous run.
# Leave empty on the first run; supply the previous result.xlsx on subsequent runs.
$PREVIOUS_COMPARISON = ""
# NOTE: This is the validation_comparison__*.xlsx file produced by a previous timestamped run.
#       The CLI adds a new timestamp sheet to it and computes deltas.
#       Format: full path to the .xlsx file.
#       Example: "C:\Data\CGMES\output\20260901\validation_comparison__20260901.xlsx"
#       Leave as empty string "" on the first run — the comparison workbook will have no delta chart.

$prevArg = if ($PREVIOUS_COMPARISON) { "--previous-comparison `"$PREVIOUS_COMPARISON`"" } else { "" }

java -jar $JAR validate `
  --workflow timestamped `
  --mapping-csv $MAPPING_CSV `
  --models $MODELS_DIR `
  --constraints-root $CONSTRAINTS_ROOT `
  --output $OUTPUT_DIR `
  --datatype-map $DATATYPE_MAP `
  --xml-base $XML_BASE `
  --workers $WORKERS `
  --max-results $MAX_RESULTS `
  --format json `
  $prevArg > $JSON_OUTPUT
```

---

## Step 6 — Quick spot-check (shape development, not for official runs)

A fast check that caps results at 10 per shape and runs the full mapping but stops early once each shape has enough samples to show problems.

```powershell
java -jar $JAR validate `
  --config REPLACE_WITH_PATH\your-run.json `
  --max-results 10 `
  --format json > "$OUTPUT_DIR\quick-check.json"

# NOTE: --max-results 10 means validation reports at most 10 violations per source shape.
#       Validation is marked "Partial" for shapes that exceeded the limit.
#       Do not use this for final/official validation runs.
```

---

## Step 7 — SPARQL inspection query [NOT YET IMPLEMENTED as a standard workflow]

Use this after a validation run to inspect the data directly — for example, to look up all instances of a class, count triples by type, or check specific property values.

```powershell
java -jar $JAR sparql `
  --models "REPLACE_WITH_PATH\EQ.xml","REPLACE_WITH_PATH\TP.xml" `
  --query "REPLACE_WITH_PATH\inspect.sparql" `
  --xml-base $XML_BASE `
  --output "REPLACE_WITH_PATH\inspection-results.xlsx"

# NOTE: --models accepts a comma-separated list of file paths.
#       Format: absolute paths to .xml, .rdf, .ttl, or .zip files.
#       All files are loaded into one combined graph before the query runs.
#       The .sparql file contains a standard SPARQL SELECT query.
#       Results go to the .xlsx file (one sheet, headers in row 1).
```

---

## Step 7 — SPARQL inspection query

Use this after a validation run to inspect the data directly — for example, to look up all instances of a class, count triples by type, or check specific property values.

```powershell
java -jar $JAR sparql `
  --models "REPLACE_WITH_PATH\EQ.xml","REPLACE_WITH_PATH\TP.xml" `
  --query "REPLACE_WITH_PATH\inspect.sparql" `
  --xml-base $XML_BASE `
  --output "REPLACE_WITH_PATH\inspection-results.xlsx"

# NOTE: --models accepts comma-separated file paths.
#       Format: absolute paths to .xml, .rdf, .ttl, or .zip files.
#       All files are merged into one combined graph before the query runs.
#       --query accepts a path to a .sparql/.rq file, or an inline query string.
#       --output with .xlsx extension writes Excel; any other extension writes CSV.
```

```bash
java -jar "$JAR" sparql \
  --models "REPLACE_WITH_PATH/EQ.xml,REPLACE_WITH_PATH/TP.xml" \
  --query "REPLACE_WITH_PATH/inspect.sparql" \
  --xml-base "$XML_BASE" \
  --output "REPLACE_WITH_PATH/inspection-results.xlsx"
```

---

## Step 8 — Convert to canonical Turtle (for git-friendly diffs)

Converts RDF/XML model or profile files to Turtle for committing to git. Turtle diffs are human-readable; RDF/XML diffs are not.

```powershell
# Convert a single model file
java -jar $JAR convert `
  --input "REPLACE_WITH_PATH\EQ.xml" `
  --output "REPLACE_WITH_PATH\EQ.ttl" `
  --xml-base $XML_BASE `
  --sort

# NOTE: --input  : path to source file (.xml or .rdf for RDF/XML, .ttl for Turtle)
#       --output : path for the converted file. Extension determines target format:
#                  .ttl → Turtle, .xml/.rdf → RDF/XML, .jsonld → JSON-LD
#       --xml-base : base URI for parsing RDF/XML. Use the same value as $XML_BASE above.
#       --sort   : produces deterministic output so git diffs are stable.
#                  Without this flag, triple ordering may differ between runs.

if ($LASTEXITCODE -ne 0) { Write-Error "Conversion failed"; exit $LASTEXITCODE }
```

```bash
java -jar "$JAR" convert \
  --input "REPLACE_WITH_PATH/EQ.xml" \
  --output "REPLACE_WITH_PATH/EQ.ttl" \
  --xml-base "$XML_BASE" \
  --sort

[ $? -ne 0 ] && { echo "Conversion failed"; exit 1; }
```

**Merge multiple files into one before converting** (e.g. for a full-grid combined model):
```powershell
java -jar $JAR convert `
  --input-files "REPLACE_WITH_PATH\EQ.xml,REPLACE_WITH_PATH\TP.xml,REPLACE_WITH_PATH\SV.xml" `
  --output "REPLACE_WITH_PATH\merged.ttl" `
  --xml-base $XML_BASE `
  --sort

# NOTE: --input-files : comma-separated list of source files.
#       All files are merged into one graph and written to --output as a single file.
```

---

## Step 9 — Generate SHACL shapes from RDFS profile

Used when a new profile version is released and needs a fresh set of scaffold SHACL shapes. Run this once per profile version, then refine the generated shapes manually.

```powershell
$RDFS_FILES = "REPLACE_WITH_PATH\EQ.rdf,REPLACE_WITH_PATH\TP.rdf"
# NOTE: Comma-separated paths to the RDFS profile .rdf files.
#       These are the files exported by cimsyntaxgen or cimcontextor.
#       One .ttl shape file is generated per input file.

$SHAPES_OUTPUT_DIR = "REPLACE_WITH_PATH\generated-shapes"
# NOTE: Folder where the generated .ttl files are written.
#       Created automatically if it does not exist.
#       Files are named after the input: EQ.rdf → EQ.ttl, TP.rdf → TP.ttl

java -jar $JAR rdfs2shacl `
  --rdfs-files $RDFS_FILES `
  --output-dir $SHAPES_OUTPUT_DIR `
  --rdfs-format 2020

# NOTE: --rdfs-format 2020 handles the current cimsyntaxgen augmented RDFS format.
#       Use 2019 for older exports without owl:Ontology headers.

if ($LASTEXITCODE -ne 0) { Write-Error "Shape generation failed"; exit $LASTEXITCODE }
Write-Host "Generated shapes written to: $SHAPES_OUTPUT_DIR"
```

```bash
RDFS_FILES="REPLACE_WITH_PATH/EQ.rdf,REPLACE_WITH_PATH/TP.rdf"
SHAPES_OUTPUT_DIR="REPLACE_WITH_PATH/generated-shapes"

java -jar "$JAR" rdfs2shacl \
  --rdfs-files "$RDFS_FILES" \
  --output-dir "$SHAPES_OUTPUT_DIR" \
  --rdfs-format 2020

[ $? -ne 0 ] && { echo "Shape generation failed"; exit 1; }
```

**For CGMES 2.4 (CIM16) profiles**, add the io-uri override:
```powershell
java -jar $JAR rdfs2shacl `
  --rdfs-files $RDFS_FILES `
  --output-dir $SHAPES_OUTPUT_DIR `
  --rdfs-format 2020 `
  --io-uri "http://iec.ch/TC57/2013/CIM-schema-cim16#IdentifiedObject.mRID"
```

---

## Step 10 — Compare profile versions

Detects what changed between two releases of an RDFS profile or two versions of a SHACL shape set. Run this as part of a profile migration audit.

```powershell
$PROFILE_V_OLD = "REPLACE_WITH_PATH\EQ-v2024.rdf"
# NOTE: The "before" / reference version.
#       Format: path to an .rdf (RDFS) or .ttl (SHACL) file.

$PROFILE_V_NEW = "REPLACE_WITH_PATH\EQ-v2026.rdf"
# NOTE: The "after" / changed version.

$DIFF_OUTPUT = "REPLACE_WITH_PATH\profile-diff.xlsx"
# NOTE: Where to write the diff report.
#       Use .xlsx for Excel (recommended), any other extension for CSV.

java -jar $JAR compare `
  --file-a $PROFILE_V_OLD `
  --file-b $PROFILE_V_NEW `
  --output $DIFF_OUTPUT

$EXIT = $LASTEXITCODE
if ($EXIT -eq 0) { Write-Host "Files are identical — no differences." }
if ($EXIT -eq 1) { Write-Warning "Differences found. See: $DIFF_OUTPUT" }
if ($EXIT -ge 2) { Write-Error "Compare failed (exit $EXIT)"; exit $EXIT }

# NOTE: Exit code 0 = identical, 1 = differences found, 2+ = error.
#       The diff report lists every changed class, attribute, or association
#       with the old value (file-a column) and the new value (file-b column).
```

```bash
java -jar "$JAR" compare \
  --file-a "REPLACE_WITH_PATH/EQ-v2024.rdf" \
  --file-b "REPLACE_WITH_PATH/EQ-v2026.rdf" \
  --output "REPLACE_WITH_PATH/profile-diff.xlsx"

case $? in
  0) echo "Identical — no differences." ;;
  1) echo "Differences found — check the diff report." ;;
  *) echo "Compare failed"; exit 1 ;;
esac
```

**Compare SHACL shape files:**
```powershell
java -jar $JAR compare `
  --file-a "REPLACE_WITH_PATH\shapes-v1.ttl" `
  --file-b "REPLACE_WITH_PATH\shapes-v2.ttl" `
  --compare-type shacl `
  --format json > diff.json
```

**Compare profiles with different CIM version namespaces** (e.g. CIM16 vs CIM17):
```powershell
java -jar $JAR compare `
  --file-a $PROFILE_V_OLD `
  --file-b $PROFILE_V_NEW `
  --normalize-cim-version `
  --output $DIFF_OUTPUT
# NOTE: --normalize-cim-version renames the 'cim' namespace in file-b to match
#       file-a before comparing. Without this, every URI difference caused by the
#       namespace change shows as a separate diff entry, drowning out real changes.
```

---

## Planned future steps [NOT YET IMPLEMENTED]

These steps will be added as CLI commands in Phase 4 once Core extraction is complete.

### `organize` — SHACL organizer / canonical formatting
```
# [NOT YET IMPLEMENTED — Phase 4]
# Splits and reformats SHACL .ttl files according to an Excel template.
# Use after editing shapes to restore canonical ordering and consistent structure.
#
# Will need:
#   --shacl-files   : one or more .ttl files to organize (comma-separated)
#                     Format: absolute paths to SHACL .ttl or .rdf files
#   --template-xlsx : Excel template (.xlsx) defining the output structure
#                     Format: absolute path to the .xlsx template file
#   --output-dir    : folder where reorganized files are written
#                     Format: absolute path, created if it does not exist
```

### `gen-instances` — Generate synthetic test instance data

Use to create small conforming/non-conforming CGMES instance files for shape testing.

```powershell
java -jar $JAR gen-instances `
  --template-xlsx "REPLACE_WITH_PATH\test-fixtures.xlsx" `
  --output "REPLACE_WITH_PATH\EQ-conform.xml" `
  --xml-base $XML_BASE

# NOTE: --template-xlsx : CimPal Advanced template Excel file.
#       Format: .xlsx with a 'Config' sheet and one sheet per class.
#       Generate a blank template from RDFS in the GUI's Generate Instance Data tab.
#
#       --output : path for the generated RDF/XML file.
#       Format: absolute path ending in .xml
#       The filename stem becomes the model name in the md:FullModel header.
#
#       --xml-base : same URI as $XML_BASE above.
#
# Typical fixture loop:
#   1. Fill the Excel template for a conforming instance → gen-instances → conforming .xml
#   2. Fill a second template for a non-conforming instance → gen-instances → nonconforming .xml
#   3. validate --workflow manual --shacl-files shapes.ttl --models fixtures/
#   4. Check that conforming passes and non-conforming fires the expected violation.

if ($LASTEXITCODE -ne 0) { Write-Error "Instance generation failed (exit $LASTEXITCODE)"; exit $LASTEXITCODE }
```

```bash
java -jar "$JAR" gen-instances \
  --template-xlsx "REPLACE_WITH_PATH/test-fixtures.xlsx" \
  --output "REPLACE_WITH_PATH/EQ-conform.xml" \
  --xml-base "$XML_BASE"

[ $? -ne 0 ] && { echo "Instance generation failed"; exit 1; }
```

### `excel2shacl` — Generate SHACL from Excel constraint definitions
```
# [NOT YET IMPLEMENTED — Phase 4]
# Generates SHACL shape files from a spreadsheet-based constraint definition.
# Used for spreadsheet-driven constraint authoring workflows.
#
# Will need:
#   --rdfs-file     : RDFS profile .rdf file (provides class/property vocabulary)
#   --template-xlsx : Excel file (.xlsx) with constraint rows
#   --output        : path for the generated .ttl shape file
#   --ns-prefix     : namespace prefix for the shapes
#   --ns-uri        : namespace URI for the shapes
#   --base-uri      : base URI for the shape graph
#   --cims-namespace: CIMS extensions namespace (rarely changed from default)
```

---

## Using the pipeline runner

Once the individual step config files are in place, the entire multi-step run can be driven by a single pipeline file:

```powershell
java -jar $JAR run "REPLACE_WITH_PATH\pipeline-full-validation.json"

# NOTE: The pipeline file references each step's config or inline options.
#       Dry-run first to verify the steps before executing:
java -jar $JAR run "REPLACE_WITH_PATH\pipeline-full-validation.json" --dry-run

# JSON output for CI dashboard:
java -jar $JAR run "REPLACE_WITH_PATH\pipeline-full-validation.json" --format json > pipeline-result.json
```

See the template in `CimPal-CLI/configs/pipeline-full-validation.json` for the full structure.

---

## Config-file version of the full run

All the inline flags above can be replaced by a JSON config file. Recommended for regular runs.

1. Copy `CimPal-CLI/configs/validate-mapping-cgmes30.json` to your working directory.
2. Fill in all `REPLACE_WITH_PATH` values.
3. Run: `java -jar $JAR validate --config my-run.json --format json > result.json`
4. Add `my-run.json` to version control alongside the shapes and mapping file.

This makes the run fully reproducible: the same config + the same inputs always produce the same result.
