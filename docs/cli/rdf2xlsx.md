<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `rdf2xlsx`: any RDF file to Excel, one sheet per type

Turns an RDF file (instance data or an RDFS profile) into a workbook with one sheet per `rdf:type`, one row per subject and one column per property. It is the same as the GUI's **Tools ▸ RDFS Profiles ▸ Export RDF triples to xls**, and both use `RdfTypeSheets` in Core.

Not exposed through `serve`, `mcp` or `run`.

---

## Quick example

```
java -jar CimPal-CLI.jar rdf2xlsx --input C:\Data\Model_EQ.xml --output C:\Reports\Model_EQ.xlsx
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file (keys `input`, `output`). Flags override it; relative paths resolve against its folder. |
| `--input` | file path | — | The RDF file: `.rdf`/`.xml` (RDF/XML, also when the extension is unknown) or `.ttl` (Turtle). JSON-LD and quad formats (TriG, N-Quads) are refused. Relative IRIs resolve against `urn:cimpal:base#`, never the file's local path. **Required.** |
| `--output` | file path | — | The `.xlsx` workbook to write. Parent folders are created; an existing file is replaced. **Required.** |
| `--dry-run` | flag | off | Print the resolved configuration and exit. |

---

## The workbook

- **Sheets:** one per `rdf:type`, named after the type's local name. Names are made safe for Excel, with a `~2` suffix when two types share a local name. Sheets are in type IRI order.
- **Rows:** one per subject, sorted by `rdf:id`.
- **Columns:** `rdf:id` first (the part of the subject IRI after `#`; `_:…` for a blank node), then one column per property as `prefix:name` in alphabetical order.
- **A subject with several types** goes to the sheet of the first type in IRI order. Its other types are listed in an `rdf:type` column.
- **Values:**
  - a literal is its lexical form, with `@lang` appended when it has a language;
  - a numeric literal is a number cell;
  - an IRI is `prefix:local` when the file declares the prefix, else the full IRI (a `file://` namespace is dropped, leaving the local name);
  - several values of one property are joined with ` | `;
  - formula-like text is stored as text.

The output is the same on every run for the same input.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Workbook written |
| 2 | Bad input: a flag missing, the file not found, unreadable, unparseable, JSON-LD or a quad format, no subject with an `rdf:type` (nothing to export), more rows or columns than one sheet holds, an `--output` that isn't `.xlsx`, or an unreadable config |
| 3 | Internal error, e.g. the workbook can't be written |
