<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `shacl2xlsx` — SHACL → Excel Export

Exports SHACL shape files to an Excel workbook, so a constraint set can be reviewed, filtered and shared without reading Turtle. It is the same export as the GUI's **Tools ▸ Export SHACL constraints information to Excel** (summary layout) and **Tools ▸ Export All SHACL constraints information to Excel ▸ Complete SHACL Predicate Export** (by-type layout). Both use `ShaclExcelExport` in Core.

The GUI's third mode, the **EA Import Format** CSV, is not available from the CLI.

Not exposed through `serve`, `mcp` or `run`.

---

## Quick examples

**One row per constraint (default layout):**
```
java -jar CimPal-CLI.jar shacl2xlsx ^
  --shacl-files C:\Shapes\EQ_Constraints.ttl ^
  --output C:\Reports\EQ_Constraints.xlsx
```

**Every predicate of every shape, several files in one workbook:**
```
java -jar CimPal-CLI.jar shacl2xlsx ^
  --shacl-files C:\Shapes\EQ.ttl,C:\Shapes\SSH.ttl ^
  --output C:\Reports\shapes-by-type.xlsx ^
  --layout by-type
```

**From a config file:**
```
java -jar CimPal-CLI.jar shacl2xlsx --config configs/shacl2xlsx.json
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. Relative paths in it resolve against its folder. |
| `--shacl-files` | comma-separated paths | — | SHACL files to export (`.ttl`, `.rdf`; the format follows the extension, Turtle when unknown). JSON-LD is refused (exit 2), because its reader could fetch a remote `@context`. **Required.** |
| `--output` | file path | — | The `.xlsx` workbook to write. Parent folders are created; an existing file is replaced. **Required.** |
| `--layout` | `summary` \| `by-type` | `summary` | Which workbook to build; see **Layouts**. |
| `--dry-run` | flag | off | Print resolved config and exit without processing. |

---

## Layouts

### `summary`

One row per constraint, with the columns `PropertyShape/NodeShape ID`, `Constraint Name`, `Group`, `Type` (`SPARQL` or `Regular SHACL`), `SPARQL constraint ID`, `Severity`, `Message` and `Constraint Description`.

- Rows are the property shapes that have an `sh:name`, plus the node shapes that have an `sh:severity`. Property shapes without `sh:name`, such as deactivated shapes and `sh:not`/`sh:qualifiedValueShape` helpers, are left out.
- For a SPARQL shape, `Message` is the `sh:message` of its SPARQL constraint (`N/A` if it has none).
- With one file the sheet is called `SHACL Constraints info`. With several files, each file gets its own sheet, named after the file.

### `by-type`

One sheet per kind: `NodeShapes`, `PropertyShapes`, `SPARQLConstraints`, `PropertyGroups`. A sheet is left out when the files have no shape of that kind.

- There is one column per predicate used (`rdf:type` excluded). `sh:name` comes first and the rest follow in QName order.
- Each row is one shape, sorted by `sh:name`.
- Several values of one predicate are joined with ` | `. RDF lists are written as `[a; b]`.
- With several files, all rows share the four sheets, and a first `Source file` column names the file each row came from.

### Cells in both layouts

| Content | How it is written |
|---|---|
| A field the shape doesn't have | Empty cell |
| IRI whose namespace has a prefix | `prefix:local` |
| IRI with no prefix | Full IRI |
| Numeric literal (e.g. `sh:minCount 1`) | Number cell |
| Text that looks like a number (`"007"`) | Text |
| Language tag, in the `by-type` layout | `@lang` appended |
| Language tag, in the `summary` layout | Text only |
| Text starting with `=`, `+`, `-`, `@`, TAB or CR | Stored quote-prefixed, so Excel shows it as text and never evaluates it |
| Text longer than an Excel cell holds (32,767 characters) | Cut to fit, ending in `…` |

Sheet names are made safe for Excel: characters it refuses (`[ ] * ? / \ :`) become `_`, and names are cut to 31 characters. When two names clash after that, the later one gets a `~2`, `~3`, … suffix. This matters for CGMES file names, which often share their first 31 characters.

---

## JSON config format

```json
{
  "_comment": "Export SHACL shapes to Excel",
  "shaclFiles": ["REPLACE_WITH_PATH/EQ.ttl", "REPLACE_WITH_PATH/SSH.ttl"],
  "output": "REPLACE_WITH_PATH/shapes.xlsx",
  "layout": "summary"
}
```

See `CimPal-CLI/configs/shacl2xlsx.json` for the annotated template.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Workbook written |
| 2 | Bad input: a required flag missing, a SHACL file not found, unreadable, not parseable or JSON-LD, an unknown `--layout`, an `--output` that isn't `.xlsx`, or an unreadable config file |
| 3 | Internal error, e.g. the workbook can't be written |
