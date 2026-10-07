<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Quickstart: validate a model in five minutes

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later*

You need [Java 25 and `CimPal-CLI.jar`](install.md), or Docker. The steps use a small sample: a synthetic CGMES-like model with equipment (EQ) and steady-state (SSH) files, about 2,000 triples, and 10 deliberate errors.

## 1. Get the sample

The sample is in the CimPal repository under [`integrations/airflow/examples/data`](../../integrations/airflow/examples/data/run.json). Clone the repository, or download it as a ZIP from GitHub, and copy that folder somewhere, e.g. `C:\cimpal-quickstart` or `~/cimpal-quickstart`.

It contains:

| File | What it is |
|---|---|
| `models/g001/g001_EQ.xml`, `g001_SSH.xml` | The model: ACLineSegments, Terminals, ConnectivityNodes, VoltageLevels |
| `constraints/bench-shapes.ttl` | The SHACL shapes to check against |
| `mapping.csv` | Which model files are validated against which shapes: one row here |
| `run.json` | The validate config; paths in it are relative to the file |

## 2. Run the validation

With the JAR (PowerShell):
```powershell
cd C:\cimpal-quickstart
java -jar C:\Tools\CimPal\CimPal-CLI.jar validate --config run.json --format json
$LASTEXITCODE
```

With the JAR (bash):
```bash
cd ~/cimpal-quickstart
java -jar /opt/cimpal/CimPal-CLI.jar validate --config run.json --format json
echo $?
```

With Docker (PowerShell; the folder is mounted at `/data`):
```powershell
docker run --rm -v "C:\cimpal-quickstart:/data" ghcr.io/griddigit-ci/cimpal:2026.10.6.1 validate --config /data/run.json --format json
```

With Docker (bash; `--user` makes the report yours on Linux):
```bash
docker run --rm --user "$(id -u):$(id -g)" -v "$HOME/cimpal-quickstart:/data" \
  ghcr.io/griddigit-ci/cimpal:2026.10.6.1 validate --config /data/run.json --format json
```

The exit code is **1**: the run worked and found violations. `0` would mean all conforming, `2` bad input, and `3` an internal error.

## 3. Read the JSON summary

Progress goes to stderr; stdout holds only this JSON (shortened):

```json
{
  "schema": "cimpal-validate-summary/1",
  "run": { "workflow": "mapping", "inputs": { "...": "..." }, "options": { "...": "..." } },
  "totals": { "conforming": 0, "violations": 1, "errors": 0, "total": 1 },
  "hasViolations": true,
  "report": "C:\\cimpal-quickstart\\out\\validation_report__20261006_154310.xlsx"
}
```

- **`totals`** counts the rows of `mapping.csv`: this one row has violations.
- **`hasViolations`** is what a script or scheduler should look at.
- **`report`** is the Excel workbook with every finding.

For a breakdown by constraint, add `--samples 3` (or set `"samples": 3` in `run.json`). The JSON then lists each group of findings with up to three example nodes:

```json
"shapes": [
  { "constraint": "sh:ClassConstraintComponent", "path": "cim:Equipment.EquipmentContainer",
    "count": 2, "sampleFocusNodes": ["_g001_L22", "_g001_L54"] },
  ...
]
```

In the sample that's 10 findings in five groups: a missing `Conductor.length`, a non-integer `sequenceNumber`, an equipment container of the wrong class, `r > x` (a SPARQL rule) and a missing `connected` flag.

## 4. Open the report

The `out` folder now holds `validation_report__<date>_<time>.xlsx`, with four sheets:

| Sheet | Content |
|---|---|
| **Validation results** | One row per finding: dataset, XML files, constraint file, focus node, path, value, source shape, constraint component, message, severity |
| **Validation statistics** | Per validated row: all findings, violations, warnings and infos, whether it conforms, any validation error, missing XML files |
| **StatisticsConstraint** | Findings counted per constraint |
| **Charts** | Overview charts of the statistics |

Run with `--export-turtle` to also get the SHACL validation report as Turtle.

## Next steps

- **Your own data:** copy `run.json` and point it at your mapping CSV, models and constraints. The [`validate` reference](../cli/validate.md) explains the mapping CSV and every option, and `CimPal-CLI/configs/` has commented templates.
- **Large models:** see [Sizing](sizing.md).
- **Automation:** see [Configuration](configuration.md) for exit codes and JSON, and [Airflow](airflow.md) for pipelines.
