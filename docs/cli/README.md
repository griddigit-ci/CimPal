<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# CimPal CLI — Overview

The CimPal CLI is a headless entry point into CimPal's core logic. It lets you run validation, execute SPARQL queries, and generate manifests from the command line, in scripts, and in CI pipelines — without opening the GUI.

---

## How to run

```
java -jar CimPal-CLI.jar <command> [options]
```

The fat JAR is built by Maven at `CimPal-CLI/target/CimPal-CLI.jar`. Every dependency is bundled inside it; no separate classpath is needed beyond a JRE 25+.

Without a local Java, run the same JAR from the Docker image that every release publishes. Mount your files and use container paths, see [docker](docker.md):
```
docker run --rm -v "C:\Data:/data" ghcr.io/griddigit-ci/cimpal:latest <command> [options]
```

**List all commands:**
```
java -jar CimPal-CLI.jar --help
```

**Help for a specific command:**
```
java -jar CimPal-CLI.jar validate --help
java -jar CimPal-CLI.jar sparql --help
java -jar CimPal-CLI.jar manifest --help
```

---

## Available commands

| Command | What it does |
|---|---|
| `validate` | Run SHACL validation against CIM/CGMES model files |
| `sparql` | Execute a SPARQL SELECT query against RDF model files |
| `manifest` | Generate a DCAT/CGMES manifest Turtle file |

---

## Resource statistics (`--stats`)

`validate`, `sparql`, `compare` and `compare-instances` take `--stats` (config key `"stats": true`). The run then reports how much it used:

- with `--format json` on stdout: a last field `stats` in the one JSON object, so `serve` and `mcp` still return a single JSON value;
- otherwise (text or CSV output, or `--output` to a file): one line `[STATS] {...}` on stderr.

Without `--stats` the output is unchanged.

```json
"stats": {"wallMs": 1984, "phases": {"validate": 1651, "shapeDetail": 9}, "cpuMs": 12250,
          "peakHeapBytes": 103234064, "maxHeapBytes": 4213178368, "peakRssBytes": null, "gcMs": 43,
          "availableProcessors": 16, "inputBytes": 1440999, "triplesLoaded": 19977,
          "javaVersion": "25.0.1+8-LTS-27", "os": "Windows 11 10.0 amd64"}
```

| Field | Meaning |
|---|---|
| `wallMs` | Wall time from the start of the command until its output is written. JVM start-up is not included. |
| `phases` | Wall time per phase: `validate` and `shapeDetail` (validate), `load` and `query` (sparql), `load` and `compare` (the compare commands). |
| `cpuMs` | CPU time of the whole process during the run, all threads. `cpuMs / wallMs` is about the number of cores actually used. `null` where the JVM can't report it. |
| `peakHeapBytes` | The sum of each heap pool's own peak usage. The pools (young and old generation) peak at different moments, so this is an upper bound and can be larger than `maxHeapBytes`. The smallest heap a model needs is measured by running it at a given `-Xmx`, as the [sizing guide](../guide/sizing.md) does. |
| `maxHeapBytes` | The heap limit the run had. |
| `peakRssBytes` | Peak resident memory of the process (Linux `VmHWM`), which is what a container limit sees. `null` on Windows and macOS. |
| `gcMs` | Garbage-collection time. A GC share (`gcMs / wallMs`) above about 10 % means the heap is too small. |
| `availableProcessors` | Cores the JVM may use; honours container CPU limits and `-XX:ActiveProcessorCount`. |
| `inputBytes` | Bytes of model files read. Files inside a ZIP are not counted. |
| `triplesLoaded` | Triples parsed from the input models: the size measure of the [sizing guide](../guide/sizing.md). For `validate --workflow mapping`, per row (a file used by two rows counts twice); for `timestamped`, each file once. The `manual` workflow reports 0. |
| `javaVersion`, `os` | The runtime. |

The schema is `CimPal-CLI/src/test/resources/fixtures/cli-json/stats.schema.json`. Fields may be added later; none will be removed or renamed.

**Out of memory.** A command that runs out of heap ends at once with exit code **3** and one line on stderr: `[ERROR] Out of memory (max heap N MB). Give the JVM more memory (-Xmx, or the container's memory limit); see docs/guide/sizing.md for sizes by model.` It is never reported as exit 1 ("violations found") and never as a failed row in the report. The [Docker image](docker.md#memory-and-cpu) does the same with `-XX:+ExitOnOutOfMemoryError`.

---

## Exit codes

Every command returns a numeric exit code. Scripts and CI systems should check this code, not the text output.

| Code | Meaning |
|---|---|
| **0** | Success — ran cleanly, no violations found |
| **1** | Ran successfully — but validation found violations or warnings |
| **2** | Bad input — missing required argument, file not found, wrong format |
| **3** | Internal error — unexpected exception, or the JVM ran out of memory; check stderr for details |

The distinction between 0 and 1 is the most important one for CI. A code of 1 does not mean the tool broke — it means the model has violations. A code of 2 or 3 means the tool itself failed to run.

In PowerShell:
```powershell
java -jar CimPal-CLI.jar validate --config my-run.json
if ($LASTEXITCODE -eq 1) { Write-Error "Violations found — see report in output folder" }
if ($LASTEXITCODE -ge 2) { Write-Error "CLI failed to run — check stderr" }
```

In bash:
```bash
java -jar CimPal-CLI.jar validate --config my-run.json
exit_code=$?
if [ $exit_code -eq 1 ]; then echo "Violations found"; fi
if [ $exit_code -ge 2 ]; then echo "Tool error"; exit 1; fi
```

---

## Config files vs. inline flags

Every command accepts a `--config <file>` argument pointing to a JSON file that holds all parameters. Individual flags typed on the command line always override the config file.

**Why use config files:**
- The command stays short and readable: `java -jar CimPal-CLI.jar validate --config nightly.json`
- Config files can be committed to git alongside the shapes, so a validation run is fully reproducible
- Multiple run profiles (quick check, full run, timestamped) can each have their own file

**Why use inline flags:**
- One-off queries or quick checks where a config file would be overhead
- Override a single value from a shared config: `... --config shared.json --max-results 10`

---

## Config file format

Config files are plain JSON. A few conventions apply:

**`_comment`** keys are silently ignored by the CLI — they are a description of the file for humans. Put anything you like there.

**`_note_*`** keys (e.g. `_note_workflow`) are also silently ignored. They are used in the template files to explain what a field means and what values are valid.

**Relative paths** in config files are resolved relative to the config file's own location. If the config is at `/data/runs/nightly.json` and it says `"modelsDir": "models"`, that resolves to `/data/runs/models`. Use absolute paths to avoid ambiguity.

**Flags on the command line override the config file.** Order does not matter; the flag always wins.

Example: use the nightly config but cap results for a quick check:
```
java -jar CimPal-CLI.jar validate --config nightly.json --max-results 10
```

---

## Template configs

Ready-to-use templates are in `CimPal-CLI/configs/`. Copy one to your working directory, fill in the `REPLACE_WITH_PATH` placeholders with real paths, and run.

| Template | Workflow |
|---|---|
| `validate-mapping-cgmes30.json` | Full mapping validation, CGMES 3.0 / NC 2.5 |
| `validate-manual.json` | Manual SHACL tester — hand-pick shapes, scan a model folder |
| `validate-timestamped.json` | Timestamped validation with comparison to previous run |
| `sparql-query.json` | SPARQL SELECT query against model files |

---

## Where outputs go

- **Excel reports** are always written to the `outputDir` specified in your config or `--output` flag. The CLI never writes to the current directory automatically.
- **JSON summary** (when `--format json`) is written to **stdout**. Redirect it: `... --format json > result.json`
- **Progress messages** always go to **stderr** — they do not appear in the JSON output even without redirection.
- **Turtle validation reports** (`.ttl`) are written beside the Excel report when `--export-turtle` is set.
