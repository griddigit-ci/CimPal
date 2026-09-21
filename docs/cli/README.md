# CimPal CLI — Overview

The CimPal CLI is a headless entry point into CimPal's core logic. It lets you run validation, execute SPARQL queries, and generate manifests from the command line, in scripts, and in CI pipelines — without opening the GUI.

---

## How to run

```
java -jar CimPal-CLI.jar <command> [options]
```

The fat JAR is built by Maven at `CimPal-CLI/target/CimPal-CLI.jar`. Every dependency is bundled inside it; no separate classpath is needed beyond a JRE 25+.

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

## Exit codes

Every command returns a numeric exit code. Scripts and CI systems should check this code, not the text output.

| Code | Meaning |
|---|---|
| **0** | Success — ran cleanly, no violations found |
| **1** | Ran successfully — but validation found violations or warnings |
| **2** | Bad input — missing required argument, file not found, wrong format |
| **3** | Internal error — unexpected exception; check stderr for details |

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
