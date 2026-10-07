<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `run` — Pipeline Command

Executes a declarative JSON pipeline of CimPal commands. Steps run sequentially, each using the full option set of the named subcommand. The pipeline stops on error by default but continues through validation violations.

---

## Quick examples

**Run a pipeline:**
```
java -jar CimPal-CLI.jar run pipeline-full-validation.json
```

**Preview steps without executing:**
```
java -jar CimPal-CLI.jar run pipeline-full-validation.json --dry-run
```

**Machine-readable JSON output:**
```
java -jar CimPal-CLI.jar run pipeline-full-validation.json --format json > result.json
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `<pipeline.json>` | file path | — | Pipeline definition file. **Required.** See **Pipeline format** below. |
| `--dry-run` | flag | off | Print all steps with their IDs and commands, then exit without executing anything. |
| `--format` | `text` / `json` | `text` | Output format. `json` writes a machine-readable summary to stdout; step progress goes to stderr. |

---

## Pipeline format

A pipeline is a single JSON file. The top-level keys:

| Key | Type | Default | Description |
|---|---|---|---|
| `name` | string | `(unnamed)` | Human-readable label, shown in progress output. |
| `description` | string | — | Optional longer description. |
| `stopOnError` | boolean | `true` | Stop the pipeline if any step exits with code 2 or 3 (bad input / internal error). |
| `stopOnViolations` | boolean | `false` | Stop the pipeline if any step exits with code 1 (validation violations found). A step's `violationsExitCode` is ignored inside a pipeline, so violations always count as such. |
| `steps` | array | — | Ordered list of step objects. **Required.** |

Each **step** is a JSON object with these special keys:

| Key | Required | Description |
|---|---|---|
| `command` | **yes** | The CimPal CLI subcommand to run: `validate`, `convert`, `compare`, etc. `serve`, `mcp` and `run` are not allowed (see below). |
| `id` | no | Identifier used in output and JSON results. Defaults to `step-N`. |
| `name` | no | Human-readable step name shown in progress. Defaults to the command name. |
| `config` | no | Path to a base config JSON file for this step. Resolved relative to the pipeline file. **Currently not read by any command** (known issue); put options inline. |
| `stopOnError` | no | Per-step override for the pipeline-level `stopOnError`. |
| `stopOnViolations` | no | Per-step override for the pipeline-level `stopOnViolations`. |
| *(any command option)* | no | Inline options for the step, same keys as the command's config file. Override the `config` file values. |

The special keys (`command`, `id`, `name`, `stopOnError`, `stopOnViolations`) are consumed by the pipeline runner. All other keys are forwarded as-is to the step's command as a config file. Keys the command doesn't recognise are silently ignored.

**Steps that are not allowed.** A pipeline can't start a server or another pipeline. If any step's `command` is `serve`, `mcp` or `run` (in any letter case), the whole pipeline is refused with exit code 2 before the first step runs. The same applies with `--dry-run`. A `run` started from inside a running pipeline is also refused with exit 2.

---

## How paths work in steps

**Inline option paths** (e.g. `mappingCsv`, `modelsDir`): relative paths resolve against the pipeline file's folder. Absolute paths are used as they are. Before SEC-2, relative paths resolved against the system temp directory, where the step config is written.

**`config` in a step**: resolved relative to the pipeline file's folder and checked like any other path. **Known issue:** no command currently reads a `config` key from its config file, so options in a referenced config file are ignored. Put the options inline in the step instead.

**Allowed folders.** Step paths may only point inside the allowed folders. By default these are the working directory and the pipeline file's folder; add others with `--root`, `--read-root` or `--write-root` (see [`serve`](serve.md#allowed-folders---root)). Each step is checked just before it runs, so a step may read what an earlier step wrote. A refused path fails that step with exit code 2, naming the path, and the pipeline stops unless `stopOnError` is false. Existing output files named in a step are only replaced when the step sets `"overwrite": true`; files a command creates inside its output folder are replaced. A working directory that is your home folder or a drive root is not used as a default root. Direct use of the other commands (outside `run`) is not restricted.

---

## Prewritten pipeline templates

Located in `CimPal-CLI/configs/`. Fill in the `REPLACE_WITH_PATH` placeholders.

| Template | What it does |
|---|---|
| `pipeline-full-validation.json` | Convert to Turtle, then validate against SHACL constraints |
| `pipeline-profile-migration.json` | Diff two RDFS profile versions, then generate fresh shapes from the new version |

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | All steps passed with no violations |
| 1 | All steps ran; at least one found violations (but no step was configured to stop on violations) |
| 2 | Bad input: pipeline file missing or malformed, no steps, or a `serve`/`mcp`/`run` step |
| 3 | A step failed (exit 2 or 3) and `stopOnError` caused the pipeline to abort |

---

## JSON output format

When `--format json`, the pipeline runner prints a JSON summary to stdout. Step progress goes to stderr.

```json
{
  "schema": "cimpal-pipeline-result/1",
  "timestamp": "2026-09-20T14:30:00Z",
  "pipeline": "Full profile validation",
  "totalSteps": 2,
  "steps": [
    {"id": "convert",  "name": "Convert to canonical Turtle",     "command": "convert",  "exitCode": 0, "status": "OK"},
    {"id": "validate", "name": "Validate against SHACL constraints","command": "validate", "exitCode": 1, "status": "VIOLATIONS"}
  ],
  "summary": {
    "passed": 1,
    "violations": 1,
    "failed": 0,
    "skipped": 0,
    "stopped": false
  }
}
```

---

## Example pipeline: full validation

```json
{
  "name": "Full profile validation",
  "stopOnError": true,
  "stopOnViolations": false,
  "steps": [
    {
      "id": "convert",
      "name": "Convert EQ to Turtle",
      "command": "convert",
      "input": "/data/models/EQ.xml",
      "output": "/data/canonical/EQ.ttl",
      "xmlBase": "http://iec.ch/TC57/CIM100",
      "sort": true
    },
    {
      "id": "validate",
      "name": "Validate",
      "command": "validate",
      "workflow": "mapping",
      "mappingCsv": "/data/mapping.csv",
      "modelsDir": "/data/models",
      "constraintsRoot": "/data/constraints",
      "outputDir": "/data/output",
      "datatypeMap": "CGMES30NC25",
      "xmlBase": "http://iec.ch/TC57/CIM100",
      "format": "json"
    }
  ]
}
```

---

## Using `config` in steps

> **Known issue (found in SEC-2):** commands don't read the `config` key, so a referenced config file currently has no effect. Until that is fixed, put the options inline in the step.

Each step can reference an existing config file with `config`. Inline step keys override the config file values — the same precedence rule as running a command with `--config base.json --flag override`.

```json
{
  "name": "Nightly run",
  "steps": [
    {
      "command": "validate",
      "config": "/runs/nightly-validate.json",
      "maxResultsPerConstraint": 0
    }
  ]
}
```

This runs the nightly validate config but overrides `maxResultsPerConstraint` to 0 (unlimited) for this run.
