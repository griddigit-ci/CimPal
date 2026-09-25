<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `manifest` — Manifest Generation Command

Generates a DCAT/CGMES manifest Turtle (`.ttl`) file from a folder or list of CGMES model files.

The manifest describes the model files as a DCAT dataset and is required by some CGMES toolchains before validation can run.

---

## Quick examples

**Generate manifest from a folder:**
```
java -jar CimPal-CLI.jar manifest --dir C:\Data\models\cimxml
```
Output is written to `C:\Data\models\manifest.ttl` (parent of the models folder) by default.

**Generate from a specific list of files:**
```
java -jar CimPal-CLI.jar manifest ^
  --files C:\Data\EQ.xml,C:\Data\TP.xml,C:\Data\SV.xml ^
  --output C:\Data\manifest.ttl
```

**With explicit access URL:**
```
java -jar CimPal-CLI.jar manifest ^
  --dir C:\Data\models\cimxml ^
  --access-url "Instance/Belgovia/Grid/cimxml" ^
  --output C:\Data\manifest.ttl
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--dir` | folder path | — | Scan this folder for `.xml`, `.rdf`, and `.ttl` files. Either `--dir` or `--files` is required. |
| `--files` | comma-separated file paths | — | Explicit list of model files to include. Either `--dir` or `--files` is required. |
| `--access-url` | string | value of `--dir` | The `dcat:accessURL` written into the manifest. Defaults to the `--dir` path with forward slashes. |
| `--output` | file path | parent of models folder / `manifest.ttl` | Where to write the manifest. |

---

## Default output location

When `--dir` is used, the manifest is written to the **parent** of the models folder, not inside it. This matches the typical CGMES directory layout where `manifest.ttl` sits one level above the model files:

```
Grid/
  manifest.ttl         ← written here
  cimxml/
    EQ.xml
    TP.xml
    SV.xml
```

Use `--output` to place it anywhere else.

---

## Legacy invocation

The old `ManifestService` command syntax (`java -jar CimPal-CLI.jar --dir ...`) still works for backward compatibility, but it will print a help message from the picocli dispatcher rather than run the manifest command. Use `manifest --dir ...` in new scripts.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Manifest written successfully |
| 2 | Bad input (no files found, directory not found) |
| 3 | Internal error (parse failure, write error) |
