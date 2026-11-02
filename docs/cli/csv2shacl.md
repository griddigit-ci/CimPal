<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `csv2shacl`: restore SHACL .ttl files from EA constraint CSV

The GUI's **Tools ▸ SHACL Constraints ▸ Export All SHACL constraints information ▸ EA Import Format** writes each shape as a row of a CSV, with the shape's original Turtle in a `Payload` column. Enterprise Architect imports that CSV. `csv2shacl` goes the other way and rebuilds the `.ttl` files from such a CSV, for example after the constraints were edited in EA and exported again. It is the same as the GUI's **Tools ▸ SHACL Constraints ▸ Restore ttl from constraint csv**, and both use `ConstraintCsvRestore` in Core.

Not exposed through `serve`, `mcp` or `run`.

---

## Quick example

```
java -jar CimPal-CLI.jar csv2shacl ^
  --csv-files C:\EA\SHACL_EA_Import_EQ.csv,C:\EA\SHACL_EA_Import_SSH.csv ^
  --output-dir C:\Shapes\restored
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file (keys `csvFiles`, an array, and `outputDir`). Flags override it; relative paths resolve against its folder. |
| `--csv-files` | comma-separated paths | — | EA constraint CSV files. **Required.** |
| `--output-dir` | folder path | — | Folder for the restored `.ttl` files. Created if needed; files of the same name are replaced. **Required.** |
| `--dry-run` | flag | off | Print the resolved configuration and exit. |

---

## How the CSV becomes .ttl files

- **Columns:** each row's Turtle is its `Payload`, or `NotesWithoutPayload` from older EA exports. Column names are matched ignoring case.
- **Output files:** rows are grouped by `SourceFile`, one `.ttl` per value. Without a `SourceFile` the name comes from the header row (`SHACL:Header::<name>`), else from the CSV's own name.
- **Order:** header rows come first. They are rows with `PayloadKind` = `Header`, ID `__HEADER__`, or a constraint name starting with `SHACL:Header`. A block that appears twice is written once.
- **Apostrophes:** the EA export puts an apostrophe before a value starting with `@`, `=`, `+`, `-` or a tab (spreadsheet formula protection). That would turn every `@prefix` header into `'@prefix`. The restore removes that apostrophe; the GUI version used to leave it in, so its output didn't parse.
- **Validation:** each restored file is parsed as Turtle before it replaces anything. A payload that isn't valid Turtle fails the CSV and leaves any existing file untouched.

### Output names are checked

`SourceFile` comes from the CSV, so it can't choose where files go:
- the name is cut to its last segment, with `\` and `/` both treated as separators, so `..\..\x.ttl`, `\\server\share\x.ttl` and `/etc/x.ttl` all become `x.ttl` in the output folder;
- a name with a colon (a drive-relative path, or an NTFS alternate data stream on Windows), a character Windows refuses (`< > " | ? *`), a control character, or a Windows device name (`CON`, `NUL`, `COM1`, `COM¹`, `CONIN$`, …) is refused;
- the result must resolve inside the output folder;
- names that differ only in case (`EQ.ttl`, `eq.ttl`) are one file, spelled as the first one; they would be one file on Windows and macOS anyway.

Each CSV is all or nothing. Every name is checked, and every file is written and parsed, before the first file replaces anything. A refused name or an invalid block anywhere in the CSV therefore leaves all existing files untouched. Messages that quote the CSV have control characters and bidi overrides neutralised.

Every CSV is processed even when an earlier one fails. Each failure is reported on stderr.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Every CSV restored |
| 2 | Bad input: a flag missing, a CSV not found, or a CSV that failed (no `Payload` column, an unsafe output name, a block that isn't valid Turtle) |
| 3 | Internal error |
