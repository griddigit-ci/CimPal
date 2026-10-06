<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Configuration

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later*

This page gathers what all commands have in common. The [CLI reference](../cli/index.md) documents each command's options.

## Config files

Every command takes its options as flags, from a JSON file (`--config file.json`), or both.

```json
{
  "_comment": "Nightly validation of the TSO models",
  "workflow": "mapping",
  "mappingCsv": "mapping.csv",
  "modelsDir": "models",
  "constraintsRoot": "constraints",
  "outputDir": "out",
  "_note_outputDir": "Created if missing; one Excel report per run."
}
```

- **Precedence:** a flag on the command line wins over the same key in the file.
- **Relative paths** in the file are relative to **the file's folder**, not the current directory. A config next to its data works from anywhere.
- **Documentation keys:** keys starting with `_` (`_comment`, `_note_*`, `_how_to_use`) are ignored. Use them for notes.
- **Templates:** `CimPal-CLI/configs/` has a commented template for every command, e.g. `validate-mapping-cgmes30.json`.
- **Checking a config:** `validate --dry-run` prints the resolved options without running anything.

## Exit codes

| Code | Meaning |
|---|---|
| **0** | Ran; no violations or differences |
| **1** | Ran; violations or differences found, or a validated row failed with an error |
| **2** | Bad input: missing file, bad option, refused path. Fix the input; retrying won't help. |
| **3** | Internal error, or the JVM ran out of memory. Check stderr. |

- **Exit 1 is a result, not a failure.** Scripts should treat it as "look at the report".
- **Schedulers that fail every non-zero exit:** add `--violations-exit-code 0` (config `violationsExitCode`). Found violations then exit 0, and `hasViolations` in the JSON says what happened. Exit 2, exit 3, and exit 1 for rows that failed with an error never change.
- **Availability:** this needs the release after 2026.10.6.1.

## JSON output

`validate`, `sparql`, `compare` and `compare-instances` take `--format json`.
- **Streams:** stdout then holds exactly one JSON document, and progress messages go to stderr.
- **Schemas:** each document names its schema (`cimpal-validate-summary/1`, `cimpal-compare-result/1`, `cimpal-compare-instances-result/1`). `sparql` returns `{"columns": […], "rows": […]}`.
- **More fields:** `--stats` adds a `stats` field (wall and CPU time, peak heap, GC, triples loaded). `validate --samples N` adds per-constraint groups with N example focus nodes.
- **To a file:** `--summary-file <path>` (config `summaryFile`) also writes the same document to a file, atomically, creating parent folders, with or without `--format json`. Release after 2026.10.6.1.

Details: [CLI reference: resource statistics and automation options](../cli/README.md#automation-options---summary-file---violations-exit-code).

## Environment variables

| Variable | Used by | Purpose |
|---|---|---|
| `GITHUB_TOKEN` | all | Token for fetching `owl:imports` from GitHub (higher rate limits, private repositories) |
| `CIMPAL_API_TOKEN` | `serve` | The bearer token, at least 32 characters, instead of a generated one in a token file |
| `JAVA_OPTS` | container image | Extra JVM options, after the image's defaults, e.g. `-Xmx6g` |
| `JAVA_TOOL_OPTIONS` | any JVM | JVM options picked up by Java itself; printed on stderr, so never put secrets in it |
| `USE_SYSTEM_CA_CERTS` | container image | `1` imports the certificates mounted at `/certificates` (TLS-inspecting proxies) |

## Memory and the JVM

- **JAR:** set the heap with `-Xmx`, e.g. `java -Xmx4g -jar CimPal-CLI.jar …`.
- **Container image:** it uses 75 % of the container's memory limit for the heap. Change that with `JAVA_OPTS`.
- **Sizes by model:** [Sizing](sizing.md).
- **Out of memory:** the run ends with exit 3 and a one-line message, never with a wrong result.

## Output files

| Command | Writes |
|---|---|
| `validate` | `validation_report__<date>_<time>.xlsx` in `outputDir`; with `--export-turtle` (or `--samples` > 0) also `*__report.ttl` per row; timestamped runs write one workbook per timestamp plus summaries |
| `compare`, `compare-instances`, `sparql` | stdout, or `--output` (`.xlsx` or CSV) |
| `rdfs2shacl`, `excel2shacl`, `organize`, `convert`, `manifest`, `gen-instances` | the files their options name |

CimPal also keeps a cache of fetched imports, private to your user: `%LOCALAPPDATA%\CimPal` on Windows, `~/.cimpal` elsewhere.
