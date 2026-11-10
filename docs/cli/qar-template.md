<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `qar-template`: QAR template from model files

Lists model files with their profile, modelling authority set (MAS) and model id in a workbook. That workbook is the input of the GUI's **Tools ▸ Quality Reports ▸ Generate QAR based on xls**. It is the same as the GUI's **Tools ▸ Quality Reports ▸ Create xlsx template from QAR**, and both use `QarTemplate` in Core.

Not exposed through `serve`, `mcp` or `run`.

---

## Quick example

```
java -jar CimPal-CLI.jar qar-template --models C:\IGM\REE_EQ.xml,C:\IGM\REE_SSH.zip --output C:\IGM\QAR_template.xlsx
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file (keys `models`, an array, and `output`). Flags override it; relative paths resolve against its folder. |
| `--models` | comma-separated paths | — | Model files (`.xml`, RDF/XML), or `.zip` archives of them, read with the usual archive limits. **Required.** |
| `--output` | file path | — | The `.xlsx` workbook to write. Parent folders are created; an existing file is replaced. **Required.** |
| `--dry-run` | flag | off | Print the resolved configuration and exit. |

---

## The workbook

One sheet, `FileData`, with the columns `FileName`, `FileProfile` (the profile keyword, e.g. `EQ`), `MAS` (`md:Model.modelingAuthoritySet`) and `FileID` (the `md:FullModel` IRI). A value a file doesn't have is an empty cell.

Rows are sorted by MAS, then file name, so the files of one IGM sit next to each other. The GUI used to write them in an unpredictable order.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Workbook written |
| 2 | Bad input: a flag missing, a model not found, unreadable or unparseable, an `--output` that isn't `.xlsx`, or an unreadable config |
| 3 | Internal error, e.g. the workbook can't be written |
